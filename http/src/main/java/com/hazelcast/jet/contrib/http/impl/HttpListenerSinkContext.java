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

package com.hazelcast.jet.contrib.http.impl;


import com.hazelcast.function.FunctionEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.Util;
import com.hazelcast.jet.core.Processor;
import com.hazelcast.ringbuffer.Ringbuffer;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.sse.ServerSentEventConnection;
import io.undertow.server.handlers.sse.ServerSentEventHandler;
import io.undertow.websockets.WebSocketProtocolHandshakeHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import org.xnio.SslClientAuthMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import static com.hazelcast.jet.contrib.http.impl.HttpListenerSinkContext.SinkType.WEBSOCKET;
import static io.undertow.Handlers.path;
import static io.undertow.UndertowOptions.ENABLE_HTTP2;
import static org.xnio.Options.SSL_CLIENT_AUTH_MODE;

public class HttpListenerSinkContext<T> {

    private static final String ADDRESS_PATTERN = "%s://%s:%d%s";

    private final SinkHttpHandler sinkHttpHandler;
    private final Undertow undertow;
    private final FunctionEx<T, String> toStringFn;
    private final Ringbuffer<String> ringBuffer;
    private final int accumulateLimit;
    private final ConcurrentLinkedQueue<String> messageBuffer;

    public HttpListenerSinkContext(
            @Nonnull Processor.Context context,
            @Nonnull String path,
            int port,
            int accumulateLimit,
            boolean mutualAuthentication,
            SinkType sinkType,
            @Nullable SupplierEx<SSLContext> sslContextFn,
            @Nonnull SupplierEx<String> hostFn,
            @Nonnull FunctionEx<T, String> toStringFn
    ) {
        this.accumulateLimit = accumulateLimit;
        this.messageBuffer = accumulateLimit > 0 ? new ConcurrentLinkedQueue<>() : null;
        this.sinkHttpHandler = sinkType == WEBSOCKET ? new WebSocketSinkHttpHandler(messageBuffer)
                : new ServerSentSinkHttpHandler(messageBuffer);
        this.toStringFn = toStringFn;
        String host = hostFn.get();

        Undertow.Builder builder = Undertow.builder();
        if (sslContextFn != null) {
            if (mutualAuthentication) {
                builder.setServerOption(SSL_CLIENT_AUTH_MODE, SslClientAuthMode.REQUIRED);
            }
            builder.addHttpsListener(port, host, sslContextFn.get());
        } else {
            builder.addHttpListener(port, host);
        }

        undertow = builder
                .setServerOption(ENABLE_HTTP2, true)
                .setHandler(path().addPrefixPath(path, sinkHttpHandler.httpHandler()))
                .build();

        undertow.start();
        String observableName = getObservableNameByJobId(context.jobId());
        ringBuffer = context.jetInstance().getHazelcastInstance().getRingbuffer(observableName);
        ringBuffer.add(sinkAddress(host, port, path, sinkType, sslContextFn != null));
    }

    @SuppressWarnings("unchecked")
    public void receive(Object item) {
        if (sinkHttpHandler.hasConnectedClients()) {
            drainBuffer();
            sinkHttpHandler.send(toStringFn.apply((T) item));
        } else {
            putToBuffer((T) item);
        }
    }

    public void flush() {
        if (sinkHttpHandler.hasConnectedClients()) {
            drainBuffer();
        }
    }

    public void close() {
        undertow.stop();
        ringBuffer.destroy();
    }

    private void drainBuffer() {
        if (messageBuffer == null) {
            return;
        }
        String message = messageBuffer.poll();
        while (message != null) {
            sinkHttpHandler.send(message);
            message = messageBuffer.poll();
        }
    }

    private void putToBuffer(T item) {
        if (messageBuffer == null || messageBuffer.size() == accumulateLimit) {
            return;
        }
        messageBuffer.add(toStringFn.apply(item));
    }

    interface SinkHttpHandler {

        HttpHandler httpHandler();

        void send(String message);

        boolean hasConnectedClients();
    }

    static class WebSocketSinkHttpHandler implements SinkHttpHandler {

        private final WebSocketProtocolHandshakeHandler handler;
        private final Set<WebSocketChannel> peerConnections;

        WebSocketSinkHttpHandler(ConcurrentLinkedQueue<String> messageBuffer) {
            handler = Handlers.websocket((exchange, channel) -> {
                String message = messageBuffer.poll();
                while (message != null) {
                    send(message);
                    message = messageBuffer.poll();
                }
            });
            peerConnections = handler.getPeerConnections();
        }

        @Override
        public HttpHandler httpHandler() {
            return handler;
        }

        @Override
        public void send(String message) {
            peerConnections.forEach(channel -> WebSockets.sendText(message, channel, null));
        }

        @Override
        public boolean hasConnectedClients() {
            return !peerConnections.isEmpty();
        }
    }

    static class ServerSentSinkHttpHandler implements SinkHttpHandler {

        private final ServerSentEventHandler handler;
        private final Set<ServerSentEventConnection> connections;

        ServerSentSinkHttpHandler(ConcurrentLinkedQueue<String> messageBuffer) {
            handler = Handlers.serverSentEvents((connection, lastEventId) -> {
                String message = messageBuffer.poll();
                while (message != null) {
                    send(message);
                    message = messageBuffer.poll();
                }
            });
            connections = handler.getConnections();
        }

        @Override
        public HttpHandler httpHandler() {
            return handler;
        }

        @Override
        public void send(String message) {
            connections.forEach(connection -> connection.send(message));
        }

        @Override
        public boolean hasConnectedClients() {
            return !connections.isEmpty();
        }
    }

    public static String getObservableNameByJobId(long id) {
        return Util.idToString(id) + "-http-listener-sink";
    }

    private String sinkAddress(String host, int port, String path, SinkType sinkType, boolean secure) {
        if (sinkType == WEBSOCKET) {
            return String.format(ADDRESS_PATTERN, secure ? "wss" : "ws", host, port, path);
        }
        return String.format(ADDRESS_PATTERN, secure ? "https" : "http", host, port, path);
    }

    public enum SinkType {
        WEBSOCKET, SSE
    }
}
