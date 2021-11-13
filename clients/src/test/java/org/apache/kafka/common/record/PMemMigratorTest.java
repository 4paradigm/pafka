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

package org.apache.kafka.common.record;

import org.apache.kafka.common.record.pmem.MixChannel;
import org.apache.kafka.common.record.pmem.PMemChannel;
import org.apache.kafka.common.record.pmem.MixChannel.Mode;
import org.apache.kafka.common.record.pmem.MixChannel.Status;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.apache.kafka.test.TestUtils.tempDirectory;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class PMemMigratorTest {
    protected static File parent;
    protected static String firstLayerPath;
    protected static String secondLayerPath;
    protected static final String TOPIC = "topic-test";
    protected static final int BLOCK_NUM = 10;
    protected static final int BLOCK_SIZE = 1024 * 1024;
    protected static final int SIZE = BLOCK_SIZE * BLOCK_NUM;
    protected static final double THRESHOLD = 0.5;
    protected static Mode firstLayerMode = Mode.PMEM;
    protected static Mode secondLayerMode = Mode.HDD;

    @BeforeAll
    public static void setup() throws IOException {
        parent = tempDirectory();
        firstLayerPath = parent.toString() + "/pmem";
        secondLayerPath = parent.toString() + "/hdd";

        File topicDir = new File(firstLayerPath + "/" + TOPIC);
        assertTrue(topicDir.mkdirs());

        MixChannel.init(firstLayerPath, secondLayerPath, Integer.toString(SIZE), THRESHOLD, 1);
        PMemChannel.init(firstLayerPath, Integer.toString(SIZE), BLOCK_SIZE, 0.4);
    }

    @AfterAll
    public static void cleanup() throws IOException, InterruptedException {
        MixChannel.stop();
    }

    @Test
    public void testNoMigrate() throws IOException {
        int beforeMigrateNum = (int) (BLOCK_NUM * THRESHOLD);
        MixChannel[] channels = new MixChannel[beforeMigrateNum];
        for (int i = 0; i < beforeMigrateNum; i++) {
            channels[i] = MixChannel.open(Paths.get(
                    firstLayerPath + "/" + TOPIC + "/" + i), BLOCK_SIZE, true, true);
        }

        assertEquals(SIZE, MixChannel.getCapacity());
        assertEquals(beforeMigrateNum * BLOCK_SIZE, MixChannel.getUsed());
        for (int i = 0; i < beforeMigrateNum; i++) {
            assertEquals(channels[i].getMode(), Mode.PMEM);
            assertEquals(channels[i].getStatus(), Status.INIT);
        }

        // sleep 5s to let migrator scheduler start
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        assertEquals(beforeMigrateNum * BLOCK_SIZE, MixChannel.getUsed());
        for (int i = 0; i < beforeMigrateNum; i++) {
            assertEquals(channels[i].getMode(), Mode.PMEM);
            assertEquals(channels[i].getStatus(), Status.INIT);
        }

        for (int i = 0; i < beforeMigrateNum; i++) {
            channels[i].delete();
        }
        assertEquals(MixChannel.getUsed(), 0);
    }

    @Test
    public void testMigrate() throws IOException {
        int beforeMigrateNum = (int) (BLOCK_NUM * THRESHOLD);
        int migrateNum = 2;
        MixChannel[] channels = new MixChannel[beforeMigrateNum + migrateNum];
        for (int i = 0; i < beforeMigrateNum + migrateNum; i++) {
            channels[i] = MixChannel.open(Paths.get(firstLayerPath + "/" + TOPIC + "/" + i), BLOCK_SIZE, true, true);
        }

        assertEquals(MixChannel.getCapacity(), SIZE);
        assertEquals((beforeMigrateNum + migrateNum) * BLOCK_SIZE, MixChannel.getUsed());
        for (int i = 0; i < (beforeMigrateNum + migrateNum); i++) {
            assertEquals(channels[i].getMode(), Mode.PMEM);
            assertEquals(channels[i].getStatus(), Status.INIT);
        }

        // sleep 8s to let migrator scheduler start
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        assertEquals(beforeMigrateNum * BLOCK_SIZE, MixChannel.getUsed());
        for (int i = 0; i < migrateNum; i++) {
            assertEquals(channels[i].getMode(), Mode.HDD);
            assertEquals(channels[i].getStatus(), Status.INIT);
        }

        for (int i = migrateNum; i < beforeMigrateNum; i++) {
            assertEquals(channels[i].getMode(), Mode.PMEM);
            assertEquals(channels[i].getStatus(), Status.INIT);
        }

        for (int i = 0; i < (beforeMigrateNum + migrateNum); i++) {
            channels[i].delete();
        }
        assertEquals(0, MixChannel.getUsed());
    }
}