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

package org.apache.rocketmq.proxy.grpc.v2.adapter.handler;

import apache.rocketmq.v2.ReceiveMessageRequest;
import com.google.common.base.Stopwatch;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.header.ExtraInfoUtil;
import org.apache.rocketmq.common.protocol.header.PopMessageResponseHeader;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.proxy.channel.InvocationContext;
import org.apache.rocketmq.proxy.grpc.v2.adapter.GrpcConverter;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.RemotingSysResponseCode;

public class ReceiveMessageResponseHandler implements ResponseHandler<ReceiveMessageRequest, List<MessageExt>> {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.PROXY_LOGGER_NAME);
    private final String brokerName;
    private final boolean fifo;

    public ReceiveMessageResponseHandler(String brokerName, boolean fifo) {
        this.brokerName = brokerName;
        this.fifo = fifo;
    }

    @Override
    public void handle(RemotingCommand responseCommand,
        InvocationContext<ReceiveMessageRequest, List<MessageExt>> context) {
        CompletableFuture<List<MessageExt>> future = context.getResponse();

        long currentTimeInMillis = System.currentTimeMillis();
        long popCosts = currentTimeInMillis - context.getTimestamp();
        try {
            Stopwatch stopWatch = Stopwatch.createStarted();
            PopMessageResponseHeader responseHeader = (PopMessageResponseHeader) responseCommand.readCustomHeader();
            List<MessageExt> allMessageList = new ArrayList<>();

            ReceiveMessageRequest request = context.getRequest();
            if (responseCommand.getCode() == RemotingSysResponseCode.SUCCESS) {
                String topicName = GrpcConverter.wrapResourceWithNamespace(request.getMessageQueue().getTopic());
                ByteBuffer byteBuffer = ByteBuffer.wrap(responseCommand.getBody());
                List<MessageExt> msgFoundList = MessageDecoder.decodes(byteBuffer);

                Map<String, Long> startOffsetInfo;
                Map<String, List<Long>> msgOffsetInfo;
                Map<String, Integer> orderCountInfo;
                startOffsetInfo = ExtraInfoUtil.parseStartOffsetInfo(responseHeader.getStartOffsetInfo());
                msgOffsetInfo = ExtraInfoUtil.parseMsgOffsetInfo(responseHeader.getMsgOffsetInfo());
                orderCountInfo = ExtraInfoUtil.parseOrderCountInfo(responseHeader.getOrderCountInfo());
                Map<String/*topicMark@queueId*/, List<Long>/*msg queueOffset*/> sortMap = new HashMap<>(16);
                for (MessageExt messageExt : msgFoundList) {
                    String key = ExtraInfoUtil.getStartOffsetInfoMapKey(messageExt.getTopic(), messageExt.getQueueId());
                    if (!sortMap.containsKey(key)) {
                        sortMap.put(key, new ArrayList<>(4));
                    }
                    sortMap.get(key).add(messageExt.getQueueOffset());
                }
                Map<String, String> map = new HashMap<>(5);
                for (MessageExt messageExt : msgFoundList) {
                    if (startOffsetInfo == null) {
                        // we should set the check point info to extraInfo field , if the command is popMsg
                        // find pop ck offset
                        String key = messageExt.getTopic() + messageExt.getQueueId();
                        if (!map.containsKey(messageExt.getTopic() + messageExt.getQueueId())) {
                            String extraInfo = ExtraInfoUtil.buildExtraInfo(
                                messageExt.getQueueOffset(),
                                responseHeader.getPopTime(),
                                responseHeader.getInvisibleTime(),
                                responseHeader.getReviveQid(),
                                messageExt.getTopic(), brokerName,
                                messageExt.getQueueId()
                            );
                            map.put(key, extraInfo);
                        }
                        messageExt.getProperties().put(MessageConst.PROPERTY_POP_CK,
                            map.get(key) + MessageConst.KEY_SEPARATOR + messageExt.getQueueOffset());
                    } else {
                        String key = ExtraInfoUtil.getStartOffsetInfoMapKey(messageExt.getTopic(),
                            messageExt.getQueueId());
                        int index = sortMap.get(key).indexOf(messageExt.getQueueOffset());
                        Long msgQueueOffset = msgOffsetInfo.get(key).get(index);
                        if (msgQueueOffset != messageExt.getQueueOffset()) {
                            log.warn("Queue offset[{}] of msg is strange, not equal to the stored in msg, {}",
                                msgQueueOffset, messageExt);
                        }
                        String extraInfo = ExtraInfoUtil.buildExtraInfo(
                            startOffsetInfo.get(key),
                            responseHeader.getPopTime(),
                            responseHeader.getInvisibleTime(),
                            responseHeader.getReviveQid(),
                            messageExt.getTopic(),
                            brokerName,
                            messageExt.getQueueId(),
                            msgQueueOffset
                        );
                        messageExt.setQueueOffset(msgQueueOffset);
                        messageExt.getProperties().put(MessageConst.PROPERTY_POP_CK, extraInfo);
                        if (fifo && orderCountInfo != null) {
                            Integer count = orderCountInfo.get(key);
                            if (count != null && count > 0) {
                                messageExt.setReconsumeTimes(count);
                            }
                        }
                    }
                    messageExt.setTopic(topicName);
                    messageExt.setBrokerName(brokerName);
                    messageExt.getProperties().computeIfAbsent(MessageConst.PROPERTY_FIRST_POP_TIME,
                        k -> String.valueOf(responseHeader.getPopTime()));
                }
                allMessageList.addAll(msgFoundList);
            }
            long elapsed = stopWatch.stop().elapsed(TimeUnit.MILLISECONDS);
            log.debug("Translating remoting response to gRPC response costs {}ms. Duration request received: {}", elapsed, popCosts);
            future.complete(allMessageList);
        } catch (Exception e) {
            log.error("Unexpected exception raised when handle pop remoting command", e);
            future.completeExceptionally(e);
        }
    }
}