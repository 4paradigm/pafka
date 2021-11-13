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
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;

import static org.apache.kafka.test.TestUtils.tempDirectory;

public class MixChannelNVMeTest extends MixChannelTest {
    @BeforeAll
    public static void setup() throws IOException {
        parent = tempDirectory();
        firstLayerPath = parent.toString() + "/nvme";
        secondLayerPath = parent.toString() + "/hdd";
        File topicDir = new File(secondLayerPath + "/" + TOPIC);
        assertTrue(topicDir.mkdirs());

        firstLayerMode = Mode.NVME;
        PMemChannel.init(firstLayerPath, Integer.toString(SIZE), BLOCK_SIZE, 0.01);
        MixChannel.init(firstLayerPath, firstLayerMode, secondLayerPath, secondLayerMode, Integer.toString(SIZE), 0.9,
                1);
    }
}
