package org.apache.kafka.common.record.pmem;

import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RocksdbMetaStore extends MetaStore {
    static boolean libLoaded = false;
    private static final Logger log = LoggerFactory.getLogger(RocksdbMetaStore.class);

    private RocksDB db;

    public RocksdbMetaStore(String path) {
        if (!libLoaded) {
            RocksDB.loadLibrary();
            libLoaded = true;
        }

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
            this.db.put(key.getBytes(), value.getBytes());
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
    }

    @Override
    public String get(String key) {
        byte[] value = null;
        try {
            value = this.db.get(key.getBytes());
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
        return value == null ? null : new String(value);
    }

    @Override
    public void del(String key) {
        try {
            this.db.delete(key.getBytes());
        } catch (RocksDBException e) {
            log.error(e.getMessage());
        }
    }
}
