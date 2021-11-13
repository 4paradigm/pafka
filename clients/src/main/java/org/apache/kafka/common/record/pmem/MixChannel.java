/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.common.record.pmem;

import org.apache.kafka.common.record.pmem.UnitedStorage.SelectMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Date;

import static org.apache.kafka.common.record.pmem.PMemChannel.toRelativePath;

public class MixChannel extends FileChannel {
    public static final String PMEM_TYPE = "pmem";
    public static final String TIERED_TYPE = "tiered";
    public enum Mode {
        PMEM(0),
        NVME(1),
        SSD(2),
        HDD(3);

        public final int value;
        public static final int LEN = Mode.values().length;
        private Mode(int value) {
            this.value = value;
        }

        public static Mode fromInteger(int x) {
            switch (x) {
                case 0:
                    return PMEM;
                case 1:
                    return NVME;
                case 2:
                    return SSD;
                case 3:
                    return HDD;
            }
            return null;
        }

        public static Mode fromString(String str) {
            if (str.compareToIgnoreCase("PMEM") == 0) {
                return PMEM;
            } else if (str.compareToIgnoreCase("SSD") == 0) {
                return SSD;
            } else if (str.compareToIgnoreCase("NVME") == 0) {
                return NVME;
            } else if (str.compareToIgnoreCase("HDD") == 0) {
                return HDD;
            }
            return null;
        }

        public boolean higherThan(Mode other) {
            return value < other.value;
        }

        public boolean equal(Mode other) {
            return value == other.value;
        }
    };

    public enum Status {
        INIT,
        MIGRATION;
    };

    private static class Stat {
        private volatile long capacity = -1;
        private volatile long used = 0;

        void setCapacity(long capacity) {
            this.capacity = capacity;
        }

        long getCapacity() {
            return this.capacity;
        }

        void setUsed(long used) {
            this.used = used;
        }

        void addUsed(long delta) {
            setUsed(this.used + delta);
        }

        void minusUsed(long delta) {
            setUsed(this.used - delta);
        }

        long getUsed() {
            return this.used;
        }
    };

    private static final Logger log = LoggerFactory.getLogger(MixChannel.class);
    private static final String TIMESTAMP_FIELD = "_timestamp_";
    private static Mode defaultHighMode = Mode.PMEM;
    private static Mode defaultLowMode = Mode.HDD;
    private static MetaStore metaStore = null;
    private static PMemMigrator migrator = null;
    private final static Object GLOBAL_LOCK = new Object();
    private static Stat highStat = new Stat();
    private static UnitedStorage lowStorage = null;
    private static UnitedStorage highStorage = null;

    private volatile Mode mode = defaultHighMode;
    private Status status = Status.INIT;
    private final Object lock = new Object();
    private volatile boolean deleted = false;
    private AtomicInteger readCount = new AtomicInteger(0);
    private AtomicInteger writeCount = new AtomicInteger(0);

    /**
     * use different location for different channels to avoid thread-risk issues
     */
    private volatile FileChannel[] channels = null;

    private Path path = null;
    private String relativePath = null;
    /**
     * namespace is like topic in the context of Kafka
     */
    private String namespace;
    private long id = -1;
    private Date timestamp;

    public static Mode getDefaultMode() {
        return defaultHighMode;
    }

    public static void init(String highPaths, String lowPaths, String capacities, double threshold, int migrateThreads) {
        init(highPaths, Mode.PMEM, lowPaths, Mode.HDD, capacities, threshold, migrateThreads);
    }

    public static void init(String highPaths, Mode highMode, String lowPaths, String capacities, double threshold, int migrateThreads) {
        init(highPaths, highMode, lowPaths, Mode.HDD, capacities, threshold, migrateThreads);
    }

    public static void init(String highPaths, String highMode, String lowPaths, String lowMode, String capacities,
            double threshold, int migrateThreads) {
        init(highPaths, Mode.fromString(highMode), lowPaths, Mode.fromString(lowMode), capacities, threshold, migrateThreads);
    }

    public static void init(String highPaths, Mode highMode, String lowPaths, Mode lowMode, String capacities,
            double threshold, int migrateThreads) {
        if (lowMode == Mode.PMEM) {
            log.error("low-level storage cannot be PMem");
            return;
        }

        defaultHighMode = highMode;
        defaultLowMode = lowMode;

        highStorage = new UnitedStorage(highPaths, capacities);
        long totalCapacity = highStorage.capacity();
        highStat.setCapacity(totalCapacity);
        log.info(defaultHighMode + " capacity is set to " + totalCapacity);

        lowStorage = new UnitedStorage(lowPaths, SelectMode.SYS_FREE);

        metaStore = new RocksdbMetaStore(highPaths.split(",")[0] + "/.meta");

        // start migration background threads
        if (threshold != -1) {
            migrator = new PMemMigrator(migrateThreads, totalCapacity, threshold);
            migrator.start();
        } else {
            log.info("Disable Migrator");
        }
    }

    public static void stop() throws InterruptedException {
        if (migrator != null) {
            migrator.stop();
        }
    }

    public static MetaStore getMetaStore() {
        return metaStore;
    }

    public static long getUsed() {
        return highStat.getUsed();
    }

    public static long getCapacity() {
        return highStat.getCapacity();
    }

    public static MixChannel open(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        MixChannel ch = null;
        log.info("Creating MixChannel " + file.toString());
        synchronized (GLOBAL_LOCK) {
            ch = new MixChannel(file, initFileSize, preallocate, mutable);
        }
        if (migrator != null) {
            migrator.add(ch);
        }
        return ch;
    }

    public MixChannel(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        this.path = file;
        this.relativePath = toRelativePath(file);
        String[] toks = this.relativePath.split("/");
        if (toks.length != 2) {
            throw new RuntimeException(this.relativePath + " not in the format of topic/id.log");
        }
        this.namespace = toks[0];
        this.id = Long.parseLong(toks[1].split("\\.")[0]);

        this.channels = new FileChannel[Mode.LEN];
        for (int i = 0; i < Mode.LEN; i++) {
            this.channels[i] = null;
        }

        int modeValue = metaStore.getInt(relativePath);
        log.info(defaultHighMode + " used: " + highStat.getUsed() + ", capacity: " + highStat.getCapacity());
        if (modeValue != MetaStore.NOT_EXIST_INT) {
            this.mode = Mode.fromInteger(modeValue);
            FileChannel ch = null;
            long epoch = metaStore.getLong(relativePath, TIMESTAMP_FIELD);
            if (epoch != MetaStore.NOT_EXIST_LONG) {
                this.timestamp = new Date(epoch);
            } else {
                log.error("timestamp not exists in metaStore for " + relativePath);
                this.timestamp = new Date();
            }

            if (this.mode == defaultHighMode) {
                if (this.mode == Mode.PMEM) {
                    ch = PMemChannel.open(file, initFileSize, preallocate, mutable);
                    highStat.addUsed(((PMemChannel) ch).occupiedSize());
                } else {
                    ch = openHighFileChannel(file, initFileSize, preallocate, mutable);
                    highStat.addUsed(ch.size());
                }
            } else if (this.mode == defaultLowMode) {
                ch = openLowFileChannel(file, initFileSize, preallocate, mutable);

                /*
                 * The following code is to handle cases: - during migration from HDD to PMEM:
                 * after new PMemChannel allocated, program crash before we change the mode meta
                 * in metaStore. Our strategy is to delete the PMEM channel and insist what we
                 * got from metaStore.
                 */
                if (defaultHighMode == Mode.PMEM && PMemChannel.exists(file)) {
                    log.error(file + " exist, but no info from metaStore.");
                    PMemChannel pCh = (PMemChannel) PMemChannel.open(file, initFileSize, preallocate, mutable);
                    pCh.delete(false);
                }
            } else {
                log.error("Not support ChannelModel " + this.mode);
            }
            this.channels[this.mode.value] = ch;

            log.info("Recover MixChannel " + this.mode + ", path = " + file + ", initSize = " + initFileSize
                    + ", preallocate = " + preallocate + ", mutable = " + mutable);
        } else {
            try {
                if (highStat.getUsed() + initFileSize <= highStat.getCapacity()) {
                    this.mode = defaultHighMode;
                    FileChannel ch = null;
                    if (defaultHighMode == Mode.PMEM) {
                        ch = PMemChannel.open(file, initFileSize, preallocate, mutable);
                    } else {
                        ch = openHighFileChannel(file, initFileSize, preallocate, mutable);
                    }
                    this.channels[this.mode.value] = ch;
                    highStat.addUsed(initFileSize);
                } else {
                    this.mode = defaultLowMode;
                    log.info(defaultHighMode + " used (" + (highStat.getUsed() + initFileSize) + " Bytes) exceeds limit (" + highStat.getCapacity()
                            + " Bytes). Using normal FileChannel instead.");
                    this.channels[this.mode.value] = openLowFileChannel(file, initFileSize, preallocate, mutable);
                }
            } catch (Exception e) {
                this.mode = defaultLowMode;
                log.info("Fail to allocate in " + defaultHighMode + " channel. Using normal FileChannel instead.", e);
                this.channels[this.mode.value] = openLowFileChannel(file, initFileSize, preallocate, mutable);
            }

            this.timestamp = new Date();
            metaStore.putInt(relativePath, this.mode.value);
            metaStore.putLong(relativePath, TIMESTAMP_FIELD, this.timestamp.getTime());

            log.info("Create MixChannel " + this.mode + ", path = " + file + ", initSize = " + initFileSize
                    + ", preallocate = " + preallocate + ", mutable = " + mutable);
        }
    }

    public String getNamespace() {
        return this.namespace;
    }

    public long getId() {
        return this.id;
    }

    public Date getTimestamp() {
        return this.timestamp;
    }

    @Override
    public String toString() {
        String status = " [Status: " + this.status + ", delete status: " + deleted + "]";
        return "MixChannel " + this.mode + " " + getNamespace() + "/" + getId() + status;
    }

    public Status setStatus(Status s) {
        this.status = s;
        return s;
    }

    public Status getStatus() {
        return this.status;
    }

    public Mode getMode() {
        return this.mode;
    }

    public boolean setMode(Mode m) throws IOException {
        if (this.mode != m) {
            if (deleted) {
                return false;
            }

            FileChannel newChannel = null;
            try {
                newChannel = migrate(m);
            } catch (IOException e) {
                if (deleted) {
                    return false;
                } else {
                    throw e;
                }
            }

            if (newChannel == null) {
                return false;
            }

            Mode oldMode = getMode();
            if (!safeDeleteOldSetNew(m, newChannel)) {
                return false;
            }

            synchronized (GLOBAL_LOCK) {
                if (oldMode.higherThan(this.mode)) {
                    highStat.minusUsed(this.occupiedSize());
                }
            }
        }

        return true;
    }

    private boolean safeDeleteOldSetNew(Mode newMode, FileChannel newChannel) throws IOException {
        synchronized (this.lock) {
            /*
             * there may be a case, where another thread is deleting this channel, acquired the lock
             * after delete, we have to abort the transform and delete any related resources
             */
            if (deleted) {
                deleteChannel(newChannel, newMode, false);
                return false;
            }

            FileChannel oldChannel = getChannel();
            Mode oldMode = getMode();

            this.channels[newMode.value] = newChannel;
            // below is necessary to ensure elements in channels is volatile
            // remove for now to pass findBug checking
            // this.channels = this.channels;

            // until this point, the newChannel will take effect
            this.mode = newMode;
            metaStore.putInt(relativePath, this.mode.value);

            /*
             * the concurrency risk is the case there are other readers are reading the data
             * writers are not possible as we only change the mode for Channels that are stable (old segments)
             * Our strategy: we wait until all the onging tasks complete
             */
            while (readCount.get() != 0) { }
            while (writeCount.get() != 0) { }
            this.channels[oldMode.value] = null;
            deleteChannel(oldChannel, oldMode, false);
        }
        return true;
    }

    private FileChannel migrate(Mode m) throws IOException {
        /*
         * if it crash in the middle, we have to clean the newly-allocated, but not-in-used PMem channels
         * For HDD FileChannel, it is ok, as the migration is deterministic,
         * during next startup, we'll do the same migration strategy and will override the same file.
         */
        FileChannel newChannel = null;
        if (m == defaultHighMode) {
            synchronized (GLOBAL_LOCK) {
                if (highStat.getUsed() + this.size() <= highStat.getCapacity()) {
                    if (m == Mode.PMEM) {
                        newChannel = PMemChannel.open(this.path, (int) this.size(), true, true);
                    } else {
                        newChannel = openHighFileChannel(this.path, this.size(), true, true);
                    }
                    highStat.addUsed(this.size());
                } else {
                    log.info("Migrate failed (cannot create PMemChannel): " + defaultHighMode + " used = "
                            + highStat.getUsed() + ", limit = " + highStat.getCapacity());
                    return null;
                }
            }
        } else {
            newChannel = openLowFileChannel(this.path, this.size(), true, true);
        }

        FileChannel oldChannel = getChannel();
        long size = oldChannel.size();
        long transferred = 0;
        long batch = 1024L * 1024;
        boolean aborted = false;
        do {
            if (deleted) {
                log.warn(this + " was deleted. Abort migration");
                aborted = true;
                break;
            }
            batch = batch > size ? size : batch;
            long t = oldChannel.transferTo(transferred, batch, newChannel);
            transferred += t;
        } while (transferred < size);

        if (!aborted && (size == 0 || size != newChannel.position())) {
            log.error("[" + this + "]: migrated " + newChannel.position() + " (expected size = " + size + ")");
        }
        return newChannel;
    }

    private FileChannel getChannel() {
        if (this.channels == null) {
            log.error(this + " is null.");
            return null;
        }

        Mode m = this.mode;
        FileChannel ch = this.channels[m.value];
        if (ch == null) {
            log.error(this + " is null.");
        }
        return ch;
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        readCount.incrementAndGet();
        int ret = 0;
        try {
            ret = getChannel().read(dst);
        } finally {
            readCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        readCount.incrementAndGet();
        long ret = 0;
        try {
            ret = getChannel().read(dsts, offset, length);
        } finally {
            readCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        writeCount.incrementAndGet();
        int ret = 0;
        try {
            ret = getChannel().write(src);
        } finally {
            writeCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        writeCount.incrementAndGet();
        long ret = 0;
        try {
            ret = getChannel().write(srcs, offset, length);
        } finally {
            writeCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public long position() throws IOException {
        readCount.incrementAndGet();
        long ret = 0;
        try {
            ret = getChannel().position();
        } finally {
            readCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        writeCount.incrementAndGet();
        FileChannel ret = null;
        try {
            ret = getChannel().position(newPosition);
        } finally {
            writeCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public long size() throws IOException {
        return getChannel().size();
    }

    public long occupiedSize() {
        if (getMode() == Mode.PMEM) {
            return ((PMemChannel) getChannel()).occupiedSize();
        } else {
            try {
                return size();
            } catch (IOException e) {
                log.error("get size() exception: ", e);
                return 0;
            }
        }
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        log.info(relativePath + " was truncated to  " + size);
        writeCount.incrementAndGet();
        FileChannel ret = null;
        try {
            ret = getChannel().truncate(size);
        } finally {
            writeCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public void force(boolean metaData) throws IOException {
        writeCount.incrementAndGet();
        try {
            getChannel().force(metaData);
        } finally {
            writeCount.decrementAndGet();
        }
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        readCount.incrementAndGet();
        long ret = 0;
        try {
            ret = getChannel().transferTo(position, count, target);
        } finally {
            readCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        writeCount.incrementAndGet();
        long ret = 0;
        try {
            ret = getChannel().transferFrom(src, position, count);
        } finally {
            writeCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        readCount.incrementAndGet();
        int ret = 0;
        try {
            ret = getChannel().read(dst, position);
        } finally {
            readCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        writeCount.incrementAndGet();
        int ret = 0;
        try {
            ret = getChannel().write(src, position);
        } finally {
            writeCount.decrementAndGet();
        }
        return ret;
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
        return getChannel().map(mode, position, size);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws IOException {
        return getChannel().lock(position, size, shared);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws IOException {
        return getChannel().tryLock(position, size, shared);
    }

    public void delete() throws IOException {
        deleted = true;
        // remove from migrator
        if (migrator != null) {
            migrator.remove(this);
        }

//        while (this.status == Status.MIGRATION) {
//            log.info("Delete " + path + " blocking due to migration in process");
//            try {
//                Thread.sleep(500);
//            } catch (InterruptedException e) {
//                log.error("sleep Exception: ", e);
//            }
//        }

        synchronized (this.lock) {
            FileChannel channel = getChannel();
            if (mode == defaultHighMode) {
                if (mode == Mode.PMEM) {
                    PMemChannel pChannel = (PMemChannel) channel;
                    highStat.minusUsed(pChannel.occupiedSize());
                } else {
                    highStat.minusUsed(channel.size());
                }
            }
            deleteChannel(channel, mode, true);
            this.channels = null;

            metaStore.del(relativePath);
        }
    }

    @Override
    protected void implCloseChannel() throws IOException {
        if (deleted) {
            return;
        }

        if (migrator != null) {
            migrator.remove(this);
        }

        for (FileChannel channel : this.channels) {
            if (channel != null) {
                channel.close();
            }
        }
    }

    static private FileChannel openLowFileChannel(Path file, long initFileSize, boolean preallocate, boolean mutable)
            throws IOException {
        return openFileChannel(lowStorage, file, initFileSize, preallocate, mutable);
    }

    static private FileChannel openHighFileChannel(Path file, long initFileSize, boolean preallocate, boolean mutable)
            throws IOException {
        return openFileChannel(highStorage, file, initFileSize, preallocate, mutable);
    }

    static private FileChannel openFileChannel(UnitedStorage storage, Path file, long initFileSize, boolean preallocate,
            boolean mutable) throws IOException {
        Path absPath = null;
        String rPath = toRelativePath(file);

        if (storage.containsAbsolute(file.toString())) {
            absPath = file;
        } else {
            absPath = new File(storage.toAbsolute(rPath)).toPath();
        }

        FileChannel ch = null;
        if (mutable) {
            boolean fileAlreadyExists = absPath.toFile().exists();
            if (fileAlreadyExists || !preallocate) {
                return FileChannel.open(absPath, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
            } else {
                Path parent = absPath.getParent();
                if (parent != null && !parent.toFile().exists()) {
                    if (!parent.toFile().mkdirs()) {
                        log.error("Create directory " + parent + " failed");
                    }
                }
                RandomAccessFile randomAccessFile = new RandomAccessFile(absPath.toString(), "rw");
                randomAccessFile.setLength(initFileSize);
                ch = randomAccessFile.getChannel();
            }
        } else {
            ch = FileChannel.open(absPath);
        }

        // create an empty log file as Kafka will check its existence
        Path parent = file.getParent();
        if (parent != null && !parent.toFile().mkdirs()) {
            log.debug(file.getParent() + " already exists");
        }

        if (!file.toFile().createNewFile()) {
            log.debug(file + " already exits");
        }

        return ch;
    }

    static private void deleteLowFileChannel(FileChannel channel, Path file) throws IOException {
        deleteFileChannel(lowStorage, channel, file);
    }

    static private void deleteHighFileChannel(FileChannel channel, Path file) throws IOException {
        deleteFileChannel(highStorage, channel, file);
    }

    static private void deleteFileChannel(UnitedStorage storage, FileChannel channel, Path file) throws IOException {
        channel.close();

        // for id file, we only set its length to 0
        // defer the delete logic
        if (storage.containsAbsolute(file.toString())) {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file.toFile(), "rw");
            randomAccessFile.setLength(0);
            log.info("Reset " + file);
            return;
        } else {
            String rPath = toRelativePath(file);
            String absPath = storage.toAbsolute(rPath);
            Path lPath = new File(absPath).toPath();
            Files.deleteIfExists(lPath);
            log.info("Delete " + file + " @ " + absPath);
        }
    }

    private void deleteChannel(FileChannel channel, Mode mode, boolean deleteOrigFile) throws IOException {
        if (mode == defaultLowMode) {
            deleteLowFileChannel(channel, path);
        } else if (mode == Mode.PMEM) {
            ((PMemChannel) channel).delete(deleteOrigFile);
        } else if (mode == defaultHighMode) {
            deleteHighFileChannel(channel, path);
        } else {
            log.error("Not support channel " + mode);
        }

        if (mode != Mode.PMEM && deleteOrigFile) {
            Files.deleteIfExists(path);
        }
    }
}
