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

package org.apache.rocketmq.proxy.grpc.service.cluster;

import apache.rocketmq.v1.Address;
import apache.rocketmq.v1.AddressScheme;
import apache.rocketmq.v1.Broker;
import apache.rocketmq.v1.Endpoints;
import apache.rocketmq.v1.Partition;
import apache.rocketmq.v1.Permission;
import apache.rocketmq.v1.QueryAssignmentRequest;
import apache.rocketmq.v1.QueryAssignmentResponse;
import apache.rocketmq.v1.QueryRouteRequest;
import apache.rocketmq.v1.QueryRouteResponse;
import apache.rocketmq.v1.Resource;
import com.google.common.net.HostAndPort;
import com.google.rpc.Code;
import io.grpc.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.common.protocol.route.QueueData;
import org.apache.rocketmq.proxy.grpc.adapter.ProxyMode;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;
import org.apache.rocketmq.proxy.connector.route.MessageQueueWrapper;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class RouteServiceTest extends BaseServiceTest {
    private String brokerAddress = "127.0.0.1:10911";
    public static final String BROKER_NAME = "brokerName";
    public static final String NAMESPACE = "namespace";
    public static final String TOPIC = "topic";
    public static final Broker MOCK_BROKER = Broker.newBuilder().setName(BROKER_NAME).build();
    public static final Resource MOCK_TOPIC = Resource.newBuilder()
        .setName(TOPIC)
        .setResourceNamespace(NAMESPACE)
        .build();

    @Override
    public void beforeEach() throws Exception {
        TopicRouteData routeData = new TopicRouteData();

        List<BrokerData> brokerDataList = new ArrayList<>();
        BrokerData brokerData = new BrokerData();
        brokerData.setCluster("cluster");
        brokerData.setBrokerName("brokerName");
        HashMap<Long, String> brokerAddrs = new HashMap<Long, String>() {{
            put(0L, brokerAddress);
        }};
        brokerData.setBrokerAddrs(brokerAddrs);
        brokerDataList.add(brokerData);

        List<QueueData> queueDataList = new ArrayList<>();
        QueueData queueData = new QueueData();
        queueData.setPerm(6);
        queueData.setWriteQueueNums(8);
        queueData.setReadQueueNums(8);
        queueData.setBrokerName("brokerName");
        queueDataList.add(queueData);

        routeData.setBrokerDatas(brokerDataList);
        routeData.setQueueDatas(queueDataList);

        MessageQueueWrapper messageQueueWrapper = new MessageQueueWrapper("topic", routeData);
        when(this.topicRouteCache.getMessageQueue("topic")).thenReturn(messageQueueWrapper);

        when(this.topicRouteCache.getMessageQueue("notExistTopic")).thenThrow(new MQClientException(ResponseCode.TOPIC_NOT_EXIST, ""));
    }

    @Test
    public void testGenPartitionFromQueueData() throws Exception {
        // test queueData with 8 read queues, 8 write queues, and rw permission, expect 8 rw queues.
        QueueData queueDataWith8R8WPermRW = mockQueueData(8, 8, PermName.PERM_READ | PermName.PERM_WRITE);
        List<Partition> partitionWith8R8WPermRW = RouteService.genPartitionFromQueueData(queueDataWith8R8WPermRW, MOCK_TOPIC, MOCK_BROKER);
        assertThat(partitionWith8R8WPermRW.size()).isEqualTo(8);
        assertThat(partitionWith8R8WPermRW.stream().filter(a -> a.getPermission() == Permission.READ_WRITE).count()).isEqualTo(8);
        assertThat(partitionWith8R8WPermRW.stream().filter(a -> a.getPermission() == Permission.READ).count()).isEqualTo(0);
        assertThat(partitionWith8R8WPermRW.stream().filter(a -> a.getPermission() == Permission.WRITE).count()).isEqualTo(0);

        // test queueData with 8 read queues, 8 write queues, and read only permission, expect 8 read only queues.
        QueueData queueDataWith8R8WPermR = mockQueueData(8, 8, PermName.PERM_READ);
        List<Partition> partitionWith8R8WPermR = RouteService.genPartitionFromQueueData(queueDataWith8R8WPermR, MOCK_TOPIC, MOCK_BROKER);
        assertThat(partitionWith8R8WPermR.size()).isEqualTo(8);
        assertThat(partitionWith8R8WPermR.stream().filter(a -> a.getPermission() == Permission.READ).count()).isEqualTo(8);
        assertThat(partitionWith8R8WPermR.stream().filter(a -> a.getPermission() == Permission.READ_WRITE).count()).isEqualTo(0);
        assertThat(partitionWith8R8WPermR.stream().filter(a -> a.getPermission() == Permission.WRITE).count()).isEqualTo(0);

        // test queueData with 8 read queues, 8 write queues, and write only permission, expect 8 write only queues.
        QueueData queueDataWith8R8WPermW = mockQueueData(8, 8, PermName.PERM_WRITE);
        List<Partition> partitionWith8R8WPermW = RouteService.genPartitionFromQueueData(queueDataWith8R8WPermW, MOCK_TOPIC, MOCK_BROKER);
        assertThat(partitionWith8R8WPermW.size()).isEqualTo(8);
        assertThat(partitionWith8R8WPermW.stream().filter(a -> a.getPermission() == Permission.WRITE).count()).isEqualTo(8);
        assertThat(partitionWith8R8WPermW.stream().filter(a -> a.getPermission() == Permission.READ_WRITE).count()).isEqualTo(0);
        assertThat(partitionWith8R8WPermW.stream().filter(a -> a.getPermission() == Permission.READ).count()).isEqualTo(0);

        // test queueData with 8 read queues, 0 write queues, and rw permission, expect 8 read only queues.
        QueueData queueDataWith8R0WPermRW = mockQueueData(8, 0, PermName.PERM_READ | PermName.PERM_WRITE);
        List<Partition> partitionWith8R0WPermRW = RouteService.genPartitionFromQueueData(queueDataWith8R0WPermRW, MOCK_TOPIC, MOCK_BROKER);
        assertThat(partitionWith8R0WPermRW.size()).isEqualTo(8);
        assertThat(partitionWith8R0WPermRW.stream().filter(a -> a.getPermission() == Permission.READ).count()).isEqualTo(8);
        assertThat(partitionWith8R0WPermRW.stream().filter(a -> a.getPermission() == Permission.READ_WRITE).count()).isEqualTo(0);
        assertThat(partitionWith8R0WPermRW.stream().filter(a -> a.getPermission() == Permission.WRITE).count()).isEqualTo(0);

        // test queueData with 4 read queues, 8 write queues, and rw permission, expect 4 rw queues and  4 write only queues.
        QueueData queueDataWith4R8WPermRW = mockQueueData(4, 8, PermName.PERM_READ | PermName.PERM_WRITE);
        List<Partition> partitionWith4R8WPermRW = RouteService.genPartitionFromQueueData(queueDataWith4R8WPermRW, MOCK_TOPIC, MOCK_BROKER);
        assertThat(partitionWith4R8WPermRW.size()).isEqualTo(8);
        assertThat(partitionWith4R8WPermRW.stream().filter(a -> a.getPermission() == Permission.WRITE).count()).isEqualTo(4);
        assertThat(partitionWith4R8WPermRW.stream().filter(a -> a.getPermission() == Permission.READ_WRITE).count()).isEqualTo(4);
        assertThat(partitionWith4R8WPermRW.stream().filter(a -> a.getPermission() == Permission.READ).count()).isEqualTo(0);

    }

    private QueueData mockQueueData(int r, int w, int perm) {
        QueueData queueData = new QueueData();
        queueData.setBrokerName(BROKER_NAME);
        queueData.setReadQueueNums(r);
        queueData.setWriteQueueNums(w);
        queueData.setPerm(perm);
        return queueData;
    }

    @Test
    public void testLocalModeQueryRoute() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.LOCAL, this.connectorManager);
        CompletableFuture<QueryRouteResponse> future = routeService.queryRoute(Context.current(), QueryRouteRequest.newBuilder()
            .setEndpoints(Endpoints.newBuilder()
                .addAddresses(Address.newBuilder()
                    .setPort(80)
                    .setHost("host")
                    .build())
                .setScheme(AddressScheme.DOMAIN_NAME)
                .build())
            .setTopic(Resource.newBuilder()
                .setName("topic")
                .build())
            .build());
        QueryRouteResponse response = future.get();
        assertEquals(Code.OK.getNumber(), response.getCommon().getStatus().getCode());
        assertEquals(8, response.getPartitionsCount());
        assertEquals(HostAndPort.fromString(brokerAddress).getHost(), response.getPartitions(0).getBroker()
            .getEndpoints().getAddresses(0).getHost());
    }

    @Test
    public void testQueryRouteWithInvalidEndpoints() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.CLUSTER, this.connectorManager);

        CompletableFuture<QueryRouteResponse> future = routeService.queryRoute(Context.current(), QueryRouteRequest.newBuilder()
            .setTopic(Resource.newBuilder()
                .setName("topic")
                .build())
            .build());

        QueryRouteResponse response = future.get();
        assertEquals(Code.INVALID_ARGUMENT.getNumber(), response.getCommon().getStatus().getCode());
    }

    @Test
    public void testQueryRoute() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.CLUSTER, this.connectorManager);

        CompletableFuture<QueryRouteResponse> future = routeService.queryRoute(Context.current(), QueryRouteRequest.newBuilder()
            .setEndpoints(Endpoints.newBuilder()
                .addAddresses(Address.newBuilder()
                    .setPort(80)
                    .setHost("host")
                    .build())
                .setScheme(AddressScheme.DOMAIN_NAME)
                .build())
            .setTopic(Resource.newBuilder()
                .setName("topic")
                .build())
            .build());

        QueryRouteResponse response = future.get();
        assertEquals(Code.OK.getNumber(), response.getCommon().getStatus().getCode());
        assertEquals(8, response.getPartitionsCount());
        assertEquals("host", response.getPartitions(0).getBroker()
            .getEndpoints().getAddresses(0).getHost());
    }

    @Test
    public void testQueryRouteWhenTopicNotExist() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.CLUSTER, this.connectorManager);

        CompletableFuture<QueryRouteResponse> future = routeService.queryRoute(Context.current(), QueryRouteRequest.newBuilder()
            .setEndpoints(Endpoints.newBuilder()
                .addAddresses(Address.newBuilder()
                    .setPort(80)
                    .setHost("host")
                    .build())
                .setScheme(AddressScheme.DOMAIN_NAME)
                .build())
            .setTopic(Resource.newBuilder()
                .setName("notExistTopic")
                .build())
            .build());

        QueryRouteResponse response = future.get();
        assertEquals(Code.NOT_FOUND.getNumber(), response.getCommon().getStatus().getCode());
    }

    @Test
    public void testQueryAssignmentInvalidEndpoints() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.CLUSTER, this.connectorManager);

        CompletableFuture<QueryAssignmentResponse> future = routeService.queryAssignment(Context.current(), QueryAssignmentRequest.newBuilder()
            .setTopic(
                Resource.newBuilder()
                    .setName("topic")
                    .build()
            )
            .build());

        QueryAssignmentResponse response = future.get();
        assertEquals(Code.INVALID_ARGUMENT.getNumber(), response.getCommon().getStatus().getCode());
    }

    @Test
    public void testLocalModeQueryAssignment() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.LOCAL, this.connectorManager);

        CompletableFuture<QueryAssignmentResponse> future = routeService.queryAssignment(Context.current(), QueryAssignmentRequest.newBuilder()
            .setEndpoints(Endpoints.newBuilder()
                .addAddresses(Address.newBuilder()
                    .setPort(80)
                    .setHost("host")
                    .build())
                .setScheme(AddressScheme.DOMAIN_NAME)
                .build())
            .setTopic(Resource.newBuilder()
                .setName("topic")
                .build())
            .setGroup(Resource.newBuilder()
                .setName("group")
                .build())
            .setClientId("clientId")
            .build());

        QueryAssignmentResponse response = future.get();
        assertEquals(Code.OK.getNumber(), response.getCommon().getStatus().getCode());
        assertEquals(1, response.getAssignmentsCount());
        assertEquals("brokerName", response.getAssignments(0).getPartition().getBroker().getName());
        assertEquals(HostAndPort.fromString(brokerAddress).getHost(), response.getAssignments(0).getPartition().getBroker().getEndpoints().getAddresses(0).getHost());
    }

    @Test
    public void testQueryAssignment() throws Exception {
        RouteService routeService = new RouteService(ProxyMode.CLUSTER, this.connectorManager);

        CompletableFuture<QueryAssignmentResponse> future = routeService.queryAssignment(Context.current(), QueryAssignmentRequest.newBuilder()
            .setEndpoints(Endpoints.newBuilder()
                .addAddresses(Address.newBuilder()
                    .setPort(80)
                    .setHost("host")
                    .build())
                .setScheme(AddressScheme.DOMAIN_NAME)
                .build())
            .setTopic(Resource.newBuilder()
                .setName("topic")
                .build())
            .setGroup(Resource.newBuilder()
                .setName("group")
                .build())
            .setClientId("clientId")
            .build());

        QueryAssignmentResponse response = future.get();
        assertEquals(Code.OK.getNumber(), response.getCommon().getStatus().getCode());
        assertEquals(1, response.getAssignmentsCount());
        assertEquals("brokerName", response.getAssignments(0).getPartition().getBroker().getName());
        assertEquals("host", response.getAssignments(0).getPartition().getBroker().getEndpoints().getAddresses(0).getHost());
    }

} 
