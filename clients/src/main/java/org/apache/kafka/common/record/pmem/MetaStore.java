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
