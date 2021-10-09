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
import org.apache.kafka.common.utils.Utils;
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
    private static MixChannel channelG;
    private static File channelPath;
    private static File parent;
    private static String pmemPath;
    private static String hddPath;
    private static String relativePathG;
    private static final String SIZE = "10485760";
    private static final int BLOCK_SIZE = 1024 * 1024;

    @BeforeAll
    public static void setup() throws IOException {
        parent = tempDirectory();
        pmemPath = parent.toString() + "/pmem";
        hddPath = parent.toString() + "/hdd";
        channelPath = new File(hddPath + "/topic-test/00001.log");
        relativePathG = Paths.get(parent.getName(), channelPath.getName()).toString();

        MixChannel.init(pmemPath, hddPath, SIZE, 0.9, 1);
        channelG = new MixChannel(channelPath.toPath(), BLOCK_SIZE, true, true);
    }

    @AfterAll
    public static void cleanup() throws IOException, InterruptedException {
        channelG.delete();
        MixChannel.stop();
        Utils.delete(new File(MixChannel.getMetaStore().getPath()));
    }

    @Test
    public void testCreateDeleteChannel() throws IOException {
        File file = new File(parent.getPath() + "/topic-test/00002.log");
        String relativePath = Paths.get(file.getParentFile().getName(), file.getName()).toString();
        MixChannel channel = new MixChannel(file.toPath(), BLOCK_SIZE, true, true);

        String[] toks = relativePath.split("/");
        long id = Long.parseLong(toks[1].split("\\.")[0]);
        assertTrue(file.exists());
        assertEquals(toks[0], channel.getNamespace());
        assertEquals(id, channel.getId());
        assertEquals(MixChannel.Mode.HDD, channel.getMode());
        assertEquals(MixChannel.Status.INIT, channel.getStatus());

        channel.delete();
        assertFalse(file.exists());
    }

    @Test
    public void testWriteReadChannel() throws IOException {
        int s = 1024000;
        ByteBuffer buf = ByteBuffer.allocate(s);
        for (int i = 0; i < s; i++) {
            buf.put(i, (byte) (i % 128));
        }
        assertEquals(channelG.write(buf), s);

        ByteBuffer rbuf = ByteBuffer.allocate(s);
        int readS = channelG.read(rbuf, 0);
        assertEquals(s, readS);
        for (int i = 0; i < s; i++) {
            assertEquals((byte) (i % 128), rbuf.get(i));
        }
    }

    @Test
    public void testSetMode() throws IOException {
        String pmemPath = parent.toString() + "/pmem";
        PMemChannel.init(pmemPath, SIZE, BLOCK_SIZE, 0.01);
        File file = new File(hddPath + "/topic-test/00003.log");
        String relativePath = Paths.get(file.getParentFile().getName(), file.getName()).toString();
        MixChannel channel = new MixChannel(file.toPath(), BLOCK_SIZE, true, true);

        String[] toks = relativePath.split("/");
        long id = Long.parseLong(toks[1].split("\\.")[0]);
        assertTrue(file.exists());
        assertEquals(toks[0], channel.getNamespace());
        assertEquals(id, channel.getId());
        assertEquals(MixChannel.Mode.PMEM, channel.getMode());
        assertEquals(MixChannel.Status.INIT, channel.getStatus());
        assertEquals(file.length(), 0);

        // pmem file
        File pFile = new File(pmemPath + "/" + relativePath);
        assertTrue(pFile.exists());
        assertEquals(BLOCK_SIZE, pFile.length());

        channel.setMode(MixChannel.Mode.HDD);
        assertEquals(channel.getMode(), MixChannel.Mode.HDD);
        // id file
        assertTrue(file.exists());
        assertEquals(BLOCK_SIZE, file.length());

        // pmem file
        assertFalse(pFile.exists());
    }
}
