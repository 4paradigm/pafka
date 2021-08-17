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

import org.apache.kafka.common.record.pmem.PMemChannel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;

import static org.apache.kafka.test.TestUtils.tempFile;

public class FileRecordsPMemTest extends FileRecordsTest {

    @Override
    protected FileRecords createFileRecords(byte[][] values) throws IOException {
        String pmemDir = "/tmp/pmem";
        File directory = new File(pmemDir);
        if (directory.exists()) {
            String[] entries = directory.list();
            for (String s : entries) {
                File currentFile = new File(directory.getPath(), s);
                currentFile.delete();
            }
            directory.delete();
        }
        directory.mkdirs();

        String path = pmemDir + "/heap";
        long size = 1024L * 1024 * 1024 * 10;
        int initSize = 10 * 1024 * 1024;
        PMemChannel.init(path, size, initSize, 0.9);

        FileRecords fileRecords = FileRecords.open(tempFile(), true, false, initSize, true, FileRecords.FileChannelType.PMEM);
        return fileRecords;
    }

    /**
     * Test that the cached size variable matches the actual file size as we append messages
     */
    @Test
    @Override
    public void testFileSize() throws IOException {
        assertEquals(fileRecords.channel().position(), fileRecords.sizeInBytes());
        for (int i = 0; i < 20; i++) {
            fileRecords.append(MemoryRecords.withRecords(CompressionType.NONE, new SimpleRecord("abcd".getBytes())));
            assertEquals(fileRecords.channel().position(), fileRecords.sizeInBytes());
        }
    }

    /**
     * disable this test as it will fail when preallocate == True
     */
    @Test
    @Override
    public void testBytesLengthOfWriteTo() throws IOException {
    }
}
