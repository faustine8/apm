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
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;

import static org.apache.skywalking.apm.agent.core.conf.Config.Collector.IS_RESOLVE_DNS_PERIODICALLY;

/**
 * 这个服务是 Agent 到 OAP 的大动脉, 也就是网络连接
 */
@DefaultImplementor
public class GRPCChannelManager implements BootService, Runnable {
    private static final ILog LOGGER = LogManager.getLogger(GRPCChannelManager.class);

    private volatile GRPCChannel managedChannel = null; // 网络链接
    private volatile ScheduledFuture<?> connectCheckFuture; // 网络连接状态定时检查任务
    private volatile boolean reconnect = true; // 当前网络链接是否需要重连
    private final Random random = new Random();
    private final List<GRPCChannelListener> listeners = Collections.synchronizedList(new LinkedList<>());
    private volatile List<String> grpcServers; // OAP 地址列表
    private volatile int selectedIdx = -1; // 上次选择的 后端地址 的索引
    private volatile int reconnectCount = 0;

    @Override
    public void prepare() {

    }

    @Override
    public void boot() {
        // 检查 OAP 地址
        if (Config.Collector.BACKEND_SERVICE.trim().length() == 0) {
            LOGGER.error("Collector server addresses are not set.");
            LOGGER.error("Agent will not uplink any data.");
            return;
        }
        grpcServers = Arrays.asList(Config.Collector.BACKEND_SERVICE.split(","));
        // 定时任务
        connectCheckFuture = Executors.newSingleThreadScheduledExecutor(
            new DefaultNamedThreadFactory("GRPCChannelManager") // 线程工厂
        ).scheduleAtFixedRate(
            new RunnableWithExceptionProtection( // 封装了 run 方法中添加 try ... catch
                this,
                t -> LOGGER.error("unexpected exception.", t)
            ), 0, Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL, TimeUnit.SECONDS
        );
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void shutdown() {
        if (connectCheckFuture != null) {
            connectCheckFuture.cancel(true);
        }
        if (managedChannel != null) {
            managedChannel.shutdownNow();
        }
        LOGGER.debug("Selected collector grpc service shutdown.");
    }

    @Override
    public void run() {
        LOGGER.debug("Selected collector grpc service running, reconnect:{}.", reconnect);
        if (IS_RESOLVE_DNS_PERIODICALLY && reconnect) { // 如果需要刷新 DNS 并且需要重连
            // 获取第一个后端服务地址
            String backendService = Config.Collector.BACKEND_SERVICE.split(",")[0];
            try {
                // 将后端服务地址拆成 域名和端口的形式
                String[] domainAndPort = backendService.split(":");

                List<String> newGrpcServers = Arrays
                        .stream(InetAddress.getAllByName(domainAndPort[0])) // 找到 domain 对应的所有的 IP
                        .map(InetAddress::getHostAddress) // 拿到所有的 IP 地址
                        .map(ip -> String.format("%s:%s", ip, domainAndPort[1]))
                        .collect(Collectors.toList());
                // 所谓刷新 DNS 其实就是根据 域名 获取所有的 IP 地址, 然后拼接上 Port, 组成后端服务地址列表

                grpcServers = newGrpcServers;
            } catch (Throwable t) {
                LOGGER.error(t, "Failed to resolve {} of backend service.", backendService);
            }
        }

        if (reconnect) { // 如果真的需要重连
            if (grpcServers.size() > 0) { // 并且确定有后端地址
                String server = "";
                try {
                    // 随机获取一个地址列表的索引
                    int index = Math.abs(random.nextInt()) % grpcServers.size();
                    if (index != selectedIdx) { // 如果获取到的索引和上次选择的地址的索引不一样
                        selectedIdx = index; // 更新上次选择的索引

                        server = grpcServers.get(index); // 获取新的连接的地址
                        String[] ipAndPort = server.split(":");

                        if (managedChannel != null) {
                            managedChannel.shutdownNow(); // 将出问题的连接 shutdown 掉
                        }

                        // 重新构建连接
                        managedChannel = GRPCChannel.newBuilder(ipAndPort[0], Integer.parseInt(ipAndPort[1]))
                                                    .addManagedChannelBuilder(new StandardChannelBuilder())
                                                    .addManagedChannelBuilder(new TLSChannelBuilder())
                                                    .addChannelDecorator(new AgentIDDecorator())
                                                    .addChannelDecorator(new AuthenticationDecorator())
                                                    .build();
                        // 通知其他所有使用到网络连接的 BootService, 网络连接已经可用了
                        notify(GRPCChannelStatus.CONNECTED);
                        reconnectCount = 0;
                        reconnect = false;
                    } else if (managedChannel.isConnected(++reconnectCount > Config.Agent.FORCE_RECONNECTION_PERIOD)) { // 如果获取的索引和上一次一致(很有可能总共就只有一个后端服务地址), 并且重连次数已经满了, 那就检查一下是否自动重连上了, 如果也没有, 那就 凉凉
                        // Reconnect to the same server is automatically done by GRPC,
                        // therefore we are responsible to check the connectivity and
                        // set the state and notify listeners
                        reconnectCount = 0;
                        notify(GRPCChannelStatus.CONNECTED);
                        reconnect = false;
                    }

                    return;
                } catch (Throwable t) {
                    LOGGER.error(t, "Create channel to {} fail.", server);
                }
            }

            LOGGER.debug(
                "Selected collector grpc service is not available. Wait {} seconds to retry",
                Config.Collector.GRPC_CHANNEL_CHECK_INTERVAL
            );
        }
    }

    public void addChannelListener(GRPCChannelListener listener) {
        listeners.add(listener);
    }

    public Channel getChannel() {
        return managedChannel.getChannel();
    }

    /**
     * If the given exception is triggered by network problem, connect in background.
     *
     * 如果网络链接发生了异常, 那就调用 reportError
     */
    public void reportError(Throwable throwable) {
        // 判断是否是网络异常
        if (isNetworkError(throwable)) {
            reconnect = true;
            notify(GRPCChannelStatus.DISCONNECT);
        }
    }

    private void notify(GRPCChannelStatus status) {
        for (GRPCChannelListener listener : listeners) {
            try {
                // 分别通知连接状态变更
                listener.statusChanged(status);
            } catch (Throwable t) {
                LOGGER.error(t, "Fail to notify {} about channel connected.", listener.getClass().getName());
            }
        }
    }

    /**
     *  判断是否是网络异常
     */
    private boolean isNetworkError(Throwable throwable) {
        // 根据异常的类型, 判断是否是网络异常
        if (throwable instanceof StatusRuntimeException) {
            // 获取到异常信息
            StatusRuntimeException statusRuntimeException = (StatusRuntimeException) throwable;
            // 根据获取到的异常信息中的异常状态, 判断是否是后面的任意一种, 确定是否是网络异常
            return statusEquals(
                statusRuntimeException.getStatus(), Status.UNAVAILABLE, Status.PERMISSION_DENIED,
                Status.UNAUTHENTICATED, Status.RESOURCE_EXHAUSTED, Status.UNKNOWN
            );
        }
        return false;
    }

    /**
     * 判断状态是否是 参数中的状态的任意一种
     */
    private boolean statusEquals(Status sourceStatus, Status... potentialStatus) {
        for (Status status : potentialStatus) {
            if (sourceStatus.getCode() == status.getCode()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }
}
