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

package org.apache.rocketmq.proxy.service.relay;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelId;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import org.apache.rocketmq.common.constant.LoggerName;
import org.apache.rocketmq.common.message.MessageDecoder;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.body.ConsumeMessageDirectlyResult;
import org.apache.rocketmq.common.protocol.body.ConsumerRunningInfo;
import org.apache.rocketmq.common.protocol.header.CheckTransactionStateRequestHeader;
import org.apache.rocketmq.common.protocol.header.ConsumeMessageDirectlyResultRequestHeader;
import org.apache.rocketmq.common.protocol.header.GetConsumerRunningInfoRequestHeader;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;
import org.apache.rocketmq.proxy.common.ContextVariable;
import org.apache.rocketmq.proxy.common.ProxyContext;
import org.apache.rocketmq.proxy.service.transaction.TransactionId;
import org.apache.rocketmq.remoting.common.RemotingUtil;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public abstract class ProxyChannel extends AbstractChannel {
    private static final InternalLogger log = InternalLoggerFactory.getLogger(LoggerName.PROXY_LOGGER_NAME);
    protected final String remoteAddress;
    protected final SocketAddress remoteSocketAddress;
    protected final String localAddress;
    protected final SocketAddress localSocketAddress;

    protected final ProxyRelayService proxyRelayService;

    protected ProxyChannel(ProxyRelayService proxyRelayService, Channel parent, String remoteAddress, String localAddress) {
        super(parent);
        this.proxyRelayService = proxyRelayService;
        this.remoteAddress = remoteAddress;
        this.remoteSocketAddress = RemotingUtil.string2SocketAddress(remoteAddress);
        this.localAddress = localAddress;
        this.localSocketAddress = RemotingUtil.string2SocketAddress(localAddress);
    }

    protected ProxyChannel(ProxyRelayService proxyRelayService, Channel parent, ChannelId id, String remoteAddress, String localAddress) {
        super(parent, id);
        this.proxyRelayService = proxyRelayService;
        this.remoteAddress = remoteAddress;
        this.remoteSocketAddress = RemotingUtil.string2SocketAddress(remoteAddress);
        this.localAddress = localAddress;
        this.localSocketAddress = RemotingUtil.string2SocketAddress(localAddress);
    }

    @Override
    public ChannelFuture writeAndFlush(Object msg) {
        CompletableFuture<Void> processFuture = new CompletableFuture<>();

        try {
            if (msg instanceof RemotingCommand) {
                ProxyContext context = ProxyContext.create()
                    .withVal(ContextVariable.REMOTE_ADDRESS, remoteAddress)
                    .withVal(ContextVariable.REMOTE_ADDRESS, localAddress);
                RemotingCommand command = (RemotingCommand) msg;
                switch (command.getCode()) {
                    case RequestCode.CHECK_TRANSACTION_STATE: {
                        CheckTransactionStateRequestHeader header = (CheckTransactionStateRequestHeader) command.readCustomHeader();
                        MessageExt messageExt = MessageDecoder.decode(ByteBuffer.wrap(command.getBody()), true, false, false);
                        TransactionId transactionId = TransactionId.genByBrokerTransactionId(header.getBrokerName(),
                            header.getTransactionId(), messageExt.getCommitLogOffset(), messageExt.getQueueOffset());
                        processFuture = this.processCheckTransaction(header, messageExt, transactionId);
                        break;
                    }
                    case RequestCode.GET_CONSUMER_RUNNING_INFO: {
                        GetConsumerRunningInfoRequestHeader header = (GetConsumerRunningInfoRequestHeader) command.readCustomHeader();
                        CompletableFuture<ProxyRelayResult<ConsumerRunningInfo>> relayFuture = this.proxyRelayService.processGetConsumerRunningInfo(context, command, header);
                        processFuture = this.processGetConsumerRunningInfo(command, header, relayFuture);
                        break;
                    }
                    case RequestCode.CONSUME_MESSAGE_DIRECTLY: {
                        ConsumeMessageDirectlyResultRequestHeader header = (ConsumeMessageDirectlyResultRequestHeader) command.readCustomHeader();
                        MessageExt messageExt = MessageDecoder.decode(ByteBuffer.wrap(command.getBody()), true, false, false);
                        processFuture = this.processConsumeMessageDirectly(command, header, messageExt,
                            this.proxyRelayService.processConsumeMessageDirectly(context, command, header));
                        break;
                    }
                    default:
                        break;
                }
            } else {
                processFuture = processOtherMessage(msg);
            }
        } catch (Throwable t) {
            log.error("process failed. msg:{}", msg, t);
            processFuture.completeExceptionally(t);
        }

        DefaultChannelPromise promise = new DefaultChannelPromise(this, GlobalEventExecutor.INSTANCE);
        processFuture.thenAccept(ignore -> promise.setSuccess())
            .exceptionally(t -> {
                promise.setFailure(t);
                return null;
            });
        return promise;
    }

    protected abstract CompletableFuture<Void> processOtherMessage(Object msg);

    protected abstract CompletableFuture<Void> processCheckTransaction(CheckTransactionStateRequestHeader header,
        MessageExt messageExt, TransactionId transactionId);

    protected abstract CompletableFuture<Void> processGetConsumerRunningInfo(
        RemotingCommand command,
        GetConsumerRunningInfoRequestHeader header,
        CompletableFuture<ProxyRelayResult<ConsumerRunningInfo>> responseFuture);

    protected abstract CompletableFuture<Void> processConsumeMessageDirectly(
        RemotingCommand command,
        ConsumeMessageDirectlyResultRequestHeader header,
        MessageExt messageExt,
        CompletableFuture<ProxyRelayResult<ConsumeMessageDirectlyResult>> responseFuture);

    @Override
    public ChannelConfig config() {
        return null;
    }

    @Override
    public ChannelMetadata metadata() {
        return null;
    }

    @Override
    protected AbstractUnsafe newUnsafe() {
        return null;
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return false;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {

    }

    @Override
    protected void doDisconnect() throws Exception {

    }

    @Override
    protected void doClose() throws Exception {

    }

    @Override
    protected void doBeginRead() throws Exception {

    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {

    }

    @Override
    protected SocketAddress localAddress0() {
        return this.localSocketAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return this.remoteSocketAddress;
    }
}