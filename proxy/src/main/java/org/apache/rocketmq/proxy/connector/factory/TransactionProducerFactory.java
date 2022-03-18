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
package org.apache.rocketmq.proxy.connector.factory;

import org.apache.rocketmq.client.ClientConfig;
import org.apache.rocketmq.client.impl.MQClientAPIExtImpl;
import org.apache.rocketmq.proxy.connector.processor.ProxyClientRemotingProcessor;
import org.apache.rocketmq.proxy.connector.transaction.TransactionStateChecker;
import org.apache.rocketmq.remoting.RPCHook;

public class TransactionProducerFactory extends AbstractClientFactory<MQClientAPIExtImpl> {
    private final TransactionStateChecker transactionStateChecker;

    public TransactionProducerFactory(RPCHook rpcHook, TransactionStateChecker transactionStateChecker) {
        super(rpcHook);
        this.transactionStateChecker = transactionStateChecker;
    }

    @Override
    public MQClientAPIExtImpl newOne(String instanceName, RPCHook rpcHook, int bootstrapWorkerThreads) {
        ClientConfig clientConfig = new ClientConfig();
        clientConfig.setInstanceName(instanceName);

        return new MQClientAPIExtImpl(
            createNettyClientConfig(bootstrapWorkerThreads),
            new ProxyClientRemotingProcessor(this.transactionStateChecker),
            rpcHook,
            clientConfig
        );
    }

    @Override
    protected boolean tryStart(MQClientAPIExtImpl client) {
        client.start();
        return true;
    }

    @Override
    protected void shutdown(MQClientAPIExtImpl client) {
        client.shutdown();
    }
}