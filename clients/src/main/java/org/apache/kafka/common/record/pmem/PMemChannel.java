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

import com.intel.pmem.llpl.AnyHeap;
import com.intel.pmem.llpl.Heap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStreamWriter;
import java.io.File;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.LinkedList;
import static java.lang.Math.min;
import java.nio.charset.StandardCharsets;
import java.io.RandomAccessFile;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.IOException;

import lib.util.persistent.ObjectDirectory;
import lib.util.persistent.PersistentInteger;
import lib.util.persistent.PersistentString;
import lib.util.persistent.PersistentLong;
import com.intel.pmem.llpl.PersistentHeap;
import com.intel.pmem.llpl.HeapException;
import com.intel.pmem.llpl.PersistentMemoryBlock;

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

class MetaStore {
    public final static String NOT_EXIST_STR = "__not_exists__";
    public final static int NOT_EXIST_INT = Integer.MIN_VALUE;
    public final static long NOT_EXIST_LONG = Long.MIN_VALUE;

    public MetaStore(String path, long size) {
        // TODO(zhanghao): config.properties is required by pmdk pcj. For now we generate dynamically here
        try {
            BufferedWriter metaConfig = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream("config.properties"), StandardCharsets.UTF_8));
            String metaConfigContent = "path=" + path + "\n" + "size=" + size + "\n";
            metaConfig.write(metaConfigContent);
            metaConfig.flush();
            metaConfig.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void putInt(String key, int value) {
        ObjectDirectory.put(key, new PersistentInteger(value));
    }

    public void putLong(String key, long value) {
        ObjectDirectory.put(key, new PersistentLong(value));
    }

    public void putString(String key, String value) {
        ObjectDirectory.put(key, new PersistentString(value));
    }

    public int getInt(String key) {
        PersistentInteger value = ObjectDirectory.get(key, PersistentInteger.class);
        if (value == null) {
            return NOT_EXIST_INT;
        } else {
            return value.intValue();
        }
    }

    public long getLong(String key) {
        PersistentLong value = ObjectDirectory.get(key, PersistentLong.class);
        if (value == null) {
            return NOT_EXIST_LONG;
        } else {
            return value.longValue();
        }
    }

    public String getString(String key) {
        PersistentString value = ObjectDirectory.get(key, PersistentString.class);
        if (value == null) {
            return NOT_EXIST_STR;
        } else {
            return value.toString();
        }
    }

    public void removeInt(String key) {
        ObjectDirectory.remove(key, PersistentInteger.class);
    }

    public void removeLong(String key) {
        ObjectDirectory.remove(key, PersistentLong.class);
    }

    public void removeString(String key) {
        ObjectDirectory.remove(key, PersistentString.class);
    }
};

public class PMemChannel extends FileChannel {
    enum Mode {
        PMEM,
        FILE
    }

    private static final Logger log = LoggerFactory.getLogger(PMemChannel.class);
    private static int poolEntrySize = 0;
    private static int poolEntryCount = 0;
    private static int poolSize = 0;
    private static PersistentHeap heap = null;
    private volatile static boolean inited = false;
    private static Pool<PersistentMemoryBlock> blockPool = new Pool<>();

    private static AtomicInteger counter = new AtomicInteger();
    private final static Object GLOBAL_LOCK = new Object();

    private static MetaStore metaStore = null;
    private static PMemMigrator migrator = null;

    public final static String HEAP_PATH = "_heap_path";
    public final static String POOL_ENTRY_SIZE = "_pool_entry_size";
    public final static String POOL_ENTRY_COUNT = "_pool_entry_count";
    public final static String POOL_ENTRY_PREFIX = "_pool_";
    public final static String POOL_ENTRY_USED = "_pool_entry_used";
    public final static String POOL_HANDLE_PREFIX = "_pool_handle_";

    // instance members
    private Mode mode = Mode.PMEM;

    public static void initHeap(String heapPath, long poolSize, int poolEntrySize) {
        File file = new File(heapPath);
        boolean isDir = file.exists() && file.isDirectory();
        String metaPath = null;
        if (isDir) {
            metaPath =  heapPath + "/heap.meta";
        } else {  // poolset file
            metaPath =  getParentDir(getFirstDir(heapPath)) + "/heap.meta";
        }

        // entry number will be #poolBlock + #dynamicBlock (we assume its max is 1000)
        long metaPoolSize = (poolEntryCount + 1000) * 1024L * 1024;
        metaStore = new MetaStore(metaPath, metaPoolSize);

        boolean heapExists = false;
        try {
            if (isDir) {
                // if it is a directory, llpl will auto create a poolset file
                heap = PersistentHeap.createHeap(heapPath);
            } else { // poolset file
                heap = PersistentHeap.createHeap(heapPath, 0);
            }
        } catch (Exception e) {
            log.debug("Create heap exception: " + e);
            log.debug("Try to open it");
            heap = PersistentHeap.openHeap(heapPath);
            heapExists = true;
        }

        if (heapExists) {
            log.info("PMem heap " + heapPath + " already exists. No need to re-init");
        } else {
            int poolEntryCount = (int) (poolSize / poolEntrySize);
            log.info("Init heapPool: size = " + poolSize + ", poolEntryCount = " + poolEntryCount + ", poolEntry size = " + poolEntrySize);
            metaStore.putString(HEAP_PATH, heapPath);
            metaStore.putInt(POOL_ENTRY_SIZE, poolEntrySize);
            metaStore.putInt(POOL_ENTRY_COUNT, poolEntryCount);

            if (poolEntryCount > 0) {
                for (int i = 0; i < poolEntryCount; i++) {
                    PersistentMemoryBlock block = heap.allocateMemoryBlock(poolEntrySize);
                    // set data to 0
                    // block.setMemory(0, 0, poolEntrySize)
                    metaStore.putLong(POOL_ENTRY_PREFIX + i, block.handle());
                    log.info("init pool entry " + i);
                }
            }

            // init pool used to 0
            metaStore.putInt(POOL_ENTRY_USED, 0);
            log.info("init heap " + heapPath + " done");
        }

        // start migration background threads
        migrator = new PMemMigrator(2);
        migrator.start();
    }

    public static void closeHeap() throws InterruptedException {
        if (heap != null) {
            heap.close();
        }
        if (migrator != null) {
            migrator.stop();
        }
    }

    public static FileChannel open(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        synchronized (GLOBAL_LOCK) {
            log.info("open PMemChannel " + file.toString());

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
                    long handle = metaStore.getLong(POOL_ENTRY_PREFIX + i);
                    String fileName = metaStore.getString(POOL_HANDLE_PREFIX + handle);
                    if (fileName.compareTo(MetaStore.NOT_EXIST_STR) == 0) {
                        PersistentMemoryBlock block = heap.memoryBlockFromHandle(handle);
                        blockPool.push(block);
                    } else {
                        log.error("Pool entry " + i + " not exists in metaStore");
                    }
                }
                log.info("open heapPool with poolEntryCount = " + poolEntryCount + ", used = " + counter.get());
            }

            FileChannel channel = null;
            try {
                channel = new PMemChannel(file, initFileSize, preallocate);
            } catch (HeapException e) {
                log.info("Fail to allocate in PMem channel. Using normal Filechannel instead.");
                if (mutable) {
                    RandomAccessFile randomAccessFile = new RandomAccessFile(file.toString(), "rw");
                    channel = randomAccessFile.getChannel();
                } else {
                    channel = FileChannel.open(file);
                }
                long handle =  metaStore.getLong(file.toString());
                if (handle == MetaStore.NOT_EXIST_LONG) {
                    metaStore.putLong(file.toString(), -1);
                } else {
                    if (handle != -1) {
                        log.error("Encounter wrong handle value.");
                        metaStore.putLong(file.toString(), -1);
                    }
                }
            } catch (IOException e) {
                log.error("Create PMemChannel exception: ", e);
            }
            return channel;
        }
    }

    public PMemChannel(Path file, int initSize, boolean preallocate) throws IOException {
        filePath = file;
        sizeKey = filePath.toString() + "/size";

        long handle = metaStore.getLong(file.toString());
        // already allocate, recover
        if (handle != MetaStore.NOT_EXIST_LONG) {
            if (handle < 0) {
                HeapException e = new HeapException("null");
                throw e;
            }
            pBlock = heap.memoryBlockFromHandle(handle);

            if (initSize != 0) {
                error("initSize not 0 for recovered channel. initSize = " + initSize + ", buf.size = " + pBlock.size());
                channelSize = initSize;
                metaStore.putInt(sizeKey, channelSize);
            } else {
                // load the buffer size
                channelSize = metaStore.getInt(sizeKey);
                if (channelSize == MetaStore.NOT_EXIST_INT) {
                    channelSize = (int) pBlock.size();
                }
            }

            info("recover block with handle " + handle);
        } else {  // allocate new block
            if (initSize == 0) {
                error("PMemChannel initSize 0 (have to set log.preallocate=true)");
            }

            // TODO(zhanghao): what if initSize is 0
            if (poolEntryCount == 0 || initSize != poolEntrySize || counter.get() >= poolEntryCount) {
                pBlock = heap.allocateMemoryBlock(initSize);
                metaStore.putLong(file.toString(), pBlock.handle());
                info("Dynamically allocate " + initSize + " with handle " + pBlock.handle());
            } else {
                int usedCounter = counter.incrementAndGet();
                metaStore.putInt(POOL_ENTRY_USED, usedCounter);
                pBlock = blockPool.pop();
                if (pBlock == null) {
                    String msg = "block pool inconsistent, usedCounter = "
                            + usedCounter + "， poolEntryCount = " + poolEntryCount
                            + ", poolSize = " + blockPool.size();
                    error(msg);
                    throw new IOException(msg);
                }
                metaStore.putLong(file.toString(), pBlock.handle());
                metaStore.putString(POOL_HANDLE_PREFIX + pBlock.handle(), file.toString());
                info("create new block " + file + " with handle " + pBlock.handle());
            }
            channelSize = initSize;
        }

        // create an empty log file as Kafka will check its existence
        if (!file.toFile().createNewFile()) {
            debug(file + " already exits");
        }
        info("Allocate PMemChannel with size " + channelSize);
    }

    public Mode getMode() {
        return this.mode;
    }

    public Mode setMode(Mode m) {
        this.mode = m;
        return this.mode;
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
            if (requiredSize <= pBlock.size()) {
                channelSize = (int) pBlock.size();
            } else {
                error("requiredSize " + requiredSize + " > buf limit " + pBlock.size());
                return 0;
            }
        }

        debug("write " + writeSize + " to buf from position " + channelPosition + ", size = " + size() + ", src.limit() = "
                + src.limit() + ", src.position = " + src.position() + ", src.capacity() = " + src.capacity()
                + ", src.arrayOffset() = " + src.arrayOffset());
        pBlock.copyFromArray(src.array(), src.arrayOffset() + src.position(), channelPosition, writeSize);
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
        if (size <= pBlock.size()) {
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
        ByteBuffer transferBuf = pBlock.asByteBuffer(position, (int) count);
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

        pBlock.copyToArray(position, dst.array(), dst.arrayOffset() + dst.position(), readSize);
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
            info("Before delete PMemChannel, channelSize = " + pBlock.size() + ", poolEntryCount = " + blockPool.size() + ", usedCounter = " + counter.get());
            if (pBlock.size() == poolEntrySize) {
                // clear the pmem metadata
                metaStore.removeLong(filePath.toString());
                metaStore.removeString(POOL_HANDLE_PREFIX + pBlock.handle());
                metaStore.removeInt(sizeKey);

                // reset memory
                // _buf.setMemory((byte)0, 0, poolEntrySize);
                // push back to pool
                blockPool.push(pBlock);
                int usedCounter = counter.decrementAndGet();
                metaStore.putInt(POOL_ENTRY_USED, usedCounter);

                if (poolEntryCount - usedCounter != blockPool.size()) {
                    error("pool free size (" + blockPool.size() + ") != poolEntryCount - usedCounter (" + (poolEntryCount - usedCounter) + ")");
                }
            } else {
                pBlock.freeMemory();
            }
            info("After delete PMemChannel, channelSize = " + pBlock.size() + ", poolEntryCount = " + blockPool.size() + ", usedCounter = " + counter.get());
            pBlock = null;
        }
    }

    private String concatPath(String str) {
        return "[" + filePath + "]: " + str;
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

    private PersistentMemoryBlock pBlock;
    private int channelSize;
    private int channelPosition = 0;
    private Path filePath;
    private String sizeKey;
}
