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

package org.apache.skywalking.apm.agent.core.conf.dynamic;

import com.google.common.collect.Lists;
import io.grpc.Channel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.boot.DefaultImplementor;
import org.apache.skywalking.apm.agent.core.boot.DefaultNamedThreadFactory;
import org.apache.skywalking.apm.agent.core.boot.ServiceManager;
import org.apache.skywalking.apm.agent.core.commands.CommandService;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelListener;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelManager;
import org.apache.skywalking.apm.agent.core.remote.GRPCChannelStatus;
import org.apache.skywalking.apm.network.language.agent.v3.ConfigurationDiscoveryServiceGrpc;
import org.apache.skywalking.apm.network.language.agent.v3.ConfigurationSyncRequest;
import org.apache.skywalking.apm.network.common.v3.Commands;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;
import org.apache.skywalking.apm.network.trace.component.command.ConfigurationDiscoveryCommand;
import org.apache.skywalking.apm.util.RunnableWithExceptionProtection;
import org.apache.skywalking.apm.util.StringUtil;

import static org.apache.skywalking.apm.agent.core.conf.Config.Collector.GRPC_UPSTREAM_TIMEOUT;

@DefaultImplementor
public class ConfigurationDiscoveryService implements BootService, GRPCChannelListener {

    /**
     * UUID of the last return value.
     */
    private String uuid;
    private final Register register = new Register();

    private volatile int lastRegisterWatcherSize; // 上一次计算的 watcher 的数量

    private volatile ScheduledFuture<?> getDynamicConfigurationFuture; // 单线程任务
    private volatile GRPCChannelStatus status = GRPCChannelStatus.DISCONNECT;
    private volatile ConfigurationDiscoveryServiceGrpc.ConfigurationDiscoveryServiceBlockingStub configurationDiscoveryServiceBlockingStub;

    private static final ILog LOGGER = LogManager.getLogger(ConfigurationDiscoveryService.class);

    @Override
    public void statusChanged(final GRPCChannelStatus status) {
        // 当网络连接成功后, 创建当前 service (和前面所有的 GPRCChannelListener 类似)
        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            Channel channel = ServiceManager.INSTANCE.findService(GRPCChannelManager.class).getChannel();
            configurationDiscoveryServiceBlockingStub = ConfigurationDiscoveryServiceGrpc.newBlockingStub(channel);
        } else {
            configurationDiscoveryServiceBlockingStub = null;
        }
        this.status = status;
    }

    @Override
    public void prepare() throws Throwable {
        // 将自身注册为 GRPC 网络连接的监听器
        ServiceManager.INSTANCE.findService(GRPCChannelManager.class).addChannelListener(this);
    }

    @Override
    public void boot() throws Throwable {
        getDynamicConfigurationFuture = Executors.newSingleThreadScheduledExecutor(
            new DefaultNamedThreadFactory("ConfigurationDiscoveryService")
        ).scheduleAtFixedRate(
            new RunnableWithExceptionProtection(
                this::getAgentDynamicConfig, // 调度执行的任务
                t -> LOGGER.error("Sync config from OAP error.", t)
            ),
            Config.Collector.GET_AGENT_DYNAMIC_CONFIG_INTERVAL,
            Config.Collector.GET_AGENT_DYNAMIC_CONFIG_INTERVAL,
            TimeUnit.SECONDS
        );
    }

    @Override
    public void onComplete() throws Throwable {

    }

    @Override
    public void shutdown() throws Throwable {
        if (getDynamicConfigurationFuture != null) {
            getDynamicConfigurationFuture.cancel(true);
        }
    }

    /**
     * Register dynamic configuration watcher.
     *
     * @param watcher dynamic configuration watcher
     */
    public void registerAgentConfigChangeWatcher(AgentConfigChangeWatcher watcher) {
        WatcherHolder holder = new WatcherHolder(watcher);
        if (register.containsKey(holder.getKey())) {
            throw new IllegalStateException("Duplicate register, watcher=" + watcher);
        }
        register.put(holder.getKey(), holder);
    }

    /**
     * Process ConfigurationDiscoveryCommand and notify each configuration watcher.
     *
     * @param configurationDiscoveryCommand Describe dynamic configuration information
     */
    public void handleConfigurationDiscoveryCommand(ConfigurationDiscoveryCommand configurationDiscoveryCommand) {
        final String responseUuid = configurationDiscoveryCommand.getUuid();

        if (responseUuid != null && Objects.equals(this.uuid, responseUuid)) {
            return;
        }

        List<KeyStringValuePair> config = readConfig(configurationDiscoveryCommand);

        config.forEach(property -> {
            String propertyKey = property.getKey();
            WatcherHolder holder = register.get(propertyKey);
            if (holder != null) {
                AgentConfigChangeWatcher watcher = holder.getWatcher();
                String newPropertyValue = property.getValue();
                if (StringUtil.isBlank(newPropertyValue)) {
                    if (watcher.value() != null) {
                        // Notify watcher, the new value is null with delete event type.
                        watcher.notify(
                            new AgentConfigChangeWatcher.ConfigChangeEvent(
                                null, AgentConfigChangeWatcher.EventType.DELETE
                            ));
                    } else {
                        // Don't need to notify, stay in null.
                    }
                } else {
                    if (!newPropertyValue.equals(watcher.value())) {
                        watcher.notify(new AgentConfigChangeWatcher.ConfigChangeEvent(
                            newPropertyValue, AgentConfigChangeWatcher.EventType.MODIFY
                        ));
                    } else {
                        // Don't need to notify, stay in the same config value.
                    }
                }
            } else {
                LOGGER.warn("Config {} from OAP, doesn't match any watcher, ignore.", propertyKey);
            }
        });
        this.uuid = responseUuid;

        LOGGER.trace("Current configurations after the sync, configurations:{}", register.toString());
    }

    /**
     * Read the registered dynamic configuration, compare it with the dynamic configuration information returned by the
     * service, and complete the dynamic configuration that has been deleted on the OAP.
     *
     * @param configurationDiscoveryCommand Describe dynamic configuration information
     * @return Adapted dynamic configuration information
     */
    private List<KeyStringValuePair> readConfig(ConfigurationDiscoveryCommand configurationDiscoveryCommand) {
        Map<String, KeyStringValuePair> commandConfigs = configurationDiscoveryCommand.getConfig()
                                                                                      .stream()
                                                                                      .collect(Collectors.toMap(
                                                                                          KeyStringValuePair::getKey,
                                                                                          Function.identity()
                                                                                      ));
        List<KeyStringValuePair> configList = Lists.newArrayList();
        for (final String name : register.keys()) {
            KeyStringValuePair command = commandConfigs.getOrDefault(name, KeyStringValuePair.newBuilder()
                                                                                             .setKey(name)
                                                                                             .build());
            configList.add(command);
        }
        return configList;
    }

    /**
     * get agent dynamic config through gRPC.
     * 定期调度. 每调度一次, 就向服务端要一次 agent 的配置信息
     */
    private void getAgentDynamicConfig() {
        LOGGER.debug("ConfigurationDiscoveryService running, status:{}.", status);

        // 判断网络是否是连接状态的, 否则不做任何操作
        if (GRPCChannelStatus.CONNECTED.equals(status)) {
            try {
                ConfigurationSyncRequest.Builder builder = ConfigurationSyncRequest.newBuilder();
                builder.setService(Config.Agent.SERVICE_NAME); // 从配置文件中获取 serviceName 放入 Request 中

                // Some plugin will register watcher later.
                // 有些插件注册监听的时候会比较慢
                final int size = register.keys().size(); // 当前所有的 watcher
                if (lastRegisterWatcherSize != size) { // 如果上一次计算的 watcher 的数量和这一次计算的 watcher 的数量不相等 (代表有新的配置 key 注册进来)
                    // reset uuid, avoid the same uuid causing the configuration not to be updated.
                    // 重置 uuid, 避免同样的 uuid 导致配置没有被更新
                    uuid = null;
                    lastRegisterWatcherSize = size;
                }

                if (null != uuid) {
                    builder.setUuid(uuid);
                }

                if (configurationDiscoveryServiceBlockingStub != null) { // service 创建成功后
                    final Commands commands = configurationDiscoveryServiceBlockingStub.withDeadlineAfter(
                        GRPC_UPSTREAM_TIMEOUT, TimeUnit.SECONDS
                    ).fetchConfigurations(builder.build()); // 通过 grpc 方法获取 commands
                    ServiceManager.INSTANCE.findService(CommandService.class).receiveCommand(commands);
                }
            } catch (Throwable t) {
                LOGGER.error(t, "ConfigurationDiscoveryService execute fail.");
                ServiceManager.INSTANCE.findService(GRPCChannelManager.class).reportError(t);
            }
        }
    }

    /**
     * Local dynamic configuration center.
     */
    public static class Register {

        // WatcherHolder
        private final Map<String, WatcherHolder> register = new HashMap<>();

        private boolean containsKey(String key) {
            return register.containsKey(key);
        }

        private void put(String key, WatcherHolder holder) {
            register.put(key, holder);
        }

        public WatcherHolder get(String name) {
            return register.get(name);
        }

        public Set<String> keys() {
            return register.keySet();
        }

        @Override
        public String toString() {
            ArrayList<String> registerTableDescription = new ArrayList<>(register.size());
            register.forEach((key, holder) -> {
                AgentConfigChangeWatcher watcher = holder.getWatcher();
                registerTableDescription.add(new StringBuilder().append("key:")
                                                                .append(key)
                                                                .append("value(current):")
                                                                .append(watcher.value()).toString());
            });
            return registerTableDescription.stream().collect(Collectors.joining(",", "[", "]"));
        }
    }

    @Getter
    private static class WatcherHolder {

        // 监听 agent 配置项值变化的监听器
        private final AgentConfigChangeWatcher watcher;
        // agent 配置项的 key
        private final String key;

        public WatcherHolder(AgentConfigChangeWatcher watcher) {
            this.watcher = watcher;
            this.key = watcher.getPropertyKey();
        }
    }
}
