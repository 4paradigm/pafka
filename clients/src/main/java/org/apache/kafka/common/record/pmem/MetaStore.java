package org.apache.kafka.common.record.pmem;

import lib.util.persistent.ObjectDirectory;
import lib.util.persistent.PersistentInteger;
import lib.util.persistent.PersistentLong;
import lib.util.persistent.PersistentString;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public abstract class MetaStore {
    public final static int NOT_EXIST_INT = Integer.MIN_VALUE;
    public final static long  NOT_EXIST_LONG = Long.MIN_VALUE;

    public void putInt(String key, int value) {
        put(key, Integer.toString(value));
    }

    public void putLong(String key, long value) {
        put(key, Long.toString(value));
    }

    public void putString(String key, String value) {
        put(key, value);
    }

    public abstract void put(String key, String value);

    public int getInt(String key) {
        String value = get(key);
        return value == null || value.isEmpty() ? NOT_EXIST_INT : Integer.parseInt(get(key));
    }

    public long getLong(String key) {
        String value = get(key);
        return value == null || value.isEmpty() ? NOT_EXIST_LONG : Long.parseLong(get(key));
    }

    public String getString(String key) {
        return get(key);
    }

    public abstract String get(String key);

    public abstract void del(String key);
};
