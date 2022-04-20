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

package org.apache.rocketmq.proxy.grpc.v2.adapter;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.apache.rocketmq.common.constant.LoggerName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResponseWriter {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.GRPC_LOGGER_NAME);

    public static <T> void write(StreamObserver<T> observer, final T response) {
        if (observer instanceof ServerCallStreamObserver) {
            if (response == null) {
                return;
            }

            final ServerCallStreamObserver<T> serverCallStreamObserver = (ServerCallStreamObserver<T>) observer;
            if (serverCallStreamObserver.isCancelled()) {
                log.warn("client has cancelled the request. response to write: {}", response);
                return;
            }

            log.debug("start to write response. response: {}", response);
            serverCallStreamObserver.onNext(response);
            serverCallStreamObserver.onCompleted();
        }
    }

    public static <T> void writeException(StreamObserver<T> observer, final Throwable e) {
        if (observer instanceof ServerCallStreamObserver) {
            if (null == e) {
                return;
            }

            final ServerCallStreamObserver<T> serverCallStreamObserver = (ServerCallStreamObserver<T>) observer;
            if (serverCallStreamObserver.isCancelled()) {
                log.warn("Client has cancelled the request. Exception to write", e);
                return;
            }

            log.debug("Start to write error response", e);
            serverCallStreamObserver.onError(e);
            serverCallStreamObserver.onCompleted();
        }
    }
}
