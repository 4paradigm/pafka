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

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;

public class RocksdbMetaStore extends MetaStore {
    static boolean libLoaded = false;
    private static final Logger log = LoggerFactory.getLogger(RocksdbMetaStore.class);

    private RocksDB db;

    private static void loadLib() {
        if (!libLoaded) {
            RocksDB.loadLibrary();
            libLoaded = true;
        }
    }

    public RocksdbMetaStore(String path) {
        loadLib();

        // the Options class contains a set of configurable DB options
        // that determines the behaviour of the database.
        Options options = new Options().setCreateIfMissing(true);
        try {
            this.db = RocksDB.open(options, path);
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public void put(String key, String value) {
        try {
            this.db.put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public String get(String key) {
        byte[] value = null;
        try {
            value = this.db.get(key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    @Override
    public void del(String key) {
        try {
            this.db.delete(key.getBytes(StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
    }
}
