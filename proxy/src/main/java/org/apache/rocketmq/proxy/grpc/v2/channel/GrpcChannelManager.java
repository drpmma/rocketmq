/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.rocketmq.proxy.grpc.v2.channel;

import io.grpc.Context;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.common.ThreadFactoryImpl;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.proxy.common.StartAndShutdown;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.config.ProxyConfig;
import org.apache.rocketmq.proxy.service.relay.ProxyRelayResult;
import org.apache.rocketmq.proxy.service.relay.ProxyRelayService;

public class GrpcChannelManager implements StartAndShutdown {
    private final ProxyRelayService proxyRelayService;
    protected final ConcurrentMap<String /* group */, Map<String, GrpcClientChannel>/* clientId */> groupClientIdChannelMap = new ConcurrentHashMap<>();

    protected final AtomicLong nonceIdGenerator = new AtomicLong(0);
    protected final ConcurrentMap<String /* nonce */, ResultFuture> resultNonceFutureMap = new ConcurrentHashMap<>();

    protected final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryImpl("GrpcChannelManager_")
    );

    public GrpcChannelManager(ProxyRelayService proxyRelayService) {
        this.proxyRelayService = proxyRelayService;
    }

    protected void init() {
        this.scheduledExecutorService.scheduleAtFixedRate(
            this::scanExpireResultFuture,
            10,  10, TimeUnit.SECONDS
        );
    }

    public GrpcClientChannel createChannel(Context ctx, String group, String clientId) {
        this.groupClientIdChannelMap.compute(group, (groupKey, clientIdMap) -> {
            if (clientIdMap == null) {
                clientIdMap = new ConcurrentHashMap<>();
            }
            clientIdMap.computeIfAbsent(clientId, clientIdKey -> new GrpcClientChannel(proxyRelayService, this, ctx, group, clientId));
            return clientIdMap;
        });
        return getChannel(group, clientId);
    }

    public GrpcClientChannel getChannel(String group, String clientId) {
        Map<String, GrpcClientChannel> clientIdChannelMap = this.groupClientIdChannelMap.get(group);
        if (clientIdChannelMap == null) {
            return null;
        }
        return clientIdChannelMap.get(clientId);
    }

    public GrpcClientChannel removeChannel(String group, String clientId)  {
        AtomicReference<GrpcClientChannel> channelRef = new AtomicReference<>();
        this.groupClientIdChannelMap.computeIfPresent(group, (groupKey, clientIdMap) -> {
            channelRef.set(clientIdMap.remove(clientId));
            if (clientIdMap.isEmpty()) {
                return null;
            }
            return clientIdMap;
        });
        return channelRef.get();
    }

    public <T> String addResponseFuture(CompletableFuture<ProxyRelayResult<T>> responseFuture) {
        String nonce = this.nextNonce();
        this.resultNonceFutureMap.put(nonce, new ResultFuture<>(responseFuture));
        return nonce;
    }

    public <T> CompletableFuture<ProxyRelayResult<T>> getAndRemoveResponseFuture(String nonce) {
        ResultFuture<T> resultFuture = this.resultNonceFutureMap.remove(nonce);
        if (resultFuture != null) {
            return resultFuture.future;
        }
        return null;
    }

    protected String nextNonce() {
        return String.valueOf(this.nonceIdGenerator.getAndIncrement());
    }

    protected void scanExpireResultFuture() {
        ProxyConfig proxyConfig = ConfigurationManager.getProxyConfig();
        long timeOutMs = TimeUnit.SECONDS.toMillis(proxyConfig.getGrpcProxyRelayRequestTimeoutInSeconds());

        Set<String> nonceSet = this.resultNonceFutureMap.keySet();
        for (String nonce : nonceSet) {
            ResultFuture<?> resultFuture = this.resultNonceFutureMap.get(nonce);
            if (resultFuture == null) {
                continue;
            }
            if (System.currentTimeMillis() - resultFuture.createTime > timeOutMs) {
                resultFuture = this.resultNonceFutureMap.remove(nonce);
                if (resultFuture != null) {
                    resultFuture.future.complete(new ProxyRelayResult<>(ResponseCode.SYSTEM_BUSY, "call remote timeout", null));
                }
            }
        }
    }

    @Override
    public void shutdown() throws Exception {
        this.scheduledExecutorService.shutdown();
    }

    @Override
    public void start() throws Exception {

    }

    protected static class ResultFuture<T> {
        public CompletableFuture<ProxyRelayResult<T>> future;
        public long createTime = System.currentTimeMillis();

        public ResultFuture(CompletableFuture<ProxyRelayResult<T>> future) {
            this.future = future;
        }
    }
}