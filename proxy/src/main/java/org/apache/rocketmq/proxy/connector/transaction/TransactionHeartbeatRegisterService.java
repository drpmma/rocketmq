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
package org.apache.rocketmq.proxy.connector.transaction;

import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.common.ServiceThread;
import org.apache.rocketmq.common.protocol.heartbeat.HeartbeatData;
import org.apache.rocketmq.common.protocol.heartbeat.ProducerData;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.common.thread.ThreadPoolMonitor;
import org.apache.rocketmq.proxy.common.StartAndShutdown;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.config.ProxyConfig;
import org.apache.rocketmq.proxy.connector.ForwardProducer;
import org.apache.rocketmq.proxy.connector.route.MessageQueueWrapper;
import org.apache.rocketmq.proxy.connector.route.TopicRouteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransactionHeartbeatRegisterService implements StartAndShutdown {
    private static final Logger log = LoggerFactory.getLogger(TransactionHeartbeatRegisterService.class);

    private static final String TRANS_HEARTBEAT_CLIENT_ID = "rmq-proxy-producer-client";

    private final ForwardProducer forwardProducer;
    private final TopicRouteCache topicRouteCache;

    private ThreadPoolExecutor heartbeatExecutors;
    private final Map<String /* group */, Set<ClusterData>/* cluster list */> groupClusterData = new ConcurrentHashMap<>();
    private TxHeartbeatServiceThread txHeartbeatServiceThread;

    public TransactionHeartbeatRegisterService(ForwardProducer forwardProducer, TopicRouteCache topicRouteCache) {
        this.forwardProducer = forwardProducer;
        this.topicRouteCache = topicRouteCache;
    }

    public void addProducerGroup(String group, String topic) {
        try {
            MessageQueueWrapper messageQueue = this.topicRouteCache.getMessageQueue(topic);
            List<BrokerData> brokerDataList = messageQueue.getTopicRouteData().getBrokerDatas();

            if (brokerDataList != null) {
                for (BrokerData brokerData : brokerDataList) {
                    groupClusterData.compute(group, (groupName, clusterDataSet) -> {
                        if (clusterDataSet == null) {
                            clusterDataSet = Sets.newHashSet();
                        }
                        clusterDataSet.add(new ClusterData(brokerData.getCluster()));
                        return clusterDataSet;
                    });
                }
            }
        } catch (Exception e) {
            log.error("add producer group err in txHeartBeat. groupId: {}, err: {}", group, e);
        }
    }

    public void onProducerGroupOffline(String group) {
        groupClusterData.remove(group);
    }

    public void scanProducerHeartBeat() {
        Set<String> groupSet = groupClusterData.keySet();

        Map<String /* cluster */, List<HeartbeatData>> clusterHeartbeatData = new HashMap<>();
        for (String group : groupSet) {
            groupClusterData.computeIfPresent(group, (groupName, clusterDataSet) -> {
                if (clusterDataSet.isEmpty()) {
                    return null;
                }

                ProducerData producerData = new ProducerData();
                producerData.setGroupName(groupName);

                for (ClusterData clusterData : clusterDataSet) {
                    List<HeartbeatData> heartbeatDataList = clusterHeartbeatData.get(clusterData.cluster);
                    if (heartbeatDataList == null) {
                        heartbeatDataList = new ArrayList<>();
                    }

                    HeartbeatData heartbeatData;
                    if (heartbeatDataList.isEmpty()) {
                        heartbeatData = new HeartbeatData();
                        heartbeatData.setClientID(TRANS_HEARTBEAT_CLIENT_ID);
                        heartbeatDataList.add(heartbeatData);
                    } else {
                        heartbeatData = heartbeatDataList.get(heartbeatDataList.size() - 1);
                        if (heartbeatData.getProducerDataSet().size() >= ConfigurationManager.getProxyConfig().getTransactionHeartbeatBatchNum()) {
                            heartbeatData = new HeartbeatData();
                            heartbeatData.setClientID(TRANS_HEARTBEAT_CLIENT_ID);
                            heartbeatDataList.add(heartbeatData);
                        }
                    }

                    heartbeatData.getProducerDataSet().add(producerData);
                    clusterHeartbeatData.put(clusterData.cluster, heartbeatDataList);
                }

                if (clusterDataSet.isEmpty()) {
                    return null;
                }
                return clusterDataSet;
            });
        }

        if (clusterHeartbeatData.isEmpty()) {
            return;
        }
        Set<Map.Entry<String, List<HeartbeatData>>> clusterEntry = clusterHeartbeatData.entrySet();
        for (Map.Entry<String, List<HeartbeatData>> entry : clusterEntry) {
            sendHeartBeatToCluster(entry.getKey(), entry.getValue());
        }
    }

    protected void sendHeartBeatToCluster(String clusterName, List<HeartbeatData> heartbeatDataList) {
        if (heartbeatDataList == null) {
            return;
        }
        for (HeartbeatData heartbeatData : heartbeatDataList) {
            sendHeartBeatToCluster(clusterName, heartbeatData);
        }
    }

    protected void sendHeartBeatToCluster(String clusterName, HeartbeatData heartbeatData) {
        try {
            MessageQueueWrapper messageQueue =  this.topicRouteCache.getMessageQueue(clusterName);
            List<BrokerData> brokerDataList = messageQueue.getTopicRouteData().getBrokerDatas();
            if (brokerDataList == null) {
                return;
            }
            for (BrokerData brokerData : brokerDataList) {
                heartbeatExecutors.submit(() -> {
                    String brokerAddr = brokerData.selectBrokerAddr();
                    try {
                        this.forwardProducer.heartBeat(brokerAddr, heartbeatData);
                    } catch (Exception e) {
                        log.error("Send transactionHeartbeat to broker err. brokerAddr: {}", brokerAddr, e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("get broker add in cluster failed in tx. clusterName: {}", clusterName, e);
        }
    }

    static class ClusterData {
        private final String cluster;

        public ClusterData(String cluster) {
            this.cluster = cluster;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof ClusterData)) {
                return super.equals(obj);
            }

            ClusterData other = (ClusterData) obj;
            return cluster.equals(other.cluster);
        }

        @Override
        public int hashCode() {
            return cluster.hashCode();
        }
    }

    class TxHeartbeatServiceThread extends ServiceThread {

        @Override
        public String getServiceName() {
            return TxHeartbeatServiceThread.class.getName();
        }

        @Override
        public void run() {
            while (!this.isStopped()) {
                this.waitForRunning(TimeUnit.SECONDS.toMillis(ConfigurationManager.getProxyConfig().getTransactionHeartbeatPeriodSecond()));
            }
        }

        @Override
        protected void onWaitEnd() {
            scanProducerHeartBeat();
        }
    }

    @Override
    public void start() throws Exception {
        ProxyConfig proxyConfig = ConfigurationManager.getProxyConfig();
        txHeartbeatServiceThread = new TxHeartbeatServiceThread();

        txHeartbeatServiceThread.start();
        heartbeatExecutors = ThreadPoolMonitor.createAndMonitor(
            proxyConfig.getTransactionHeartbeatThreadPoolNums(),
            proxyConfig.getTransactionHeartbeatThreadPoolNums(),
            0L, TimeUnit.MILLISECONDS,
            "TransactionHeartbeatRegisterThread",
            proxyConfig.getTransactionHeartbeatThreadPoolQueueCapacity()
        );
    }

    @Override
    public void shutdown() throws Exception {
        txHeartbeatServiceThread.shutdown();
        heartbeatExecutors.shutdown();
    }
}
