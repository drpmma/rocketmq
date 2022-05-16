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
package org.apache.rocketmq.proxy.service;

import org.apache.rocketmq.broker.BrokerController;
import org.apache.rocketmq.broker.client.ConsumerManager;
import org.apache.rocketmq.broker.client.ProducerManager;
import org.apache.rocketmq.proxy.common.AbstractStartAndShutdown;
import org.apache.rocketmq.proxy.service.message.MessageService;
import org.apache.rocketmq.proxy.service.out.ProxyOutService;
import org.apache.rocketmq.proxy.service.route.TopicRouteService;
import org.apache.rocketmq.proxy.service.transaction.TransactionService;
import org.apache.rocketmq.remoting.RPCHook;

public abstract class ServiceManager extends AbstractStartAndShutdown {

    public static ServiceManager createForLocalMode(BrokerController brokerController) {
        return createForLocalMode(brokerController, null);
    }

    public static ServiceManager createForLocalMode(BrokerController brokerController, RPCHook rpcHook) {
        return new LocalServiceManager(brokerController, rpcHook);
    }

    public static ServiceManager createForClusterMode() {
        return createForClusterMode(null);
    }

    public static ServiceManager createForClusterMode(RPCHook rpcHook) {
        return new ClusterServiceManager(rpcHook);
    }

    public abstract MessageService getMessageService();

    public abstract TopicRouteService getTopicRouteService();

    public abstract ProducerManager getProducerManager();

    public abstract ConsumerManager getConsumerManager();

    public abstract TransactionService getTransactionService();

    public abstract ProxyOutService getProxyOutService();
}
