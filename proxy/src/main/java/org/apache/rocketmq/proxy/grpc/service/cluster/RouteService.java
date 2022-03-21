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
import apache.rocketmq.v1.Assignment;
import apache.rocketmq.v1.Broker;
import apache.rocketmq.v1.Endpoints;
import apache.rocketmq.v1.Partition;
import apache.rocketmq.v1.Permission;
import apache.rocketmq.v1.QueryAssignmentRequest;
import apache.rocketmq.v1.QueryAssignmentResponse;
import apache.rocketmq.v1.QueryRouteRequest;
import apache.rocketmq.v1.QueryRouteResponse;
import apache.rocketmq.v1.Resource;
import com.google.common.base.Preconditions;
import com.google.common.net.HostAndPort;
import com.google.rpc.Code;
import io.grpc.Context;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.common.constant.PermName;
import org.apache.rocketmq.common.protocol.route.BrokerData;
import org.apache.rocketmq.common.protocol.route.QueueData;
import org.apache.rocketmq.common.protocol.route.TopicRouteData;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.connector.ConnectorManager;
import org.apache.rocketmq.proxy.connector.route.MessageQueueWrapper;
import org.apache.rocketmq.proxy.connector.route.SelectableMessageQueue;
import org.apache.rocketmq.proxy.connector.route.TopicRouteHelper;
import org.apache.rocketmq.proxy.grpc.common.Converter;
import org.apache.rocketmq.proxy.grpc.common.ParameterConverter;
import org.apache.rocketmq.proxy.grpc.common.ProxyMode;
import org.apache.rocketmq.proxy.grpc.common.ResponseBuilder;
import org.apache.rocketmq.proxy.grpc.common.ResponseHook;

public class RouteService extends BaseService {
    private final ProxyMode mode;

    private volatile ParameterConverter<Endpoints, Endpoints> queryRouteEndpointConverter;
    private volatile ResponseHook<QueryRouteRequest, QueryRouteResponse> queryRouteHook = null;

    private volatile ParameterConverter<Endpoints, Endpoints> queryAssignmentEndpointConverter;
    private volatile AssignmentQueueSelector assignmentQueueSelector;
    private volatile ResponseHook<QueryAssignmentRequest, QueryAssignmentResponse> queryAssignmentHook = null;

    public RouteService(ProxyMode mode, ConnectorManager connectorManager) {
        super(connectorManager);
        Preconditions.checkArgument(ProxyMode.isClusterMode(mode) || ProxyMode.isLocalMode(mode));
        this.mode = mode;
        queryRouteEndpointConverter = (ctx, parameter) -> parameter;
        queryAssignmentEndpointConverter = (ctx, parameter) -> parameter;
        assignmentQueueSelector = new DefaultAssignmentQueueSelector(this.connectorManager.getTopicRouteCache());
    }

    public void setQueryRouteEndpointConverter(ParameterConverter<Endpoints, Endpoints> queryRouteEndpointConverter) {
        this.queryRouteEndpointConverter = queryRouteEndpointConverter;
    }

    public void setQueryRouteHook(ResponseHook<QueryRouteRequest, QueryRouteResponse> queryRouteHook) {
        this.queryRouteHook = queryRouteHook;
    }

    public void setQueryAssignmentEndpointConverter(
        ParameterConverter<Endpoints, Endpoints> queryAssignmentEndpointConverter) {
        this.queryAssignmentEndpointConverter = queryAssignmentEndpointConverter;
    }

    public void setAssignmentQueueSelector(AssignmentQueueSelector assignmentQueueSelector) {
        this.assignmentQueueSelector = assignmentQueueSelector;
    }

    public void setQueryAssignmentHook(
        ResponseHook<QueryAssignmentRequest, QueryAssignmentResponse> queryAssignmentHook) {
        this.queryAssignmentHook = queryAssignmentHook;
    }

    public CompletableFuture<QueryRouteResponse> queryRoute(Context ctx, QueryRouteRequest request) {
        CompletableFuture<QueryRouteResponse> future = new CompletableFuture<>();
        future.whenComplete((response, throwable) -> {
            if (queryRouteHook != null) {
                queryRouteHook.beforeResponse(request, response, throwable);
            }
        });

        try {
            MessageQueueWrapper messageQueueWrapper = this.connectorManager.getTopicRouteCache()
                .getMessageQueue(Converter.getResourceNameWithNamespace(request.getTopic()));
            TopicRouteData topicRouteData = messageQueueWrapper.getTopicRouteData();
            List<QueueData> queueDataList = topicRouteData.getQueueDatas();
            List<BrokerData> brokerDataList = topicRouteData.getBrokerDatas();

            List<Partition> partitionList = new ArrayList<>();
            if (ProxyMode.isClusterMode(mode.name())) {
                Endpoints resEndpoints = this.queryRouteEndpointConverter.convert(ctx, request.getEndpoints());
                if (resEndpoints == null || resEndpoints.getDefaultInstanceForType().equals(resEndpoints)) {
                    future.complete(QueryRouteResponse.newBuilder()
                        .setCommon(ResponseBuilder.buildCommon(Code.INVALID_ARGUMENT, "endpoint " +
                            request.getEndpoints() + " is invalidate"))
                        .build());
                    return future;
                }
                for (QueueData queueData : queueDataList) {
                    Broker broker = Broker.newBuilder()
                        .setName(queueData.getBrokerName())
                        .setId(0)
                        .setEndpoints(resEndpoints)
                        .build();

                    partitionList.addAll(genPartitionFromQueueData(queueData, request.getTopic(), broker));
                }
            }
            if (ProxyMode.isLocalMode(mode.name())) {
                Map<String, Map<Long, Broker>> brokerMap = buildBrokerMap(brokerDataList);

                for (QueueData queueData : queueDataList) {
                    String brokerName = queueData.getBrokerName();
                    Map<Long, Broker> brokerIdMap = brokerMap.get(brokerName);
                    if (brokerIdMap == null) {
                        break;
                    }
                    for (Broker broker : brokerIdMap.values()) {
                        partitionList.addAll(genPartitionFromQueueData(queueData, request.getTopic(), broker));
                    }
                }
            }

            QueryRouteResponse response = QueryRouteResponse.newBuilder()
                .setCommon(ResponseBuilder.buildCommon(Code.OK, Code.OK.name()))
                .addAllPartitions(partitionList)
                .build();
            future.complete(response);
        } catch (Throwable t) {
            if (TopicRouteHelper.isTopicNotExistError(t)) {
                future.complete(QueryRouteResponse.newBuilder()
                    .setCommon(ResponseBuilder.buildCommon(Code.NOT_FOUND, t.getMessage()))
                    .build());
            } else {
                future.completeExceptionally(t);
            }
        }
        return future;
    }

    protected static List<Partition> genPartitionFromQueueData(QueueData queueData, Resource topic, Broker broker) {
        List<Partition> partitionList = new ArrayList<>();

        int r = 0;
        int w = 0;
        int rw = 0;
        if (PermName.isWriteable(queueData.getPerm()) && PermName.isReadable(queueData.getPerm())) {
            rw = Math.min(queueData.getWriteQueueNums(), queueData.getReadQueueNums());
            r = queueData.getReadQueueNums() - rw;
            w = queueData.getWriteQueueNums() - rw;
        } else if (PermName.isWriteable(queueData.getPerm())) {
            w = queueData.getWriteQueueNums();
        } else if (PermName.isReadable(queueData.getPerm())) {
            r = queueData.getReadQueueNums();
        }

        // r here means readOnly queue nums, w means writeOnly queue nums, while rw means both readable and writable queue nums.
        int queueIdIndex = 0;
        for (int i = 0; i < r; i++) {
            Partition partition = Partition.newBuilder().setBroker(broker).setTopic(topic)
                .setId(queueIdIndex++)
                .setPermission(Permission.READ)
                .build();
            partitionList.add(partition);
        }

        for (int i = 0; i < w; i++) {
            Partition partition = Partition.newBuilder().setBroker(broker).setTopic(topic)
                .setId(queueIdIndex++)
                .setPermission(Permission.WRITE)
                .build();
            partitionList.add(partition);
        }

        for (int i = 0; i < rw; i++) {
            Partition partition = Partition.newBuilder().setBroker(broker).setTopic(topic)
                .setId(queueIdIndex++)
                .setPermission(Permission.READ_WRITE)
                .build();
            partitionList.add(partition);
        }

        return partitionList;
    }

    public CompletableFuture<QueryAssignmentResponse> queryAssignment(Context ctx, QueryAssignmentRequest request) {
        CompletableFuture<QueryAssignmentResponse> future = new CompletableFuture<>();
        future.whenComplete((response, throwable) -> {
            if (queryAssignmentHook != null) {
                queryAssignmentHook.beforeResponse(request, response, throwable);
            }
        });

        try {
            List<Assignment> assignments = new ArrayList<>();
            List<SelectableMessageQueue> messageQueueList = this.assignmentQueueSelector.getAssignment(ctx, request);
            if (ProxyMode.isLocalMode(mode)) {
                MessageQueueWrapper messageQueueWrapper = this.connectorManager.getTopicRouteCache()
                    .getMessageQueue(Converter.getResourceNameWithNamespace(request.getTopic()));
                TopicRouteData topicRouteData = messageQueueWrapper.getTopicRouteData();
                Map<String, Map<Long, Broker>> brokerMap = buildBrokerMap(topicRouteData.getBrokerDatas());
                for (SelectableMessageQueue messageQueue : messageQueueList) {
                    Map<Long, Broker> brokerIdMap = brokerMap.get(messageQueue.getBrokerName());
                    if (brokerIdMap != null) {
                        Broker broker = brokerIdMap.get(0L);

                        Partition defaultPartition = Partition.newBuilder()
                            .setTopic(request.getTopic())
                            .setId(-1)
                            .setPermission(Permission.READ_WRITE)
                            .setBroker(broker)
                            .build();

                        assignments.add(Assignment.newBuilder()
                            .setPartition(defaultPartition)
                            .build());
                    }
                }
            }
            if (ProxyMode.isClusterMode(mode)) {
                Endpoints resEndpoints = this.queryAssignmentEndpointConverter.convert(ctx, request.getEndpoints());
                if (resEndpoints == null || Endpoints.getDefaultInstance().equals(resEndpoints)) {
                    future.complete(QueryAssignmentResponse.newBuilder()
                        .setCommon(ResponseBuilder.buildCommon(Code.INVALID_ARGUMENT, "endpoint " +
                            request.getEndpoints() + " is invalidate"))
                        .build());
                    return future;
                }
                for (SelectableMessageQueue messageQueue : messageQueueList) {
                    Broker broker = Broker.newBuilder()
                        .setName(messageQueue.getBrokerName())
                        .setId(0)
                        .setEndpoints(resEndpoints)
                        .build();

                    Partition defaultPartition = Partition.newBuilder()
                        .setTopic(request.getTopic())
                        .setId(-1)
                        .setPermission(Permission.READ_WRITE)
                        .setBroker(broker)
                        .build();

                    assignments.add(Assignment.newBuilder()
                        .setPartition(defaultPartition)
                        .build());
                }
            }

            QueryAssignmentResponse response = QueryAssignmentResponse.newBuilder()
                .addAllAssignments(assignments)
                .setCommon(ResponseBuilder.buildCommon(Code.OK, Code.OK.name()))
                .build();
            future.complete(response);
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    private Map<String/*brokerName*/, Map<Long/*brokerID*/, Broker>> buildBrokerMap(List<BrokerData> brokerDataList) {
        Map<String, Map<Long, Broker>> brokerMap = new HashMap<>();
        for (BrokerData brokerData : brokerDataList) {
            Map<Long, Broker> brokerIdMap = new HashMap<>();
            String brokerName = brokerData.getBrokerName();
            for (Map.Entry<Long, String> entry : brokerData.getBrokerAddrs().entrySet()) {
                Long brokerId = entry.getKey();
                HostAndPort hostAndPort = HostAndPort.fromString(entry.getValue());
                Broker broker = Broker.newBuilder()
                    .setName(brokerName)
                    .setId(Math.toIntExact(brokerId))
                    .setEndpoints(Endpoints.newBuilder()
                        .setScheme(AddressScheme.IPv4)
                        .addAddresses(
                            Address.newBuilder()
                                .setPort(ConfigurationManager.getProxyConfig().getGrpcServerPort())
                                .setHost(hostAndPort.getHost())
                        )
                        .build())
                    .build();

                brokerIdMap.put(brokerId, broker);
            }
            brokerMap.put(brokerName, brokerIdMap);
        }
        return brokerMap;
    }
}