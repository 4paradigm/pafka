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

import org.apache.kafka.common.utils.KafkaThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeMap;
import java.util.NavigableMap;
import java.io.Serializable;
import java.util.ArrayList;

public class PMemMigrator {
    private static final Logger log = LoggerFactory.getLogger(PMemMigrator.class);
    private KafkaThread[] threadPool = null;
    private Migrate[] migrates = null;
    private Scheduler schedule = null;

    private long capacity = 0;
    /**
     * above threadhold1, start migrate from PMEM to HDD from the tail
     */
    private double threshold1 = 0.5;
    /**
     * below threshold2, start migrate from HDD to PMEM from the head
     * TODO(zhanghao): threshold2 is not used
     */
    private double threshold2 = 0.8;
    MixChannel lastEvicted;

    private volatile long usedG = 0;
    private volatile long usedTotalG = 0;
    private Object channelsLock = new Object();
    private Object statslLock = new Object();
    private TreeMap<MixChannel, MixChannel> channels = new TreeMap<>(new HotComparator());
    private ArrayList<MixChannel> tmpChannels = new ArrayList<>();

    private LinkedList<MigrateTask> highToLow = new LinkedList<>();
    private LinkedList<MigrateTask> lowToHigh = new LinkedList<>();

    private Map<String, Long> ns2Id = new HashMap<>();

    private static class HotComparator implements Comparator<MixChannel>, Serializable {
        @Override
        public int compare(MixChannel c1, MixChannel c2) {
            int before = c1.getTimestamp().compareTo(c2.getTimestamp());
            int ns = c1.getNamespace().compareTo(c2.getNamespace());
            return before == 0 ? (ns == 0 ? (int) (c1.getId() - c2.getId()) : ns) : before;
        }
    }

    private class MigrateTask {
        private MixChannel channel;
        private MixChannel.Mode mode;
        private long size;
        private String chStr;

        public MigrateTask(MixChannel ch, MixChannel.Mode mode) {
            this.channel = ch;
            this.mode = mode;
            this.size = ch.occupiedSize();
            this.chStr = ch.toString();
        }

        public void run(Runnable runner) {
            log.info("[" + runner.toString() + "] Running task: migrating " + channel.toString() + " to " + mode);
            boolean migrateSuccess = false;
            try {
                migrateSuccess = this.channel.setMode(this.mode);
                this.channel.setStatus(MixChannel.Status.INIT);
            } catch (IOException e) {
                log.error("Migrate error: " + e.getMessage());
                migrateSuccess = false;
            }

            // add back the usage
            if (!migrateSuccess) {
                log.info(this.chStr + ": migrate failed");
                synchronized (statslLock) {
                    if (this.mode == MixChannel.getDefaultMode()) {
                        usedG += size;
                    } else {
                        usedG -= size;
                    }
                }
            }
        }
    };

    private class Migrate implements Runnable {
        private volatile boolean stop = false;
        private String name = null;

        public Migrate(String name) {
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }

        @Override
        public void run() {
            while (!stop) {
                MigrateTask task = null;
                synchronized (channelsLock) {
                    if (highToLow.size() > 0) {
                        task = highToLow.pop();
                    }

                    if (task == null) {
                        if (lowToHigh.size() > 0) {
                            task = lowToHigh.pop();
                        }
                    }
                }

                if (task != null) {
                    task.run(this);
                } else {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        log.error(name + " exception:", e);
                    }
                }
            }
        }

        public void stop() {
            stop = true;
        }
    };

    private class Scheduler implements Runnable {
        private volatile boolean stop = false;

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            // wait for the existing channels initialization phase to complete
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                log.error("Sleep interrupt", e);
            }

            while (!stop) {
                // copy channel from tmpChannels
                ArrayList<MixChannel> clonedChannels = null;
                long used = 0;
                long usedStart = 0;
                long usedTotal = 0;
                synchronized (statslLock) {
                    used = usedG;
                    usedStart = usedG;
                    usedTotal = usedTotalG;
                    clonedChannels = (ArrayList<MixChannel>) tmpChannels.clone();
                    tmpChannels.clear();
                }

                // check the threshold
                synchronized (channelsLock) {
                    for (MixChannel ch : clonedChannels) {
                        channels.put(ch, ch);
                    }

                    log.info("[Before Schedule] usedHigh: " + (used >> 20) + " MB, thresholdHigh: " + (((long) (capacity * threshold1)) >> 20) +
                            " MB, limitHigh: " + (capacity >> 20) + " MB, usedTotal: " + (usedTotal >> 20) + " MB");
                    if (used > capacity * threshold1) {
                        used = checkHighToLow(used);
                    } else {
                        used = checkLowToHigh(used);
                    }
                    log.info("[After Schedule] usedHigh: " + (used >> 20) + " MB, thresholdHigh: " + (((long) (capacity * threshold1)) >> 20) +
                            " MB, limitHigh: " + (capacity >> 20) + " MB, usedTotal: " + (usedTotal >> 20) + " MB");
                }

                synchronized (statslLock) {
                    usedG += used - usedStart;
                }

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    log.error("Sleep interrupt", e);
                }
            }
        }

        public void stop() {
            stop = true;
        }

        /**
         * for debug purpose
         * print all the recorded channel info
         */
        private void traverseAllChannels() {
            for (MixChannel ch : channels.values()) {
                log.info("Traverse channel: " + ch.toString() + ", " + ch.getTimestamp() + ", " + ch.getTimestamp().getTime());
            }
        }
    };

    public PMemMigrator(int threads, long capacity, double threshold) {
        this.capacity = capacity;
        this.threshold1 = threshold;
        this.threshold2 = threshold;
        threadPool = new KafkaThread[threads + 1];
        migrates = new Migrate[threads];
        for (int i = 0; i < threads; i++) {
            String name = "PMemMigrator-" + i;
            migrates[i] = new Migrate(name);
            threadPool[i] = KafkaThread.daemon(name, migrates[i]);
        }

        schedule = new Scheduler();
        threadPool[threads] = KafkaThread.daemon("PMemMigrationScheduler", schedule);
    }

    public void add(MixChannel channel) {
        log.debug("Add " + channel);
        String ns = channel.getNamespace();
        long id = channel.getId();
        synchronized (statslLock) {
            tmpChannels.add(channel);
            if (ns2Id.containsKey(ns)) {
                long existingId = ns2Id.get(ns);
                if (existingId >= id) {
                    log.error("ID under " + ns + " not incremental: current = " + existingId + ", next = " + id);
                } else {
                    ns2Id.put(channel.getNamespace(), id);
                }
            } else {
                ns2Id.put(channel.getNamespace(), id);
            }

            if (channel.getMode() == MixChannel.getDefaultMode()) {
                usedG += channel.occupiedSize();
            }
            usedTotalG += channel.occupiedSize();
        }
    }

    /**
     * @param channel
     */
    public void remove(MixChannel channel) {
        synchronized (channelsLock) {
            channels.remove(channel);
        }

        synchronized (statslLock) {
            if (channel.getMode() == MixChannel.getDefaultMode()) {
                usedG -= channel.occupiedSize();
            }
            usedTotalG -= channel.occupiedSize();
        }
    }

    public void start() {
        for (int i = 0; i < this.threadPool.length; i++) {
            if (i == this.threadPool.length - 1) {
                log.info("Start migration scheduler");
            } else {
                log.info("Start migrator " + i);
            }
            threadPool[i].start();
        }
    }

    public void stop() throws InterruptedException {
        for (int i = 0; i < this.threadPool.length - 1; i++) {
            migrates[i].stop();
        }
        schedule.stop();

        for (int i = 0; i < this.threadPool.length; i++) {
            threadPool[i].join();
        }
    }

    private long checkHighToLow(long used) {
        boolean stillLow = true;
        for (MixChannel ch : channels.values()) {
            MixChannel.Mode m = ch.getMode();

            if (ch.getStatus() != MixChannel.Status.MIGRATION &&
                    m == MixChannel.getDefaultMode() && channelDone(ch)) {
                addTask(ch, MixChannel.Mode.HDD, true);
                used -= ch.occupiedSize();
                lastEvicted = ch;
            }

            if (m == MixChannel.Mode.HDD && stillLow) {
                lastEvicted = ch;
            } else {
                stillLow = false;
            }

            if (used <= capacity * threshold1) {
                break;
            }
        }
        return used;
    }

    private long checkLowToHigh(long used) {
        // TODO(zhanghao): optimize this iter code
        NavigableMap<MixChannel, MixChannel> it = channels.descendingMap();
        for (MixChannel ch : it.values()) {
            if (ch == lastEvicted) {
                log.debug("traverse until lastEvicted");
                break;
            }

            if (ch.getStatus() != MixChannel.Status.MIGRATION &&
                    ch.getMode().equal(MixChannel.Mode.HDD) && channelDone(ch)) {
                if (used + ch.occupiedSize() <= capacity * threshold2) {
                    addTask(ch, MixChannel.getDefaultMode(), false);
                    used += ch.occupiedSize();
                } else {
                    break;
                }
            }
        }
        return used;
    }

    private void addTask(MixChannel channel, MixChannel.Mode mode, boolean h2l) {
        log.info("AddTask: " + channel.getNamespace() +  "/" + channel.getId() + " to " + mode);
        MigrateTask task = new MigrateTask(channel, mode);
        if (h2l) {
            highToLow.add(task);
        } else {
            lowToHigh.add(task);
        }

        channel.setStatus(MixChannel.Status.MIGRATION);
    }

    private boolean channelDone(MixChannel channel) {
        return ns2Id.get(channel.getNamespace()) > channel.getId();
    }
};
