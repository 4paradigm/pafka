package org.apache.kafka.common.record.pmem;

import com.intel.pmem.llpl.HeapException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
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

public class MixChannel extends FileChannel {
    enum Mode {
        PMEM(0),
        NVME(1),
        SSD(2),
        FILE(3);

        public int value;
        public static final int length = Mode.values().length;
        private Mode(int value) { this.value = value; }

        public static Mode fromInteger(int x) {
            switch(x) {
                case 0:
                    return PMEM;
                case 1:
                    return NVME;
                case 2:
                    return SSD;
                case 3:
                    return FILE;
            }
            return null;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MixChannel.class);
    private static Mode defaultMode = Mode.PMEM;
    private static MetaStore metaStore;
    
    private Mode mode = defaultMode;
    /**
     * use different location for different channels to avoid thread-risk issues
     */
    private FileChannel[] channels = null;

    private Path path = null;

    public static void init(String path) {
        metaStore = new RocksdbMetaStore(path);
    }

    public static MixChannel open(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        return new MixChannel(file, initFileSize, preallocate, mutable);
    }

    public MixChannel(Path file, int initFileSize, boolean preallocate, boolean mutable) throws IOException {
        path = file;
        this.channels = new FileChannel[Mode.length];

        int modeValue = metaStore.getInt(file.toString());
        if (modeValue != MetaStore.NOT_EXIST_INT) {
            this.mode = Mode.fromInteger(modeValue);
            switch (mode) {
                case PMEM:
                    this.channels[Mode.PMEM.value] = PMemChannel.open(file, initFileSize, preallocate, mutable);
                    break;
                case FILE:
                    this.channels[Mode.FILE.value] = openFileChannel(file, initFileSize, preallocate, mutable);
                    break;
                default:
                    log.error("Not support ChannelModel " + this.mode);
                    break;
            }
        } else {
            try {
                // TODO(zhanghao): support other default channel
                this.channels[Mode.PMEM.value] = PMemChannel.open(file, initFileSize, preallocate, mutable);
                this.mode = Mode.PMEM;
            } catch (Exception e) {
                log.info("Fail to allocate in " + defaultMode + " channel. Using normal FileChannel instead.");
                this.channels[Mode.FILE.value] = openFileChannel(file, initFileSize, preallocate, mutable);
                this.mode = Mode.FILE;

                modeValue = metaStore.getInt(file.toString());
                if (modeValue != MetaStore.NOT_EXIST_INT && this.mode.value != modeValue) {
                    log.error("realPath for " + file.toString() + " already exists: " + modeValue + ", but != " + modeValue);
                }
            }
            metaStore.putInt(file.toString(), this.mode.value);
        }
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
                newChannel = PMemChannel.open(this.path, (int)this.size(), true, true);
                break;
            case FILE:
                newChannel = openFileChannel(this.path, (int)this.size(), true, true);
                break;
            default:
                log.error("Not support Mode: " + m);
        }

        FileChannel oldChannel = getChannel();
        long size = oldChannel.size();
        long transferred = 0;
        do {
            transferred += oldChannel.transferTo(transferred, size, newChannel);
        } while (transferred < size);

        this.channels[m.value] = newChannel;
        // until this point, the newChannel not take effect
        this.mode = m;
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
        for (int i = 0; i < Mode.length; i++) {
            FileChannel channel = this.channels[i];
            if (channel == null) {
                continue;
            }

            Mode cmode = Mode.fromInteger(i);
            if (cmode == Mode.FILE) {
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
