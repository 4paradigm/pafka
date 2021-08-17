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
import java.io.FileReader;
import java.io.IOException;
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
    private static int poolSizeG = 0;
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

    public static void init(String path, long size, int blockSize, double poolRatio) {
        pmemRootPathG = path;
        File file = new File(pmemRootPathG);
        boolean isDir = file.exists() && file.isDirectory();
        String metaPath = null;
        if (isDir) {
            metaPath =  pmemRootPathG + "/pool.meta";
        } else {  // poolset file
            log.warn("pmemRootPath is a poolset file: " + pmemRootPathG);
            metaPath =  getParentDir(getFirstDir(pmemRootPathG)) + "/pool.meta";
        }

        // entry number will be #poolBlock + #dynamicBlock (we assume its max is 1000)
        long metaPoolSize = (poolEntryCountG + 1000) * 1024L * 1024;
        metaStore = new PMemMetaStore(metaPath, metaPoolSize);

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
                    String poolPath = Join(pmemRootPathG, relativePoolPath);
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
                    MemoryPool pool = MemoryPool.openPool(Join(pmemRootPathG, relativePoolPath));
                    blockPoolG.push(new Pair<>(relativePoolPath, pool));
                } else {
                    log.info("Pool entry " + i + " in use");
                }
            }
            log.info("init PMem pool with poolEntryCount = " + poolEntryCountG + ", used = "
                    + counterG.get() + ", poolEntrySize = " + poolEntrySizeG);
        } else {
            log.error("pool already inited");
        }
    }

    public static FileChannel open(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        synchronized (GLOBAL_LOCK) {
            log.info("open PMemChannel " + file.toString() + " with size " + initFileSize);
            FileChannel channel = new PMemChannel(file, initFileSize, preallocate);
            return channel;
        }
    }

    public PMemChannel(Path file, int initSize, boolean preallocate) throws IOException {
        filePath = file;
        relativePath = Join(file.getParent().getFileName().toString(), file.getFileName().toString());
        sizeKey = relativePath + "/size";

        mpRelativePath = metaStore.get(relativePath);
        // already allocate, recover
        if (mpRelativePath != null && !mpRelativePath.isEmpty()) {
            memoryPool = MemoryPool.openPool(Join(pmemRootPathG, mpRelativePath));

            // load the buffer size
            channelSize = metaStore.getInt(sizeKey);
            if (channelSize == MetaStore.NOT_EXIST_INT) {
                channelSize = (int) memoryPool.size();
            }

            if (initSize != 0) {
                error("initSize not 0 for recovered channel. initSize = " + initSize + ", buf.size = " + memoryPool.size());
                truncate(initSize);
            }
            info("recover MemoryPool (size: " + channelSize + ") @ " + Join(pmemRootPathG, mpRelativePath));
        } else {  // allocate new block
            if (initSize == 0) {
                error("PMemChannel initSize 0 (have to set log.preallocate=true)");
            }

            // TODO(zhanghao): what if initSize is 0
            info("poolEntryCount = " + poolEntryCountG + ", initSize = " + initSize + ", poolEntrySize = " + poolEntrySizeG
                    + ", counter = " + counterG.get() + ", poolEntryCount = " + poolEntryCountG);
            if (poolEntryCountG == 0 || !similarSize(initSize, poolEntrySizeG) || counterG.get() >= poolEntryCountG) {
                File parentFile = new File(Join(pmemRootPathG, relativePath)).getParentFile();
                parentFile.mkdirs();

                mpRelativePath = relativePath;
                String path = Join(pmemRootPathG, mpRelativePath);
                memoryPool = MemoryPool.createPool(path, initSize);
                metaStore.put(relativePath, mpRelativePath);
                info("Dynamically allocate " + initSize + " @ " + path);
            } else {
                int usedCounter = counterG.incrementAndGet();
                // TODO(zhanghao): what to do if POOL_ENTRY_USED not consistent
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
                metaStore.put(relativePath, mpRelativePath);
                metaStore.put(mpRelativePath, relativePath);
                info("allocate from existing pool @ " + mpRelativePath);
            }
            channelSize = initSize;
            info("Allocate PMemChannel with size " + channelSize);
        }

        // create an empty log file as Kafka will check its existence
        if (!file.toFile().createNewFile()) {
            debug(file + " already exits");
        }
    }

    @Override
    public int read(ByteBuffer dst) throws UnsupportedOperationException {
        String msg = "read(ByteBuffer dst) not implemented";
        error(msg);
        throw new UnsupportedOperationException(msg);
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

        int requiredSize = writeSize + channelPosition;

        // TODO (zhanghao): re-allocate
        if (requiredSize > channelSize) {
            if (requiredSize <= memoryPool.size()) {
                channelSize = (int) memoryPool.size();
            } else {
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
        channelPosition = (int) newPosition;
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
        if (size <= memoryPool.size()) {
            this.channelSize = (int) size;
            position(min(position(), this.channelSize));

            synchronized (GLOBAL_LOCK) {
                metaStore.putInt(sizeKey, this.channelSize);
            }
            return this;
        } else {
            String msg = "PMemChannel does not support truncate to larger size";
            error(msg);
            throw new IOException(msg);
        }
    }

    @Override
    public void force(boolean metaData) {
        // PersistentMemoryBlock do the sync automatically
    }

    @Override
    public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
        int transferSize = min(channelSize - (int) position, (int) count);
        debug("transferTo @" + position + " with length " + count + ":" + transferSize);
        ByteBuffer transferBuf = memoryPool.asByteBuffer(position, (int) count);
        int n = 0;
        while (n < transferSize) {
            n += target.write(transferBuf);
        }
        debug("write " + n + " bytes");
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
        int readSize = min(channelSize - (int) position, dst.remaining());
        if (readSize <= 0)  {
            return -1;
        }

        memoryPool.copyToByteArray(position, dst.array(), dst.arrayOffset() + dst.position(), readSize);
        dst.position(dst.position() + readSize);
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
        synchronized (GLOBAL_LOCK) {
            info("Before delete PMemChannel, channelSize = " + memoryPool.size() + ", poolEntryCount = " + blockPoolG.size() + ", usedCounter = " + counterG.get());
            metaStore.del(relativePath);
            metaStore.removeInt(sizeKey);

            if (mpRelativePath.startsWith(POOL_ENTRY_PREFIX)) {
                // clear the pmem metadata
                metaStore.put(mpRelativePath, POOL_NOT_IN_USE);

                // push back to pool
                blockPoolG.push(new Pair<>(mpRelativePath, memoryPool));
                int usedCounter = counterG.decrementAndGet();
                metaStore.putInt(POOL_ENTRY_USED, usedCounter);

                if (poolEntryCountG - usedCounter != blockPoolG.size()) {
                    error("pool free size (" + blockPoolG.size() + ") != poolEntryCount - usedCounter (" + (poolEntryCountG - usedCounter) + ")");
                }
            } else {
                Path p1 = Paths.get(pmemRootPathG, mpRelativePath);
                info("Delete file " + p1.toString());
                try {
                    Files.deleteIfExists(p1);
                } catch (IOException e) {
                    error("delete file " + p1 + " error: " + e);
                }
                if (deleteOrigFile) {
                    Path p2 = filePath;
                    info("Delete file " + p2.toString());
                    try {
                        Files.deleteIfExists(p2);
                    } catch (IOException e) {
                        error("delete file " + p2 + " error: " + e);
                    }
                }
            }
            info("After delete PMemChannel, channelSize = " + memoryPool.size() +
                    ", poolEntryCount = " + blockPoolG.size() + ", usedCounter = " + counterG.get());
            memoryPool = null;
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
            br = new BufferedReader(new FileReader(poolset));
            String line = br.readLine();
            while (line != null) {
                if (line.contains("/")) {
                    pmemPath = line.split(" ")[1];
                    break;
                }
                line = br.readLine();
            }
        } catch (Exception e) {
            log.error("Read file " + poolset + " failed: " + e);
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                log.error("Close file " + poolset + " failed: " + e);
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

    private static String Join(String path1, String path2) {
        return Paths.get(path1, path2).toString();
    }

    private boolean similarSize(long size1, long size2) {
        if (abs(size1 - size2) < 10L * 1024 * 1024) {
            return true;
        } else {
            return false;
        }
    }

    private MemoryPool memoryPool;
    private String mpRelativePath;
    private int channelSize;
    private int channelPosition = 0;
    private Path filePath;
    private String relativePath;
    private String sizeKey;
}
