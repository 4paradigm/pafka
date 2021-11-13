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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Paths;

import static org.apache.kafka.test.TestUtils.tempDirectory;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MixChannelTest {
    protected static File parent;
    protected static String firstLayerPath;
    protected static String secondLayerPath;
    protected static final int SIZE = 10485760;
    protected static final String TOPIC = "topic-test";
    protected static final int BLOCK_SIZE = 1024 * 1024;
    protected static Mode firstLayerMode = Mode.PMEM;
    protected static Mode secondLayerMode = Mode.HDD;

    @BeforeAll
    public static void setup() throws IOException {
        parent = tempDirectory();
        firstLayerPath = parent.toString() + "/pmem";
        secondLayerPath = parent.toString() + "/hdd";

        File topicDir = new File(secondLayerPath + "/" + TOPIC);
        assertTrue(topicDir.mkdirs());

        MixChannel.init(firstLayerPath, secondLayerPath, Integer.toString(SIZE), 0.9, 1);
        PMemChannel.init(firstLayerPath, Integer.toString(SIZE), BLOCK_SIZE, 0.01);
    }

    @AfterAll
    public static void cleanup() throws IOException, InterruptedException {
        MixChannel.stop();
    }

    @Test
    public void testCreateDeleteFirstLayerChannel() throws IOException {
        testCreateDeleteChannel(BLOCK_SIZE, firstLayerMode);
    }

    @Test
    public void testCreateDeleteSecondLayerChannel() throws IOException {
        testCreateDeleteChannel(SIZE * 2, secondLayerMode);
    }

    public void testCreateDeleteChannel(int channelSize, Mode expectedMode) throws IOException {
        File file = new File(secondLayerPath + "/" + TOPIC + "/00001.log");
        String relativePath = Paths.get(file.getParentFile().getName(), file.getName()).toString();
        MixChannel channel = MixChannel.open(file.toPath(), channelSize, true, true);

        String[] toks = relativePath.split("/");
        long id = Long.parseLong(toks[1].split("\\.")[0]);
        assertTrue(file.exists());
        assertEquals(toks[0], channel.getNamespace());
        assertEquals(id, channel.getId());
        assertEquals(expectedMode, channel.getMode());
        assertEquals(MixChannel.Status.INIT, channel.getStatus());

        channel.delete();
        assertFalse(file.exists());
    }

    @Test
    public void testWriteReadFirstLayerChannel() throws IOException {
        testWriteReadChannel(BLOCK_SIZE, firstLayerMode);
    }

    @Test
    public void testWriteReadSecondLayerChannel() throws IOException {
        testWriteReadChannel(SIZE * 2, secondLayerMode);
    }

    public void testWriteReadChannel(int channelSIze, Mode expectedMode) throws IOException {
        File channelPath = new File(secondLayerPath + "/" + TOPIC + "/00002.log");
        MixChannel channel = MixChannel.open(channelPath.toPath(), channelSIze, true, true);
        assertEquals(expectedMode, channel.getMode());

        int s = 1024000;
        ByteBuffer buf = ByteBuffer.allocate(s);
        for (int i = 0; i < s; i++) {
            buf.put(i, (byte) (i % 128));
        }
        assertEquals(channel.write(buf), s);

        ByteBuffer rbuf = ByteBuffer.allocate(s);
        int readS = channel.read(rbuf, 0);
        assertEquals(s, readS);
        for (int i = 0; i < s; i++) {
            assertEquals((byte) (i % 128), rbuf.get(i));
        }

        channel.delete();
    }

    @Test
    public void testSetMode() throws IOException {
        File file = new File(secondLayerPath + "/" + TOPIC + "/00003.log");
        String relativePath = Paths.get(file.getParentFile().getName(), file.getName()).toString();
        MixChannel channel = MixChannel.open(file.toPath(), BLOCK_SIZE, true, true);

        String[] toks = relativePath.split("/");
        long id = Long.parseLong(toks[1].split("\\.")[0]);
        assertTrue(file.exists());
        assertEquals(toks[0], channel.getNamespace());
        assertEquals(id, channel.getId());
        assertEquals(firstLayerMode, channel.getMode());
        assertEquals(MixChannel.Status.INIT, channel.getStatus());
        assertEquals(file.length(), 0);

        // first layer file
        File pFile = new File(firstLayerPath + "/" + relativePath);
        assertTrue(pFile.exists());
        assertEquals(BLOCK_SIZE, pFile.length());

        channel.setMode(secondLayerMode);
        assertEquals(channel.getMode(), secondLayerMode);
        // id file
        assertTrue(file.exists());
        assertEquals(BLOCK_SIZE, file.length());

        // first layer file file
        assertFalse(pFile.exists());
    }
}