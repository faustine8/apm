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
 *
 */

package org.apache.skywalking.apm.agent.core.remote;

import io.grpc.Channel;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolverRegistry;
import io.grpc.internal.DnsNameResolverProvider;
import io.grpc.netty.NettyChannelBuilder;

import java.util.LinkedList;
import java.util.List;

public class GRPCChannel {
    /**
     * origin channel
     */
    private final ManagedChannel originChannel; // 标准的网络链接
    private final Channel channelWithDecorators; // 附带了额外功能的 Channel

    private GRPCChannel(String host, int port, List<ChannelBuilder> channelBuilders,
                        List<ChannelDecorator> decorators) throws Exception {
        // 借助 Netty 建立底层连接
        ManagedChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port);

        NameResolverRegistry.getDefaultRegistry().register(new DnsNameResolverProvider());

        // 通过 Builder 构造 Channel
        for (ChannelBuilder builder : channelBuilders) {
            channelBuilder = builder.build(channelBuilder); // 这里调用的 sw 封装的 build, 主要设置加密传输或者明文传输
        }

        // 将构造后的 channel 赋值给 originChannel, 代表 originChannel 是一个可用的网络链接了
        this.originChannel = channelBuilder.build(); // 此处是调用的 原生对象的 build 方法

        // 将 originChannel 赋值给 channel
        Channel channel = originChannel;
        // 如果有装饰器, 就对 channel 进行装饰
        for (ChannelDecorator decorator : decorators) {
            channel = decorator.build(channel);
        }

        // 将构造完, 并且装饰完的 channel 交给 channelWithDecorators 保存
        channelWithDecorators = channel;
    }

    public static Builder newBuilder(String host, int port) {
        return new Builder(host, port);
    }

    public Channel getChannel() {
        return this.channelWithDecorators;
    }

    public boolean isTerminated() {
        return originChannel.isTerminated();
    }

    public void shutdownNow() {
        originChannel.shutdownNow();
    }

    public boolean isShutdown() {
        return originChannel.isShutdown();
    }

    public boolean isConnected() {
        return isConnected(false);
    }

    public boolean isConnected(boolean requestConnection) {
        return originChannel.getState(requestConnection) == ConnectivityState.READY;
    }

    public static class Builder {
        private final String host;
        private final int port;
        private final List<ChannelBuilder> channelBuilders;
        private final List<ChannelDecorator> decorators;

        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
            this.channelBuilders = new LinkedList<>();
            this.decorators = new LinkedList<>();
        }

        public Builder addChannelDecorator(ChannelDecorator interceptor) {
            this.decorators.add(interceptor);
            return this;
        }

        public GRPCChannel build() throws Exception {
            return new GRPCChannel(host, port, channelBuilders, decorators);
        }

        public Builder addManagedChannelBuilder(ChannelBuilder builder) {
            channelBuilders.add(builder);
            return this;
        }
    }
}
