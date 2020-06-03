/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.contrib.mqtt;

import com.hazelcast.jet.JetInstance;
import com.hazelcast.jet.core.JetTestSupport;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.test.TestSources;
import org.eclipse.paho.client.mqttv3.IMqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.internal.util.UuidUtil.newUnsecureUuidString;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;

/**
 * todo add proper javadoc
 */
public class MqttSinkTest extends JetTestSupport {

    @Rule
    public MosquittoContainer mosquittoContainer = new MosquittoContainer();

    private JetInstance jet;
    private IMqttAsyncClient client;
    private String broker;

    @Before
    public void setup() throws MqttException {
        jet = createJetMember();
        createJetMember();
        broker = mosquittoContainer.connectionString();
        client = MqttSinks.client(broker, newUnsecureUuidString());
        client.connect().waitForCompletion();
    }

    @After
    public void teardown() throws MqttException {
        client.disconnect().waitForCompletion();
        client.close();
    }

    @Test
    public void test() throws MqttException {
        int itemCount = 5800;
        AtomicInteger counter = new AtomicInteger();
        client.subscribe("/topic", 2, (topic, message) -> counter.incrementAndGet()).waitForCompletion();

        Pipeline p = Pipeline.create();

        p.readFrom(TestSources.items(range(0, itemCount).boxed().collect(toList())))
         .rebalance()
         .writeTo(MqttSinks.publish(broker, "/topic", "sinkClient", MqttConnectOptions::new, item -> {
             MqttMessage message = new MqttMessage(intToByteArray(item));
             message.setQos(2);
             return message;
         }));

        jet.newJob(p).join();

        assertEqualsEventually(itemCount, counter);
    }


    private static byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    private static int byteArrayToInt(byte[] data) {
        return data[0] << 24 | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | (data[3] & 0xFF);
    }
}
