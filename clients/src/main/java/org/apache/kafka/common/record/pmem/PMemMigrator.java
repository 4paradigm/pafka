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

public class PMemMigrator {
    private static final Logger log = LoggerFactory.getLogger(PMemMigrator.class);
    private KafkaThread[] threadPool = null;
    private Migrate[] migrates = null;
    private Scheduler schedule = null;

    private long capacity = 0;
    private double threshold = 0;
    private volatile long used = 0;
    private volatile long usedTotal = 0;
    private Object lock = new Object();
    private TreeMap<MixChannel, MixChannel> channels = new TreeMap<>(new HotComparator());

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

    private static class MigrateTask {
        private MixChannel channel;
        private MixChannel.Mode mode;

        public MigrateTask(MixChannel ch, MixChannel.Mode mode) {
            this.channel = ch;
            this.mode = mode;
        }

        public void run() {
            log.info("Running task: migrating " + channel.toString() + " to " + mode);
            try {
                this.channel.setMode(this.mode);
                this.channel.setStatus(MixChannel.Status.INIT);
            } catch (IOException e) {
                log.error("Migrate error: " + e.getMessage());
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
        public void run() {
            while (!stop) {
                MigrateTask task = null;
                synchronized (lock) {
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
                    task.run();
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
        public void run() {
            // wait for the existing channels initialization phase to complete
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                log.error("Sleep interrupt", e);
            }

            while (!stop) {
                // check the threshold
                synchronized (lock) {
                    log.info("[Before Schedule] usedHigh: " + (used >> 20) + " MB, thresholdHigh: " + (((long) (capacity * threshold)) >> 20) +
                            " MB, limitHigh: " + (capacity >> 20) + " MB, usedTotal: " + (usedTotal >> 20) + " MB");
                    if (used >= capacity * threshold) {
                        for (MixChannel ch : channels.values()) {
                            if (ch.getStatus() != MixChannel.Status.MIGRATION &&
                                    ch.getMode().higherThan(MixChannel.Mode.HDD) && channelDone(ch)) {
                                addTask(ch, MixChannel.Mode.HDD, true);
                                used -= ch.occupiedSize();
                            }

                            if (used <= capacity * threshold) {
                                break;
                            }
                        }
                    } else {
                        // TODO(zhanghao): optimize this iter code
                        NavigableMap<MixChannel, MixChannel> it = channels.descendingMap();
                        for (MixChannel ch : it.values()) {
                            if (ch.getStatus() != MixChannel.Status.MIGRATION &&
                                    ch.getMode().equal(MixChannel.Mode.HDD) && channelDone(ch)) {
                                if (used + ch.occupiedSize() <= capacity * threshold) {
                                    addTask(ch, MixChannel.getDefaultMode(), false);
                                    used += ch.occupiedSize();
                                } else {
                                    break;
                                }
                            }
                        }
                    }
                    log.info("[Before Schedule] usedHigh: " + (used >> 20) + " MB, thresholdHigh: " + (((long) (capacity * threshold)) >> 20) +
                            " MB, limitHigh: " + (capacity >> 20) + " MB, usedTotal: " + (usedTotal >> 20) + " MB");
                }
                try {
                    Thread.sleep(10000);
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
        this.threshold = threshold;
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
        String ns = channel.getNamespace();
        long id = channel.getId();
        synchronized (lock) {
            channels.put(channel, channel);
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

            if (channel.getMode().higherThan(MixChannel.Mode.HDD)) {
                used += channel.occupiedSize();
            }
            usedTotal += channel.occupiedSize();
        }
    }

    /**
     * @param channel
     */
    public void remove(MixChannel channel) {
        synchronized (lock) {
            if (channel.getMode().higherThan(MixChannel.Mode.HDD)) {
                used -= channel.occupiedSize();
            }
            usedTotal -= channel.occupiedSize();

            channels.remove(channel);
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
