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

import lib.util.persistent.ObjectDirectory;
import lib.util.persistent.PersistentInteger;
import lib.util.persistent.PersistentLong;
import lib.util.persistent.PersistentString;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class PMemMetaStore extends MetaStore {
    public PMemMetaStore(String path, long size) {
        super(path);

        // TODO(zhanghao): config.properties is required by pmdk pcj. For now we generate dynamically here
        try {
            BufferedWriter metaConfig = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream("config.properties"), StandardCharsets.UTF_8));
            String metaConfigContent = "path=" + path + "\n" + "size=" + size + "\n";
            metaConfig.write(metaConfigContent);
            metaConfig.flush();
            metaConfig.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void putInt(String key, int value) {
        ObjectDirectory.put(key, new PersistentInteger(value));
    }

    @Override
    public void putLong(String key, long value) {
        ObjectDirectory.put(key, new PersistentLong(value));
    }

    @Override
    public void put(String key, String value) {
        ObjectDirectory.put(key, new PersistentString(value));
    }

    @Override
    public int getInt(String key) {
        PersistentInteger value = ObjectDirectory.get(key, PersistentInteger.class);
        if (value == null) {
            return NOT_EXIST_INT;
        } else {
            return value.intValue();
        }
    }

    @Override
    public long getLong(String key) {
        PersistentLong value = ObjectDirectory.get(key, PersistentLong.class);
        if (value == null) {
            return NOT_EXIST_LONG;
        } else {
            return value.longValue();
        }
    }

    @Override
    public String get(String key) {
        PersistentString value = ObjectDirectory.get(key, PersistentString.class);
        if (value == null) {
            return null;
        } else {
            return value.toString();
        }
    }

    public void removeInt(String key) {
        ObjectDirectory.remove(key, PersistentInteger.class);
    }

    public void removeLong(String key) {
        ObjectDirectory.remove(key, PersistentLong.class);
    }

    public void removeString(String key) {
        ObjectDirectory.remove(key, PersistentString.class);
    }

    @Override
    public void del(String key) {
        removeString(key);
    }
};
