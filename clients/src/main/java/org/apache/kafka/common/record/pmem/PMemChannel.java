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

import com.intel.pmem.llpl.MemoryPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.abs;
import static java.lang.Math.min;

class Pool<T> {
    public void push(T item) {
        items.add(item);
    }

    public T pop() {
        if (items.size() > 0) {
            return items.remove();
        } else {
            return null;
        }
    }

    public int size() {
        return items.size();
    }

    private LinkedList<T> items = new LinkedList<>();
};

class Pair<T1, T2> {
    private T1 first;
    private T2 second;

    public Pair(T1 first, T2 second) {
        this.first = first;
        this.second = second;
    }

    public T1 fst() {
        return this.first;
    }

    public T2 snd() {
        return this.second;
    }
};

public class PMemChannel extends FileChannel {
    private static final Logger log = LoggerFactory.getLogger(PMemChannel.class);
    private static int poolEntrySizeG = 0;
    private static int poolEntryCountG = 0;
    private static long pSizeG = 0;
    private static String pmemRootPathG = null;
    private volatile static boolean initedG = false;
    private static Pool<Pair<String, MemoryPool>> blockPoolG = new Pool<>();

    private static AtomicInteger counterG = new AtomicInteger();
    private final static Object GLOBAL_LOCK = new Object();

    private static PMemMetaStore metaStore = null;

    public final static String POOL_ENTRY_SIZE = "_pool_entry_size_";
    public final static String POOL_ENTRY_COUNT = "_pool_entry_count_";
    public final static String POOL_ENTRY_PREFIX = "_pool_";
    public final static String POOL_NOT_IN_USE = "_NOT_IN_USE_";
    public final static String POOL_ENTRY_USED = "_pool_entry_used_";
    public final static String POOL_INITED = "_inited_";
    public final static String DELETED_FLAG = "_deleted_";

    public static void init(String path, long size, int blockSize, double poolRatio) {
        size = getCapacity(path, size);

        pmemRootPathG = path;
        pSizeG = size;
        File file = new File(pmemRootPathG);
        if (!file.exists()) {
            if (!file.mkdirs()) {
                log.error("Mkdir " + file + " failed");
                return;
            }
        }

        boolean isDir = file.isDirectory();
        String metaPath = null;
        if (isDir) {
            metaPath = pmemRootPathG + "/pool.meta";
        } else {  // poolset file
            log.warn("pmemRootPath is a poolset file: " + pmemRootPathG);
            metaPath = getParentDir(getFirstDir(pmemRootPathG)) + "/pool.meta";
        }

        // entry number will be #poolBlock + #dynamicBlock (we assume its max is 1000)
        long metaPoolSize = (poolEntryCountG + 1000) * 1024L * 1024;
        metaStore = new PMemMetaStore(metaPath, metaPoolSize);

        preallocateIfNeeded(path, size, blockSize, poolRatio);

        if (!initedG) {
            initedG = true;
            poolEntrySizeG = metaStore.getInt(POOL_ENTRY_SIZE);
            if (poolEntrySizeG == MetaStore.NOT_EXIST_INT) {
                poolEntrySizeG = 0;
                log.error("PMem pool is not inited (poolEntrySize is not set)");
            }

            poolEntryCountG = metaStore.getInt(POOL_ENTRY_COUNT);
            if (poolEntryCountG == MetaStore.NOT_EXIST_INT) {
                poolEntryCountG = 0;
                log.error("PMem poolEntryCount is not set");
            }

            int used = metaStore.getInt(POOL_ENTRY_USED);
            if (used == MetaStore.NOT_EXIST_INT) {
                used = 0;
            }
            counterG.set(used);

            for (int i = 0; i < poolEntryCountG; i++) {
                String relativePoolPath = POOL_ENTRY_PREFIX + i;
                String idPath = metaStore.get(relativePoolPath);

                if (idPath.compareTo(POOL_NOT_IN_USE) == 0) {
                    MemoryPool pool = MemoryPool.openPool(joinPath(pmemRootPathG, relativePoolPath));
                    blockPoolG.push(new Pair<>(relativePoolPath, pool));
                    log.info("Add pool entry " + i + " to blockPool");
                } else {
                    String storedPoolPath = metaStore.get(idPath);

                    if (storedPoolPath.compareTo(DELETED_FLAG) == 0) {
                        // remedy crash case PMemChannel#3
                        metaStore.del(idPath);
                        String sizeKey = idPath + "/size";
                        metaStore.del(sizeKey);
                        MemoryPool pool = MemoryPool.openPool(joinPath(pmemRootPathG, relativePoolPath));
                        blockPoolG.push(new Pair<>(relativePoolPath, pool));
                        log.info("Recovered pool entry " + i + " to blockPool");
                    } else if (storedPoolPath.compareTo(relativePoolPath) != 0) {
                        // remedy the crash case PMemChannel#1
                        log.error(storedPoolPath + ":" + relativePoolPath + " not consistent");
                        metaStore.put(idPath, relativePoolPath);
                        log.info("Recovered pool entry " + i + " used by " + idPath);
                    } else {
                        log.info("Pool entry " + i + " in use");
                    }
                }
            }

            // remedy the crash case PMemChannel#2
            if (poolEntryCountG - blockPoolG.size() != counterG.get()) {
                log.error("Stored used pool count not consistent with pool entry meta: " + counterG + " vs " + blockPoolG.size() +
                        ". Prioritize pool entry meta");
                counterG.set(poolEntryCountG - blockPoolG.size());
            }

            log.info("init PMem pool with poolEntryCount = " + poolEntryCountG + ", used = "
                    + counterG.get() + ", poolEntrySize = " + poolEntrySizeG);
        } else {
            log.warn("pool already inited");
        }
    }

    private static void preallocateIfNeeded(String path, long size, int blockSize, double poolRatio) {
        String poolInited = metaStore.get(POOL_INITED);
        if (poolInited != null && !poolInited.isEmpty()) {
            log.info("PMem Pool already inited.");
        } else {
            int poolEntryCount = (int) (size * poolRatio / blockSize);
            log.info("Init pool: size = " + size + ", poolEntryCount = " + poolEntryCount
                    + " (" + (poolRatio * 100) + "% of total size), poolEntry size = " + blockSize);
            metaStore.putInt(POOL_ENTRY_SIZE, blockSize);
            metaStore.putInt(POOL_ENTRY_COUNT, poolEntryCount);

            if (poolEntryCount > 0) {
                for (int i = 0; i < poolEntryCount; i++) {
                    MemoryPool pool = null;
                    String relativePoolPath = POOL_ENTRY_PREFIX + i;
                    String poolPath = joinPath(pmemRootPathG, relativePoolPath);
                    if (MemoryPool.exists(poolPath)) {
                        pool = MemoryPool.openPool(poolPath);
                        log.warn(poolPath + " already exists");
                    } else {
                        pool = MemoryPool.createPool(poolPath, blockSize);
                    }
                    // memset pool
                    pool.setMemoryNT((byte) 0, 0, blockSize);
                    metaStore.put(relativePoolPath, POOL_NOT_IN_USE);
                    log.info("init pool entry " + i);
                }
            }

            // init pool used to 0
            metaStore.putInt(POOL_ENTRY_USED, 0);
            metaStore.put(POOL_INITED, POOL_INITED);
            log.info("init pmem " + pmemRootPathG + " done");
        }
    }

    public static FileChannel open(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        log.info("Creating PMemChannel " + file.toString() + " with size " + initFileSize);
        synchronized (GLOBAL_LOCK) {
            FileChannel channel = new PMemChannel(file, initFileSize, preallocate);
            return channel;
        }
    }

    public static boolean exists(Path file) {
        String key = toRelativePath(file);
        String poolPath = metaStore.get(key);
        return poolPath != null && !poolPath.isEmpty();
    }

    private PMemChannel(Path file, int initSize, boolean preallocate) throws IOException {
        filePath = file;
        relativePath = toRelativePath(file);
        sizeKey = relativePath + "/size";

        mpRelativePath = metaStore.get(relativePath);
        // already allocate, recover
        if (mpRelativePath != null && !mpRelativePath.isEmpty()) {
            memoryPool = MemoryPool.openPool(joinPath(pmemRootPathG, mpRelativePath));

            // load the buffer size
            channelSize = metaStore.getInt(sizeKey);
            if (channelSize == MetaStore.NOT_EXIST_INT) {
                channelSize = (int) memoryPool.size();
            }

            if (initSize != 0) {
                error("initSize not 0 for recovered channel. initSize = " + initSize + ", buf.size = " + memoryPool.size());
                truncate(initSize);
            }
            info("recover MemoryPool (size: " + channelSize + ") @ " + joinPath(pmemRootPathG, mpRelativePath));
        } else {  // allocate new block
            if (initSize == 0) {
                String msg = "PMemChannel initSize 0 (have to set log.preallocate=true)";
                error(msg);
                throw new RuntimeException(msg);
            }

            info("poolEntryCount = " + poolEntryCountG + ", initSize = " + initSize + ", poolEntrySize = " + poolEntrySizeG
                    + ", counter = " + counterG.get() + ", poolEntryCount = " + poolEntryCountG);
            if (poolEntryCountG == 0 || !similarSize(initSize, poolEntrySizeG) || counterG.get() >= poolEntryCountG) {
                File parentFile = new File(joinPath(pmemRootPathG, relativePath)).getParentFile();
                if (!parentFile.exists()) {
                    if (!parentFile.mkdirs()) {
                        error("Create directory " + parentFile + " failed");
                    }
                }

                mpRelativePath = relativePath;
                String path = joinPath(pmemRootPathG, mpRelativePath);
                File mpFile = new File(path);
                // This may be true if
                // 1) program crash immediately after we createPool, but before we put the info to metaStore
                // 2) storage.pmem.path == log.dirs
                if (mpFile.exists()) {
                    warn(mpFile + " already exists. Delete it first ");
                    if (!mpFile.delete()) {
                        warn(mpFile + " delete failed");
                    }
                }
                memoryPool = MemoryPool.createPool(path, initSize);
                metaStore.put(relativePath, mpRelativePath);
                info("Dynamically allocate " + initSize + " @ " + path);
            } else {
                int usedCounter = counterG.incrementAndGet();
                // crash case PMemChannel#2
                metaStore.putInt(POOL_ENTRY_USED, usedCounter);
                Pair<String, MemoryPool> pair = blockPoolG.pop();
                this.memoryPool = pair.snd();
                this.mpRelativePath = pair.fst();
                if (memoryPool == null) {
                    String msg = "block pool inconsistent, usedCounter = "
                            + usedCounter + "， poolEntryCount = " + poolEntryCountG
                            + ", poolSize = " + blockPoolG.size();
                    error(msg);
                    throw new IOException(msg);
                }

                /*
                 * crash case PMemChannel#1
                 * the following two operations can be remedied if the first succeed but the second failed
                 */
                metaStore.put(mpRelativePath, relativePath);
                metaStore.put(relativePath, mpRelativePath);
                info("allocate from existing pool @ " + mpRelativePath);
            }
            channelSize = initSize;
        }

        // create an empty log file as Kafka will check its existence
        if (!file.toFile().createNewFile()) {
            debug(file + " already exits");
        }
    }

    @Override
    public int read(ByteBuffer dst) throws IOException {
        int ret = read(dst, channelPosition);
        synchronized (rwLock) {
            channelPosition += ret;
        }
        return ret;
    }

    @Override
    public long read(ByteBuffer[] dsts, int offset, int length) throws UnsupportedOperationException {
        String msg = "read(ByteBuffer[] dsts, int offset, int length) not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int writeSize = src.remaining();
        if (writeSize <= 0) {
            return writeSize;
        }

        synchronized (rwLock) {
            int requiredSize = writeSize + channelPosition;
            if (requiredSize > channelSize) {
                if (requiredSize <= memoryPool.size()) {
                    channelSize = (int) memoryPool.size();
                } else {
                    // This condition shouldn't happen as segment size is fixed and should not exceed the configured segment size
                    error("requiredSize " + requiredSize + " > buf limit " + memoryPool.size());
                    return 0;
                }
            }

            debug("write " + writeSize + " to buf from position " + channelPosition + ", size = " + size() + ", src.limit() = "
                    + src.limit() + ", src.position = " + src.position() + ", src.capacity() = " + src.capacity()
                    + ", src.arrayOffset() = " + src.arrayOffset());
            memoryPool.copyFromByteArrayNT(src.array(), src.arrayOffset() + src.position(), channelPosition, writeSize);
            // _buf.flush(_position, writeSize);
            src.position(src.position() + writeSize);
            channelPosition += writeSize;
        }
        debug("After write, final position = " + channelPosition);
        return writeSize;
    }

    @Override
    public long write(ByteBuffer[] srcs, int offset, int length) throws UnsupportedOperationException {
        String msg = "write(ByteBuffer[] srcs, int offset, int length) not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    public long position() throws IOException {
        debug("position = " + channelPosition);
        return channelPosition;
    }

    @Override
    public FileChannel position(long newPosition) throws IOException {
        debug("new position = " + newPosition + ", old position = " + channelPosition);
        synchronized (rwLock) {
            channelPosition = (int) newPosition;
        }
        return this;
    }

    @Override
    public long size() throws IOException {
        return channelSize;
    }

    public long occupiedSize() {
        return memoryPool.size();
    }

    @Override
    public FileChannel truncate(long size) throws IOException {
        info("PMemChannel truncate from " + this.channelSize + " to " + size);
        synchronized (rwLock) {
            if (size <= memoryPool.size()) {
                this.channelSize = (int) size;
                position(min(position(), this.channelSize));
            } else {
                String msg = "PMemChannel does not support truncate to larger size";
                error(msg);
                throw new IOException(msg);
            }
        }

        synchronized (GLOBAL_LOCK) {
            metaStore.putInt(sizeKey, this.channelSize);
        }
        return this;
    }

    @Override
    public void force(boolean metaData) {
        // no need to call flush as pmem_memcpy already do flush for us
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        int n = 0;
        ByteBuffer transferBuf = null;
        synchronized (rwLock) {
            int transferSize = min(channelSize - (int) position, (int) count);
            debug("transferTo @" + position + " with length " + count + " (required) :" + transferSize + " (available)");

            if (transferSize > 0) {
                try {
                    transferBuf = memoryPool.asByteBuffer(position, transferSize);
                } catch (IllegalAccessException e) {
                    log.error(concatPath("asByteBuffer error: "), e);
                }
                while (n < transferSize) {
                    n += target.write(transferBuf);
                }
            }
        }
        debug("write " + n + " bytes");
        transferBuf = null;
        return n;
    }

    @Override
    public long transferFrom(ReadableByteChannel src, long position, long count) throws UnsupportedOperationException {
        String msg = "transferFrom not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    public int read(ByteBuffer dst, long position) throws IOException {
        debug("dst.remaining() = " + dst.remaining() + ", size = " + channelSize + ", position = " + position + ", curPos = " + this.channelPosition);
        int readSize = 0;
        synchronized (rwLock) {
            readSize = min(channelSize - (int) position, dst.remaining());
            if (readSize <= 0) {
                return -1;
            }

            memoryPool.copyToByteArray(position, dst.array(), dst.arrayOffset() + dst.position(), readSize);
            dst.position(dst.position() + readSize);
        }
        debug("read " + readSize + " from position " + position);
        return readSize;
    }

    @Override
    public int write(ByteBuffer src, long position) throws UnsupportedOperationException {
        String msg = "write(ByteBuffer src, long position) not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    public MappedByteBuffer map(MapMode mode, long position, long size) throws UnsupportedOperationException {
        String msg = "map not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    public FileLock lock(long position, long size, boolean shared) throws UnsupportedOperationException {
        String msg = "lock not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    public FileLock tryLock(long position, long size, boolean shared) throws UnsupportedOperationException {
        String msg = "tryLock not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
    }

    @Override
    protected void implCloseChannel() throws IOException {
        info("PMemChannel closed");
    }

    public void delete() {
        delete(true);
    }

    public void delete(boolean deleteOrigFile) {
        boolean inPool = mpRelativePath.startsWith(POOL_ENTRY_PREFIX);
        int usedCounter = counterG.get();
        info("Before delete PMemChannel, channelSize = " + memoryPool.size() + ", free poolEntryCount = "
                + blockPoolG.size() + ", usedCounter = " + usedCounter);

        synchronized (GLOBAL_LOCK) {
            // crash case PMemChannel#3
            // if we call metaStore.del(relativePath) directly
            // after which, program crash, we may lose one PMemChannel
            // so we put a DELETE_FLAG first so that we can recover if this crash case happen
            metaStore.put(relativePath, DELETED_FLAG);

            if (inPool) {
                // clear the pmem metadata
                metaStore.put(mpRelativePath, POOL_NOT_IN_USE);

                // push back to pool
                blockPoolG.push(new Pair<>(mpRelativePath, memoryPool));
                usedCounter = counterG.decrementAndGet();

                metaStore.putInt(POOL_ENTRY_USED, usedCounter);

                if (poolEntryCountG - usedCounter != blockPoolG.size()) {
                    error("pool free size (" + blockPoolG.size() + ") != poolEntryCount - usedCounter ("
                            + (poolEntryCountG - usedCounter) + ")");
                }
            }
            metaStore.del(relativePath);
            metaStore.removeInt(sizeKey);
            info("Return " + mpRelativePath + " back to the pool");
        }
        info("After delete PMemChannel, channelSize = " + memoryPool.size() +
                ", free poolEntryCount = " + blockPoolG.size() + ", usedCounter = " + usedCounter);
        memoryPool = null;

        if (!inPool) {
            Path p1 = Paths.get(pmemRootPathG, mpRelativePath);
            // delete pmem file only if pmem file does not equal filePath
            if (p1.compareTo(filePath) != 0) {
                info("Delete pmem file " + p1.toString());
                try {
                    Files.deleteIfExists(p1);
                } catch (IOException e) {
                    error("delete file " + p1 + " error: ", e);
                }
            }

            if (deleteOrigFile) {
                Path p2 = filePath;
                info("Delete id file " + p2.toString());
                try {
                    Files.deleteIfExists(p2);
                } catch (IOException e) {
                    error("delete file " + p2 + " error: ", e);
                }
            }
        }
    }

    private String concatPath(String str) {
        return "[" + relativePath + "]: " + str;
    }

    private void info(String str) {
        log.info(concatPath(str));
    }

    private void warn(String str) {
        log.warn(concatPath(str));
    }

    private void debug(String str) {
        log.debug(concatPath(str));
    }

    private void error(String str) {
        log.error(concatPath(str));
    }

    private void error(String str, Exception e) {
        log.error(concatPath(str), e);
    }

    /**
     * Return:
     *  the first poolset directory in poolset file
     *
     * a poolset is in this format:
     * PMEMPOOLSET
     * OPTION SINGLEHDR
     * 64G /mnt/pmem0/poolset/
     * 64G /mnt/pmem1/poolset/
     */
    private static String getFirstDir(String poolset) {
        String pmemPath = poolset;
        BufferedReader br = null;
        try {
            InputStream inputStream = new FileInputStream(poolset);
            Reader fileReader = new InputStreamReader(inputStream, "UTF-8");
            br = new BufferedReader(fileReader);
            String line = br.readLine();
            while (line != null) {
                if (line.contains("/")) {
                    pmemPath = line.split(" ")[1];
                    break;
                }
                line = br.readLine();
            }
        } catch (Exception e) {
            log.error("Read file " + poolset + " failed: ", e);
        } finally {
            try {
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                log.error("Close file " + poolset + " failed: ", e);
            }
        }

        return pmemPath;
    }

    private static String getParentDir(String path) {
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int i = path.lastIndexOf(File.separator);
        return path.substring(0, i);
    }

    private static String joinPath(String path1, String path2) {
        return Paths.get(path1, path2).toString();
    }

    private boolean similarSize(long size1, long size2) {
        return abs(size1 - size2) < 10L * 1024 * 1024;
    }

    static String toRelativePath(Path file) {
        Path parent = file.getParent();
        parent = parent == null ? null : parent.getFileName();
        String parentStr = parent == null ? "" : parent.toString();
        Path fileName = file.getFileName();
        String fileNameStr = fileName == null ? "" : fileName.toString();
        return joinPath(parentStr, fileNameStr);
    }

    static long getCapacity(String path, long size) {
        // use all the storage space if capacity is configured to -1
        if (size == -1) {
            log.info("PMem size is set to total capacity of device: " + size);
            File file = new File(path);
            size = file.getTotalSpace();
        }

        return size;
    }

    private MemoryPool memoryPool;
    private String mpRelativePath;
    private int channelSize;
    private int channelPosition = 0;
    private Path filePath;
    private String relativePath;
    private String sizeKey;
    private final Object rwLock = new Object();
}
