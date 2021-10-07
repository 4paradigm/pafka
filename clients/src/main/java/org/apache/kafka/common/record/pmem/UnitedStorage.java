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

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnitedStorage {
    private static final Logger log = LoggerFactory.getLogger(UnitedStorage.class);
    
    private String[] dirs;
    private long[] frees;
    private long[] capacities;
    private long free = 0;
    private long capacity = 0;
    private Object lock = new Object();
    private String maxDir = null;
    Random rand = new Random();

    public UnitedStorage(String[] dirs) {
        this.dirs = dirs;

        frees = new long[dirs.length];
        capacities = new long[dirs.length];
        frees = new long[dirs.length];

        for (int i = 0; i < dirs.length; i++) {
            File file = new File(dirs[i]);
            capacities[i] = file.getTotalSpace();
            capacity += capacities[i];
        }

        updateStat();
    }

    public String maxDir() {
        return maxDir;
    }

    public long capacity() {
        return this.capacity;
    }

    public long free() {
        return this.free;
    }

    public boolean containsAbsolute(String file) {
        for (int i = 0; i < this.dirs.length; i++) {
            if (file.startsWith(this.dirs[i])) {
                return true;
            }
        }

        return false;
    }

    public boolean containsRelative(String file) {
        return containsRelativeInternal(file) >= 0;
    }

    public int containsRelativeInternal(String file) {
        for (int i = 0; i < this.dirs.length; i++) {
            Path absPath = Paths.get(this.dirs[i], file);
            if (absPath.toFile().exists()) {
                return i;
            }
        }

        return -1;
    }

    public String randomDir() {
        return randomDir(true, false);
    }

    public String randomDir(boolean balanced, boolean update) {
        if (!balanced) {
            return this.dirs[rand.nextInt(this.dirs.length)];
        } else {
            if (update) {
                updateStat();
            }

            long[] freesCopy = null;
            long freeCopy = 0;
            synchronized (lock) {
                freesCopy = frees.clone();
                freeCopy = free;
            }

            long factor = 1024L * 1024 * 1024;
            int r = rand.nextInt((int) (freeCopy / factor));
            int cum = 0;
            for (int i = 0; i < freesCopy.length; i++) {
                cum += freesCopy[i] / factor;

                if (cum > r) {
                    return this.dirs[i];
                }
            }

            log.error("Cannot get a reasonable root dir");
            return this.dirs[0];
        }
    }

    public String toAbsolute(String relativePath) {
        int idx = containsRelativeInternal(relativePath);
        String dir = null;
        if (idx >= 0) {
            dir = this.dirs[idx];
        } else {
            dir = randomDir();
        }
        return Paths.get(dir, relativePath).toString();
    }

    private void updateStat() {
        synchronized (lock) {
            free = 0;
            long max = 0;
            for (int i = 0; i < this.dirs.length; i++) {
                File file = new File(this.dirs[i]);
                frees[i] = file.getFreeSpace();
                free += frees[i];

                if (frees[i] > max) {
                    max = frees[i];
                    maxDir = this.dirs[i];
                }
            }
        }
    }
    
}
