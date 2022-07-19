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

package org.apache.skywalking.apm.plugin.asf.dubbo;

import org.apache.skywalking.apm.agent.core.boot.PluginConfig;

/**
 * 插件配置类，目的是将 agent 参数的配置复制到当前类中
 */
public class DubboPluginConfig {

    /**
     * 所有的自定义插件配置类都需要使用 Plugin 内部类。
     *
     * 因为 Agent 配置文件关于插件的配置都在 skywalking.plugin.xxx 里面配置的
     */
    public static class Plugin {

        @PluginConfig(root = DubboPluginConfig.class)
        public static class Dubbo {

            // 当前配置项的key为： skywalking.plugin.dubbo.collect_consumer_arguments
            public static boolean COLLECT_CONSUMER_ARGUMENTS = false;

            public static int CONSUMER_ARGUMENTS_LENGTH_THRESHOLD = 256;

            public static boolean COLLECT_PROVIDER_ARGUMENTS = false;

            public static int PROVIDER_ARGUMENTS_LENGTH_THRESHOLD = 256;
        }
    }
}
