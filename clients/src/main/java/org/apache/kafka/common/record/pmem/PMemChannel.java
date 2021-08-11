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
import com.intel.pmem.llpl.PersistentHeap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static int poolEntrySize = 0;
    private static int poolEntryCount = 0;
    private static int poolSize = 0;
    private static String pmemRootPath = null;
    private static PersistentHeap heap = null;
    private volatile static boolean inited = false;
    private static Pool<Pair<String, MemoryPool>> blockPool = new Pool<>();

    private static AtomicInteger counter = new AtomicInteger();
    private final static Object GLOBAL_LOCK = new Object();

    private static PMemMetaStore metaStore = null;
    private static PMemMigrator migrator = null;

    public final static String POOL_ENTRY_SIZE = "_pool_entry_size_";
    public final static String POOL_ENTRY_COUNT = "_pool_entry_count_";
    public final static String POOL_ENTRY_PREFIX = "_pool_";
    public final static String POOL_NOT_IN_USE = "_NOT_IN_USE_";
    public final static String POOL_ENTRY_USED = "_pool_entry_used_";
    public final static String POOL_INITED = "_inited_";

    public static void init(String path, long poolSize, int poolEntrySize) {
        pmemRootPath = path;
        File file = new File(pmemRootPath);
        boolean isDir = file.exists() && file.isDirectory();
        String metaPath = null;
        if (isDir) {
            metaPath =  pmemRootPath + "/heap.meta";
        } else {  // poolset file
            log.warn("pmemRootPath is a poolset file: " + pmemRootPath);
            metaPath =  getParentDir(getFirstDir(pmemRootPath)) + "/heap.meta";
        }

        // entry number will be #poolBlock + #dynamicBlock (we assume its max is 1000)
        long metaPoolSize = (poolEntryCount + 1000) * 1024L * 1024;
        metaStore = new PMemMetaStore(metaPath, metaPoolSize);

        String poolInited = metaStore.get(POOL_INITED);
        if (poolInited != null && !poolInited.isEmpty()) {
            log.info("PMem Pool already initted.");
        } else {
            int poolEntryCount = (int) (poolSize / poolEntrySize);
            log.info("Init heapPool: size = " + poolSize + ", poolEntryCount = " + poolEntryCount + ", poolEntry size = " + poolEntrySize);
            metaStore.putInt(POOL_ENTRY_SIZE, poolEntrySize);
            metaStore.putInt(POOL_ENTRY_COUNT, poolEntryCount);

            if (poolEntryCount > 0) {
                for (int i = 0; i < poolEntryCount; i++) {
                    MemoryPool pool = null;
                    String relativePoolPath = POOL_ENTRY_PREFIX + i;
                    String poolPath = Join(pmemRootPath, relativePoolPath);
                    if (MemoryPool.exists(poolPath)) {
                        pool = MemoryPool.openPool(poolPath);
                        log.warn(poolPath + " already exists");
                    } else {
                        pool = MemoryPool.createPool(poolPath, poolEntrySize);
                    }
                    // memset pool
                    pool.setMemoryNT((byte)0, 0, poolEntrySize);
                    metaStore.put(relativePoolPath, POOL_NOT_IN_USE);
                    log.info("init pool entry " + i);
                }
            }

            // init pool used to 0
            metaStore.putInt(POOL_ENTRY_USED, 0);
            metaStore.put(POOL_INITED, POOL_INITED);
            log.info("init heap " + pmemRootPath + " done");
        }

        if (!inited) {
            inited = true;
            poolEntrySize = metaStore.getInt(POOL_ENTRY_SIZE);
            if (poolEntrySize == MetaStore.NOT_EXIST_INT) {
                poolEntrySize = 0;
                log.error("PMem heap is not inited (poolEntrySize is not set)");
            }

            poolEntryCount = metaStore.getInt(POOL_ENTRY_COUNT);
            if (poolEntryCount == MetaStore.NOT_EXIST_INT) {
                poolEntryCount = 0;
                log.error("PMem heap poolEntryCount is not set");
            }

            int used = metaStore.getInt(POOL_ENTRY_USED);
            if (used == MetaStore.NOT_EXIST_INT) {
                used = 0;
            }
            counter.set(used);

            for (int i = 0; i < poolEntryCount; i++) {
                String relativePoolPath = POOL_ENTRY_PREFIX + i;
                String idPath = metaStore.get(relativePoolPath);
                if (idPath.compareTo(POOL_NOT_IN_USE) == 0) {
                    MemoryPool pool = MemoryPool.openPool(Join(pmemRootPath, relativePoolPath));
                    blockPool.push(new Pair<>(relativePoolPath, pool));
                } else {
                    log.info("Pool entry " + i + " in use");
                }
            }
            log.info("init PMem pool with poolEntryCount = " + poolEntryCount + ", used = " + counter.get());
        } else {
            log.error("pool already inited");
        }

        // start migration background threads
        migrator = new PMemMigrator(2);
        migrator.start();
    }

    public static void stop() throws InterruptedException {
        if (migrator != null) {
            migrator.stop();
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
            memoryPool = MemoryPool.openPool(Join(pmemRootPath, mpRelativePath));

            // load the buffer size
            channelSize = metaStore.getInt(sizeKey);
            if (channelSize == MetaStore.NOT_EXIST_INT) {
                channelSize = (int) memoryPool.size();
            }

            if (initSize != 0) {
                error("initSize not 0 for recovered channel. initSize = " + initSize + ", buf.size = " + memoryPool.size());
                truncate(initSize);
            }
            info("recover MemoryPool @ " + Join(pmemRootPath, mpRelativePath));
        } else {  // allocate new block
            if (initSize == 0) {
                error("PMemChannel initSize 0 (have to set log.preallocate=true)");
            }

            // TODO(zhanghao): what if initSize is 0
            if (poolEntryCount == 0 || initSize != poolEntrySize || counter.get() >= poolEntryCount) {
                File parentFile = new File(Join(pmemRootPath, relativePath)).getParentFile();
                parentFile.mkdirs();

                mpRelativePath = relativePath;
                String path = Join(pmemRootPath, mpRelativePath);
                memoryPool = MemoryPool.createPool(path, initSize);
                metaStore.put(relativePath, mpRelativePath);
                info("Dynamically allocate " + initSize + " @ " + path);
            } else {
                int usedCounter = counter.incrementAndGet();
                // TODO(zhanghao): what to do if POOL_ENTRY_USED not consistent
                metaStore.putInt(POOL_ENTRY_USED, usedCounter);
                Pair<String, MemoryPool> pair = blockPool.pop();
                this.memoryPool = pair.snd();
                this.mpRelativePath = pair.fst();
                if (memoryPool == null) {
                    String msg = "block pool inconsistent, usedCounter = "
                            + usedCounter + "， poolEntryCount = " + poolEntryCount
                            + ", poolSize = " + blockPool.size();
                    error(msg);
                    throw new IOException(msg);
                }
                metaStore.put(relativePath, mpRelativePath);
                metaStore.put(mpRelativePath, relativePath);
                info("allocate from existing pool @ " + mpRelativePath);
            }
            channelSize = initSize;
        }

        // create an empty log file as Kafka will check its existence
        if (!file.toFile().createNewFile()) {
            debug(file + " already exits");
        }
        info("Allocate PMemChannel with size " + channelSize);
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
        synchronized (GLOBAL_LOCK) {
            info("Before delete PMemChannel, channelSize = " + memoryPool.size() + ", poolEntryCount = " + blockPool.size() + ", usedCounter = " + counter.get());
            metaStore.del(relativePath);
            metaStore.removeInt(sizeKey);

            if (mpRelativePath.startsWith(POOL_ENTRY_PREFIX)) {
                // clear the pmem metadata
                metaStore.put(mpRelativePath, POOL_NOT_IN_USE);

                // push back to pool
                blockPool.push(new Pair<>(mpRelativePath, memoryPool));
                int usedCounter = counter.decrementAndGet();
                metaStore.putInt(POOL_ENTRY_USED, usedCounter);

                if (poolEntryCount - usedCounter != blockPool.size()) {
                    error("pool free size (" + blockPool.size() + ") != poolEntryCount - usedCounter (" + (poolEntryCount - usedCounter) + ")");
                }
            } else {
                Path p1 = Paths.get(pmemRootPath, mpRelativePath);
                try {
                    Files.deleteIfExists(p1);
                } catch (IOException e) {
                    error("delete file " + p1 + " error: " + e);
                }
                Path p2 = filePath;
                try {
                    Files.deleteIfExists(p2);
                } catch (IOException e) {
                    error("delete file " + p2 + " error: " + e);
                }
            }
            info("After delete PMemChannel, channelSize = " + memoryPool.size() +
                    ", poolEntryCount = " + blockPool.size() + ", usedCounter = " + counter.get());
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

    private MemoryPool memoryPool;
    private String mpRelativePath;
    private int channelSize;
    private int channelPosition = 0;
    private Path filePath;
    private String relativePath;
    private String sizeKey;
}
