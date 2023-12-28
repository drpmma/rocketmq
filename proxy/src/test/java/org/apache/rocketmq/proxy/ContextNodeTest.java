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

package org.apache.rocketmq.proxy;

import org.apache.rocketmq.proxy.common.context.ContextNode;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ContextNodeTest {
    private ContextNode contextNode;

    @Test
    public void testWithValue() {
        String key = "key";
        String value = "value";
        contextNode = new ContextNode();
        ContextNode newContextNode = contextNode.withValue(key, value);
        assertThat(newContextNode.getValue(key, String.class)).isEqualTo(value);
        assertThat(newContextNode.getValue(key)).isEqualTo(value);

        assertThat(contextNode.getValue(key, String.class)).isNull();
    }
}