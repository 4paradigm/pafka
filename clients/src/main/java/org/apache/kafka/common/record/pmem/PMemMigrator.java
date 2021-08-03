package org.apache.kafka.common.record.pmem;

import lib.util.persistent.ObjectDirectory;
import lib.util.persistent.PersistentInteger;
import lib.util.persistent.PersistentString;
import lib.util.persistent.PersistentLong;
import com.intel.pmem.llpl.PersistentHeap;
import com.intel.pmem.llpl.HeapException;
import com.intel.pmem.llpl.PersistentMemoryBlock;
import org.apache.kafka.common.utils.KafkaThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;

class Migrate implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(Migrate.class);
    private volatile boolean stop = false;
    private String name = null;

    public Migrate(String name) {
        this.name = name;
    }

    @Override
    public void run() {
        while (!stop) {
            log.info(name + " running");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        stop = true;
    }
};

public class PMemMigrator {
    private static final Logger log = LoggerFactory.getLogger(PMemMigrator.class);
    private KafkaThread threadPool[] = null;
    private Migrate migrates[] = null;

    public PMemMigrator(int threads) {
        threadPool = new KafkaThread[threads];
        migrates = new Migrate[threads];
        for (int i = 0; i < threads; i++) {
            String name = "PMemMigrator-" + i;
            migrates[i] = new Migrate(name);
            threadPool[i] = KafkaThread.daemon(name, migrates[i]);
        }
    }

    public void start() {
        for (int i = 0; i < this.threadPool.length; i++) {
            threadPool[i].start();
        }
    }

    public void stop() throws InterruptedException {
        for (int i = 0; i < this.threadPool.length; i++) {
            migrates[i].stop();
        }
        for (int i = 0; i < this.threadPool.length; i++) {
            threadPool[i].join();
        }
    }
};
