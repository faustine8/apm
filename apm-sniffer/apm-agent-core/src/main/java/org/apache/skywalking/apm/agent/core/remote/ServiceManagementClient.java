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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.commands.CommandService;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.jvm.LoadedLibraryCollector;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.os.OSUtil;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.management.v3.InstancePingPkg;
import org.apache.skywalking.apm.network.management.v3.InstanceProperties;
import org.apache.skywalking.apm.network.management.v3.ManagementServiceGrpc;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;

import static org.apache.skywalking.apm.agent.core.conf.Config.Collector.GRPC_UPSTREAM_TIMEOUT;

/**
 * 自报家门/打招呼
 *
 * 1. 将当前 AgentClient 的基本信息汇报给 OAP
 * 2. 和 OAP 保持心跳
 */
@DefaultImplementor
public class ServiceManagementClient implements BootService, Runnable, GRPCChannelListener {
    private static final ILog LOGGER = LogManager.getLogger(ServiceManagementClient.class);
    private static List<KeyStringValuePair> SERVICE_INSTANCE_PROPERTIES; // AgentClient 的信息

    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT; // 当前网络连接状态
    private volatile ManagementServiceGrpc.ManagementServiceBlockingStub managementServiceBlockingStub; // 网络服务
    private volatile ScheduledFuture<?> heartbeatFuture; // 心跳定时任务
    private volatile AtomicInteger sendPropertiesCounter = new AtomicInteger(0);

    @Override
    public void statusChanged(GRPCChannelStatus status) {
        // 收到 GRPCChannelListener 的状态变更通知, 看是否已经连接上了
        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            // 找到 GRPCChannelManager 服务, 拿到网络连接
            Channel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getChannel();
            // grpc 中的 stub 可以理解为你在 protobuf 中定义的 XxxService
            managementServiceBlockingStub = ManagementServiceGrpc.newBlockingStub(channel);
        } else {
            managementServiceBlockingStub = null;
        }
        this.status = status;
    }

    @Override
    public void prepare() {
        // 将自身注册为一个监听器
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);

        SERVICE_INSTANCE_PROPERTIES = new ArrayList<>();

        // 把配置文件中的 Agent Client 的信息放入集合, 等待发送
        for (String key : Config.Agent.INSTANCE_PROPERTIES.keySet()) { // 从配置文件的 instance_properties 中取出数据
            // 将取出的数据组装成 KeyStringValuePair, 再放进集合中
            SERVICE_INSTANCE_PROPERTIES.add(KeyStringValuePair.newBuilder()
                                                              .setKey(key)
                                                              .setValue(Config.Agent.INSTANCE_PROPERTIES.get(key))
                                                              .build());
        }
    }

    @Override
    public void boot() {
        heartbeatFuture = Executors.newSingleThreadScheduledExecutor(
            new DefaultNamedThreadFactory("ServiceManagementClient")
        ).scheduleAtFixedRate(
            new RunnableWithExceptionProtection(
                this,
                t -> LOGGER.error("unexpected exception.", t)
            ), 0, Config.Collector.HEARTBEAT_PERIOD,
            TimeUnit.SECONDS
        );
    }

    @Override
    public void onComplete() {
    }

    @Override
    public void shutdown() {
        heartbeatFuture.cancel(true);
    }

    @Override
    public void run() {
        LOGGER.debug("ServiceManagementClient running, status:{}.", status);

        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            try {
                if (managementServiceBlockingStub != null) {
                    if (Math.abs(sendPropertiesCounter.getAndAdd(1)) % Config.Collector.PROPERTIES_REPORT_PERIOD_FACTOR == 0) {

                        managementServiceBlockingStub
                            .withDeadlineAfter(GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS)
                            .reportInstanceProperties(InstanceProperties.newBuilder()
                                                                        .setService(Config.Agent.SERVICE_NAME)
                                                                        .setServiceInstance(Config.Agent.INSTANCE_NAME)
                                                                        .addAllProperties(OSUtil.buildOSInfo(
                                                                            Config.OsInfo.IPV4_LIST_SIZE))
                                                                        .addAllProperties(SERVICE_INSTANCE_PROPERTIES)
                                                                        .addAllProperties(LoadedLibraryCollector.buildJVMInfo())
                                                                        .build());
                    } else {
                        final Commands commands = managementServiceBlockingStub.withDeadlineAfter(
                            GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS
                        ).keepAlive(InstancePingPkg.newBuilder()
                                                   .setService(Config.Agent.SERVICE_NAME)
                                                   .setServiceInstance(Config.Agent.INSTANCE_NAME)
                                                   .build());

                        ServiceManager.INSTANCE.findService(CommandService.class).receiveCommand(commands);
                    }
                }
            } catch (Throwable t) {
                LOGGER.error(t, "ServiceManagementClient execute fail.");
                ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
            }
        }
    }
}
