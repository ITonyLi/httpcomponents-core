/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.core5.http2.examples;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpConnection;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.Message;
import org.apache.hc.core5.http.Methods;
import org.apache.hc.core5.http.impl.bootstrap.HttpAsyncRequester;
import org.apache.hc.core5.http.nio.AsyncClientEndpoint;
import org.apache.hc.core5.http.nio.entity.StringAsyncEntityConsumer;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.nio.support.BasicResponseConsumer;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.http2.config.H2Config;
import org.apache.hc.core5.http2.frame.RawFrame;
import org.apache.hc.core5.http2.impl.nio.H2StreamListener;
import org.apache.hc.core5.http2.impl.nio.bootstrap.H2RequesterBootstrap;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

/**
 * Example of HTTP/2 concurrent request execution using multiple streams.
 */
public class H2MultiStreamExecutionExample {

    public static void main(final String[] args) throws Exception {

        // Create and start requester
        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                .setSoTimeout(5, TimeUnit.SECONDS)
                .build();

        final H2Config h2Config = H2Config.custom()
                .setPushEnabled(false)
                .setMaxConcurrentStreams(100)
                .build();

        final HttpAsyncRequester requester = H2RequesterBootstrap.bootstrap()
                .setIOReactorConfig(ioReactorConfig)
                .setVersionPolicy(HttpVersionPolicy.FORCE_HTTP_2)
                .setH2Config(h2Config)
                .setStreamListener(new H2StreamListener() {

                    @Override
                    public void onHeaderInput(final HttpConnection connection, final int streamId, final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println(connection.getRemoteAddress() + " (" + streamId + ") << " + headers.get(i));
                        }
                    }

                    @Override
                    public void onHeaderOutput(final HttpConnection connection, final int streamId, final List<? extends Header> headers) {
                        for (int i = 0; i < headers.size(); i++) {
                            System.out.println(connection.getRemoteAddress() + " (" + streamId + ") >> " + headers.get(i));
                        }
                    }

                    @Override
                    public void onFrameInput(final HttpConnection connection, final int streamId, final RawFrame frame) {
                    }

                    @Override
                    public void onFrameOutput(final HttpConnection connection, final int streamId, final RawFrame frame) {
                    }

                    @Override
                    public void onInputFlowControl(final HttpConnection connection, final int streamId, final int delta, final int actualSize) {
                    }

                    @Override
                    public void onOutputFlowControl(final HttpConnection connection, final int streamId, final int delta, final int actualSize) {
                    }

                })
                .create();
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                System.out.println("HTTP requester shutting down");
                requester.close(CloseMode.GRACEFUL);
            }
        });
        requester.start();

        final HttpHost target = new HttpHost("nghttp2.org");
        final String[] requestUris = new String[] {"/httpbin/ip", "/httpbin/user-agent", "/httpbin/headers"};

        final Future<AsyncClientEndpoint> future = requester.connect(target, Timeout.ofSeconds(5));
        final AsyncClientEndpoint clientEndpoint = future.get();

        final CountDownLatch latch = new CountDownLatch(requestUris.length);
        for (final String requestUri: requestUris) {
            clientEndpoint.execute(
                    new BasicRequestProducer(Methods.GET, target, requestUri),
                    new BasicResponseConsumer<>(new StringAsyncEntityConsumer()),
                    new FutureCallback<Message<HttpResponse, String>>() {

                        @Override
                        public void completed(final Message<HttpResponse, String> message) {
                            latch.countDown();
                            final HttpResponse response = message.getHead();
                            final String body = message.getBody();
                            System.out.println(requestUri + "->" + response.getCode());
                            System.out.println(body);
                        }

                        @Override
                        public void failed(final Exception ex) {
                            latch.countDown();
                            System.out.println(requestUri + "->" + ex);
                        }

                        @Override
                        public void cancelled() {
                            latch.countDown();
                            System.out.println(requestUri + " cancelled");
                        }

                    });
        }

        latch.await();

        // Manually release client endpoint when done !!!
        clientEndpoint.releaseAndDiscard();

        System.out.println("Shutting down I/O reactor");
        requester.initiateShutdown();
    }

}
