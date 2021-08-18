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
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.io.File;

public class MixChannel extends FileChannel {
    enum Mode {
        PMEM(0),
        NVME(1),
        SSD(2),
        HDD(3);

        public int value;
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

        public boolean higherThan(Mode other) {
            return value < other.value;
        }

        public boolean equal(Mode other) {
            return value == other.value;
        }
    };

    enum Status {
        INIT,
        MIGRATION;
    };


    private static final Logger log = LoggerFactory.getLogger(MixChannel.class);
    private static Mode defaultMode = Mode.PMEM;
    private static MetaStore metaStore = null;
    private static PMemMigrator migrator = null;
    private static volatile long highCapacity = -1;
    private static volatile long highUsed = 0;
    private final static Object GLOBAL_LOCK = new Object();
    
    private volatile Mode mode = defaultMode;
    private Status status = Status.INIT;

    /**
     * use different location for different channels to avoid thread-risk issues
     */
    private FileChannel[] channels = null;

    private Path path = null;
    private String relativePath = null;
    /**
     * namespace is like topic in the context of Kafka
     */
    private String namespace = null;
    private long id = -1;

    public static Mode getDefaultMode() {
        return defaultMode;
    }

    public static void init(String path, long capacity, double threshold, int migrateThreads) {
        metaStore = new RocksdbMetaStore(path + "/.meta");

        // use all the storage space if capacity is configured to -1
        if (capacity == -1) {
            File file = new File(path);
            capacity = file.getTotalSpace();
        }
        highCapacity = capacity;
        log.info(defaultMode + " capacity is set to " + capacity);

        // start migration background threads
        migrator = new PMemMigrator(migrateThreads, capacity, threshold);
        migrator.start();
    }

    public static void stop() throws InterruptedException {
        if (migrator != null) {
            migrator.stop();
        }
    }

    public static MixChannel open(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        MixChannel ch = null;
        synchronized (GLOBAL_LOCK) {
            ch = new MixChannel(file, initFileSize, preallocate, mutable);
        }
        migrator.add(ch);
        return ch;
    }

    public MixChannel(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        this.path = file;
        this.relativePath = Paths.get(file.getParent().getFileName().toString(), file.getFileName().toString()).toString();
        String[] toks = this.relativePath.split("/");
        if (toks.length != 2) {
            throw new RuntimeException(this.relativePath + " not in the format of topic/id.log");
        }
        this.namespace = toks[0];
        this.id = Long.parseLong(toks[1].split("\\.")[0]);

        this.channels = new FileChannel[Mode.LEN];

        int modeValue = metaStore.getInt(relativePath);
        if (modeValue != MetaStore.NOT_EXIST_INT) {
            this.mode = Mode.fromInteger(modeValue);
            switch (mode) {
                case PMEM:
                    this.channels[Mode.PMEM.value] = PMemChannel.open(file, initFileSize, preallocate, mutable);
                    break;
                case HDD:
                    this.channels[Mode.HDD.value] = openFileChannel(file, initFileSize, preallocate, mutable);
                    break;
                default:
                    log.error("Not support ChannelModel " + this.mode);
                    break;
            }
        } else {
            try {
                // TODO(zhanghao): support other default channel
                if (highUsed + initFileSize <= highCapacity) {
                    this.channels[Mode.PMEM.value] = PMemChannel.open(file, initFileSize, preallocate, mutable);
                    this.mode = Mode.PMEM;
                    highUsed += initFileSize;
                } else {
                    log.info(defaultMode + " used (" + (highUsed + initFileSize) + " Bytes) exceeds limit (" + highCapacity
                            + " Bytes). Using normal FileChannel instead.");
                    this.channels[Mode.HDD.value] = openFileChannel(file, initFileSize, preallocate, mutable);
                    this.mode = Mode.HDD;
                }
            } catch (Exception e) {
                log.info("Fail to allocate in " + defaultMode + " channel. Using normal FileChannel instead.", e);
                this.channels[Mode.HDD.value] = openFileChannel(file, initFileSize, preallocate, mutable);
                this.mode = Mode.HDD;
            }
            metaStore.putInt(relativePath, this.mode.value);
        }
        log.info("Create MixChannel " + this.mode + ", path = " + file + ", initSize = "
                + initFileSize + ", preallocate = " + preallocate + ", mutable = " + mutable);
    }

    public String getNamespace() {
        return this.namespace;
    }

    public long getId() {
        return this.id;
    }

    @Override
    public String toString() {
        return "MixChannel " + getNamespace() + "/" + getId();
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

    public Mode setMode(Mode m) throws IOException {
        // if no change, just return
        if (this.mode == m) {
            return m;
        }

        FileChannel newChannel = null;
        switch (m) {
            case PMEM:
                newChannel = PMemChannel.open(this.path, (int) this.size(), true, true);
                break;
            case HDD:
                newChannel = openFileChannel(this.path, (int) this.size(), true, true);
                break;
            default:
                log.error("Not support Mode: " + m);
        }

        FileChannel oldChannel = getChannel();
        Mode oldMode = getMode();
        long size = oldChannel.size();
        long transferred = 0;
        do {
            transferred += oldChannel.transferTo(transferred, size, newChannel);
        } while (transferred < size);

        this.channels[m.value] = newChannel;
        // until this point, the newChannel will take effect
        this.mode = m;
        metaStore.putInt(relativePath, this.mode.value);

        /**
         * FIXME(zhanghao): concurrency risk
         * the concurrency risk is the case there are other readers are reading the data
         * writers are not possible as we only change the mode for Channels that are stable
         */
        switch (oldMode) {
            case PMEM:
                ((PMemChannel) oldChannel).delete(false);
                break;
            case HDD:
                RandomAccessFile randomAccessFile = new RandomAccessFile(path.toFile(), "rw");
                randomAccessFile.setLength(0);
                break;
            default:
                log.error("Not support " + oldMode);
        }
        this.channels[oldMode.value] = null;
        synchronized (GLOBAL_LOCK) {
            if (oldMode.higherThan(this.mode)) {
                highUsed -= this.occupiedSize();
            }
        }
        return this.mode;
    }

    private FileChannel getChannel() {
        return this.channels[this.mode.value];
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        return getChannel().read(dst);
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
        return getChannel().read(dsts, offset, length);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        return getChannel().write(src);
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
        return getChannel().write(srcs, offset, length);
    }

    @Override
    public long position() throws IOException {
        return getChannel().position();
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        return getChannel().position(newPosition);
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
        return getChannel().truncate(size);
    }

    @Override
    public void force(boolean metaData) throws IOException {
        getChannel().force(metaData);
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        return getChannel().transferTo(position, count, target);
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        return getChannel().transferFrom(src, position, count);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        return getChannel().read(dst, position);
    }

    @Override
    public int write(ByteBuffer src, long position) throws IOException {
        return getChannel().write(src, position);
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
        for (int i = 0; i < Mode.LEN; i++) {
            FileChannel channel = this.channels[i];
            if (channel == null) {
                continue;
            }

            Mode cmode = Mode.fromInteger(i);
            if (cmode == Mode.HDD) {
                Files.deleteIfExists(path);
            } else if (cmode == Mode.PMEM) {
                ((PMemChannel) channel).delete();
            } else {
                log.error("Not support channel " + cmode);
            }
        }
    }

    @Override
    protected void implCloseChannel() throws IOException {
        for (FileChannel channel : this.channels) {
            if (channel != null) {
                channel.close();
            }
        }
    }

    static private FileChannel openFileChannel(Path file, long initFileSize, boolean preallocate, boolean mutable) throws IOException {
        if (mutable) {
            boolean fileAlreadyExists = file.toFile().exists();
            if (fileAlreadyExists || !preallocate) {
                return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
            } else {
                RandomAccessFile randomAccessFile = new RandomAccessFile(file.toString(), "rw");
                randomAccessFile.setLength(initFileSize);
                return randomAccessFile.getChannel();
            }
        } else {
            return FileChannel.open(file);
        }
    }
}
