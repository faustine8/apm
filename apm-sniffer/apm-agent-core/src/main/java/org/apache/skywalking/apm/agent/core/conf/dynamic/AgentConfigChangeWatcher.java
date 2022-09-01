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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 监听 agent 的某项配置的 值 的变化
 */
@Getter
public abstract class AgentConfigChangeWatcher {
    // Config key, should match KEY in the Table of Agent Configuration Properties.
    // 这个 key 来源于 agent 配置文件, 也就是说只有 agent 配置文件中合法的 key 才能在这里被使用
    private final String propertyKey;

    public AgentConfigChangeWatcher(String propertyKey) {
        this.propertyKey = propertyKey;
    }

    /**
     * Notify the watcher, the new value received.
     * 值发生变化时, 通知使用这个配置的那些服务
     *
     * @param value of new.
     */
    public abstract void notify(ConfigChangeEvent value);

    /**
     * @return current value of current config.
     */
    public abstract String value();

    @Override
    public String toString() {
        return "AgentConfigChangeWatcher{" +
            "propertyKey='" + propertyKey + '\'' +
            '}';
    }

    @Getter
    @RequiredArgsConstructor
    public static class ConfigChangeEvent {
        private final String newValue; // 变更后的新值
        private final EventType eventType; // 事件类型
    }

    public enum EventType {
        ADD, MODIFY, DELETE
    }
}
