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

package com.hazelcast.jet.contrib.mqtt.impl;

import com.hazelcast.logging.ILogger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;

import java.util.concurrent.Semaphore;

/**
 * An MQTT client callback for async publishing.
 * A {@link Semaphore} is initialized with permits equal to
 * {@link MqttConnectOptions#getMaxInflight()}.
 */
public class SinkCallback extends AbstractCallback {

    private final Semaphore semaphore;

    public SinkCallback(ILogger logger, MqttConnectOptions connectOptions) {
        super(logger);
        this.semaphore = new Semaphore(connectOptions.getMaxInflight());
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        semaphore.release();
    }

    public void acquire() throws InterruptedException {
        semaphore.acquire();
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        logger.info("Connection(reconnect=" + reconnect + ") to " + serverURI + " complete.");
    }
}
