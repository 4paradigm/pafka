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

package org.apache.kafka.common.record;

import com.intel.pmem.llpl.AnyHeap;
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

public class PMemChannel extends FileChannel {
    private static final Logger log = LoggerFactory.getLogger(PMemChannel.class);
    private static int poolAllocatedSize = 0;
    private static int poolSize = 0;
    private static PersistentHeap heap;
    private volatile static boolean inited = false;
    private static Pool<PersistentMemoryBlock> blockPool = new Pool<>();

    private static AtomicInteger counter = new AtomicInteger();
    private final static Object GLOBAL_LOCK = new Object();

    public static void initHeap(String path, long size, int allocatedSize, double poolRatio) {
        String heapPath = path + "/pmem_heap";
        String metaPath = path + "/pmem_meta";

        File heapDir = new File(path);
        if (!heapDir.mkdirs()) {
            log.debug(heapDir + " already exists.");
        }

        // TODO(zhanghao): config.properties is required by pmdk pcj. For now we generate dynamically here
        try {
            BufferedWriter metaConfig = new BufferedWriter(new OutputStreamWriter(new FileOutputStream("config.properties"), StandardCharsets.UTF_8));
            long metaPoolSize = (poolSize + 100) * 1024L * 1024;  // entry number will be #poolBlock + #dynamicBlock (we assume its max is 100)
            String metaConfigContent = "path=" + metaPath + "\n" + "size=" + metaPoolSize + "\n";
            metaConfig.write(metaConfigContent);
            metaConfig.flush();
            log.info("Generate config.properties for pmdk pcj");

            metaConfig.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!AnyHeap.exists(heapPath)) {
            if (poolRatio > 0.9) {
                log.warn("poolRatio larger than 90%, may fail to allocate");
            }
            int poolSize = (int) (size * poolRatio / allocatedSize);
            log.info("Init heap: size = " + size + ", poolSize = " + poolSize + " (" + (poolRatio * 100) + "% of total size)" + ", poolEntry size = " + allocatedSize);
            ObjectDirectory.put("_heap_path", new PersistentString(heapPath));
            ObjectDirectory.put("_pool_allocated_size", new PersistentInteger(allocatedSize));
            ObjectDirectory.put("_pool_size", new PersistentInteger(poolSize));

            PersistentHeap heap = PersistentHeap.createHeap(heapPath, size);
            if (poolSize > 0) {
                for (int i = 0; i < poolSize; i++) {
                    PersistentMemoryBlock block = heap.allocateMemoryBlock(allocatedSize);
                    // set data to 0
                    // block.setMemory(0, 0, allocatedSize)
                    ObjectDirectory.put("_pool_" + i, new PersistentLong(block.handle()));
                    log.info("init pool entry " + i);
                }

                // init pool used to 0
                ObjectDirectory.put("_pool_used", new PersistentInteger(0));
            }
            log.info("init heap " + heapPath + " done");
        } else {
            log.info("PMem heap " + heapPath + " already exists. No need to re-init");
        }
    }

    public static FileChannel open(Path file, int initFileSize, boolean preallocate) throws IOException {
        synchronized (GLOBAL_LOCK) {
            log.info("open PMemChannel " + file.toString());

            if (!inited) {
                inited = true;
                heap = PersistentHeap.openHeap(ObjectDirectory.get("_heap_path", PersistentString.class).toString());
                PersistentInteger poolAllocatedSizeP = ObjectDirectory.get("_pool_allocated_size", PersistentInteger.class);
                if (poolAllocatedSizeP != null) {
                    poolAllocatedSize = poolAllocatedSizeP.intValue();
                } else {
                    poolAllocatedSize = 0;
                    log.error("PMem heap is not inited (poolAllocatedSize is not set)");
                }

                PersistentInteger poolSizeP = ObjectDirectory.get("_pool_size", PersistentInteger.class);
                if (poolSizeP != null) {
                    poolSize = poolSizeP.intValue();
                } else {
                    poolSize = 0;
                    log.error("PMem heap poolSize is not set");
                }

                counter.set(ObjectDirectory.get("_pool_used", PersistentInteger.class).intValue());

                for (int i = 0; i < poolSize; i++) {
                    long handle = ObjectDirectory.get("_pool_" + i, PersistentLong.class).longValue();
                    PersistentString fileNameP = ObjectDirectory.get("_pool_handle_" + handle, PersistentString.class);
                    if (fileNameP == null || fileNameP.length() == 0) {
                        PersistentMemoryBlock block = heap.memoryBlockFromHandle(handle);
                        blockPool.push(block);
                    }
                }
                log.info("open heapPool with poolSize = " + poolSize + ", used = " + counter.get());
            }

            PMemChannel channel = null;
            try {
                channel = new PMemChannel(file, initFileSize, preallocate);
            } catch (IOException e) {
                log.error("Create PMemChannel exception: " + e);
            }
            return channel;
        }
    }

    public PMemChannel(Path file, int initSize, boolean preallocate) throws IOException {
        filePath = file;
        sizeKey = filePath.toString() + "/size";

        PersistentLong handleP = ObjectDirectory.get(file.toString(), PersistentLong.class);
        if (handleP != null) {  // already allocate, recover
            long handle = handleP.longValue();
            pBlock = heap.memoryBlockFromHandle(handle);
            if (initSize != 0) {
                error("initSize not 0 for recovered channel. initSize = " + initSize + ", buf.size = " + pBlock.size());
            }

            if (initSize != 0) {
                channelSize = initSize;
                ObjectDirectory.put(sizeKey, new PersistentInteger(channelSize));
            } else {
                // load the buffer size
                PersistentInteger sizeP = ObjectDirectory.get(sizeKey, PersistentInteger.class);
                if (sizeP != null) {
                    channelSize = sizeP.intValue();
                } else {
                    channelSize = (int) pBlock.size();
                }
            }

            info("recover block with handle " + handle);
        } else {  // allocate new block
            if (initSize == 0) {
                error("PMemChannel initSize 0 (have to set log.preallocate=true)");
            }

            // TODO(zhanghao): what if initSize is 0
            if (poolSize == 0 || initSize != poolAllocatedSize || counter.get() >= poolSize) {
                try {
                    pBlock = heap.allocateMemoryBlock(initSize);
                } catch (HeapException e) {
                    error(e.toString());
                    throw e;
                }
                ObjectDirectory.put(file.toString(), new PersistentLong(pBlock.handle()));
                info("Dynamically allocate " + initSize + " with handle " + pBlock.handle());
            } else {
                int usedCounter = counter.incrementAndGet();
                ObjectDirectory.put("_pool_used", new PersistentInteger(usedCounter));
                pBlock = blockPool.pop();
                if (pBlock == null) {
                    String msg = "block pool inconsistent, usedCounter = " + usedCounter + "， poolSize = " + poolAllocatedSize;
                    error(msg);
                    throw new IOException(msg);
                }
                ObjectDirectory.put(file.toString(), new PersistentLong(pBlock.handle()));
                ObjectDirectory.put("_pool_handle_" + pBlock.handle(), new PersistentString(file.toString()));
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
                ObjectDirectory.put(sizeKey, new PersistentInteger(this.channelSize));
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
    }

    public void delete() {
        synchronized (GLOBAL_LOCK) {
            info("Before delete PMemChannel, channelSize = " + pBlock.size() + ", poolSize = " + blockPool.size() + ", usedCounter = " + counter.get());
            if (pBlock.size() == poolAllocatedSize) {
                // clear the pmem metadata
                ObjectDirectory.remove(filePath.toString(), PersistentLong.class);
                ObjectDirectory.remove("_pool_handle_" + pBlock.handle(), PersistentString.class);
                ObjectDirectory.remove(sizeKey, PersistentInteger.class);

                // reset memory
                // _buf.setMemory((byte)0, 0, poolAllocatedSize);
                // push back to pool
                blockPool.push(pBlock);
                int usedCounter = counter.decrementAndGet();
                ObjectDirectory.put("_pool_used", new PersistentInteger(usedCounter));

                if (poolSize - usedCounter != blockPool.size()) {
                    error("pool free size (" + blockPool.size() + ") != poolSize - usedCounter (" + (poolSize - usedCounter) + ")");
                }
            } else {
                pBlock.freeMemory();
            }
            info("After delete PMemChannel, channelSize = " + pBlock.size() + ", poolSize = " + blockPool.size() + ", usedCounter = " + counter.get());
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

    private PersistentMemoryBlock pBlock;
    private int channelSize;
    private int channelPosition = 0;
    private Path filePath;
    private String sizeKey;
}
