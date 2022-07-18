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

package org.apache.skywalking.apm.agent.core.conf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.skywalking.apm.agent.core.boot.AgentPackageNotFoundException;
import org.apache.skywalking.apm.agent.core.boot.AgentPackagePath;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.logging.core.JsonLogResolver;
import org.apache.skywalking.apm.agent.core.logging.core.PatternLogResolver;
import org.apache.skywalking.apm.util.ConfigInitializer;
import org.apache.skywalking.apm.util.PropertyPlaceholderHelper;
import org.apache.skywalking.apm.util.StringUtil;

/**
 * The <code>SnifferConfigInitializer</code> initializes all configs in several way.
 */
public class SnifferConfigInitializer {
    private static ILog LOGGER = LogManager.getLogger(SnifferConfigInitializer.class);
    private static final String SPECIFIED_CONFIG_PATH = "skywalking_config";
    private static final String DEFAULT_CONFIG_FILE_NAME = "/config/agent.config";
    private static final String ENV_KEY_PREFIX = "skywalking.";
    private static Properties AGENT_SETTINGS;
    private static boolean IS_INIT_COMPLETED = false;

    /**
     * If the specified agent config path is set, the agent will try to locate the specified agent config. If the
     * specified agent config path is not set , the agent will try to locate `agent.config`, which should be in the
     * /config directory of agent package.
     * <p>
     * Also try to override the config by system.properties. All the keys in this place should start with {@link
     * #ENV_KEY_PREFIX}. e.g. in env `skywalking.agent.service_name=yourAppName` to override `agent.service_name` in
     * config file.
     * <p>
     * At the end, `agent.service_name` and `collector.servers` must not be blank.
     */
    public static void initializeCoreConfig(String agentOptions) {
        AGENT_SETTINGS = new Properties();
        // 将配置文件加载成输入流
        try (final InputStreamReader configFileStream = loadConfig()) {
            // 加载进 properties 中
            AGENT_SETTINGS.load(configFileStream);
            for (String key : AGENT_SETTINGS.stringPropertyNames()) {
                String value = (String) AGENT_SETTINGS.get(key);
                // 替换配置文件中的配置项的占位符
                AGENT_SETTINGS.put(key, PropertyPlaceholderHelper.INSTANCE.replacePlaceholders(value, AGENT_SETTINGS));
            }

        } catch (Exception e) {
            LOGGER.error(e, "Failed to read the config file, skywalking is going to run in default config.");
        }

        try {
            // 用环境变量中的值，替换配置文件中配置的值
            // 环境变量中的配置必须以 skywalking 开头
            overrideConfigBySystemProp();
        } catch (Exception e) {
            LOGGER.error(e, "Failed to read the system properties.");
        }

        // 解析 agent 参数
        agentOptions = StringUtil.trim(agentOptions, ',');
        if (!StringUtil.isEmpty(agentOptions)) {
            try {
                agentOptions = agentOptions.trim();
                LOGGER.info("Agent options is {}.", agentOptions);

                // 使用 agent 参数替换配置文件中配置的值
                overrideConfigByAgentOptions(agentOptions);
            } catch (Exception e) {
                LOGGER.error(e, "Failed to parse the agent options, val is {}.", agentOptions);
            }
        }

        // 将 Properties 中的配置项赋值到 Config 类中
        initializeConfig(Config.class);
        // reconfigure logger after config initialization
        // 配置日志
        configureLogger();
        LOGGER = LogManager.getLogger(SnifferConfigInitializer.class);

        // 检查 service_name
        if (StringUtil.isEmpty(Config.Agent.SERVICE_NAME)) {
            throw new ExceptionInInitializerError("`agent.service_name` is missing.");
        }
        // 检查 oap server 地址
        if (StringUtil.isEmpty(Config.Collector.BACKEND_SERVICE)) {
            throw new ExceptionInInitializerError("`collector.backend_service` is missing.");
        }
        // 容错。如果 Peer 的长度配置短于 3， 则默认使用 200 作为长度。 // Peer 可以理解为记录调用的地址。
        if (Config.Plugin.PEER_MAX_LENGTH <= 3) {
            LOGGER.warn(
                "PEER_MAX_LENGTH configuration:{} error, the default value of 200 will be used.",
                Config.Plugin.PEER_MAX_LENGTH
            );
            Config.Plugin.PEER_MAX_LENGTH = 200;
        }

        // 标识配置加载完成
        IS_INIT_COMPLETED = true;
    }

    /**
     * Initialize field values of any given config class.
     *
     * @param configClass to host the settings for code access.
     */
    public static void initializeConfig(Class configClass) {
        if (AGENT_SETTINGS == null) {
            LOGGER.error("Plugin configs have to be initialized after core config initialization.");
            return;
        }
        try {
            // 本质就是将 AGENT_SETTINGS 配置中的同名参数拷贝到 configClass 中
            ConfigInitializer.initialize(AGENT_SETTINGS, configClass);
        } catch (IllegalAccessException e) {
            LOGGER.error(e,
                         "Failed to set the agent settings {}"
                             + " to Config={} ",
                         AGENT_SETTINGS, configClass
            );
        }
    }

    private static void overrideConfigByAgentOptions(String agentOptions) throws IllegalArgumentException {
        for (List<String> terms : parseAgentOptions(agentOptions)) {
            if (terms.size() != 2) {
                throw new IllegalArgumentException("[" + terms + "] is not a key-value pair.");
            }
            AGENT_SETTINGS.put(terms.get(0), terms.get(1));
        }
    }

    private static List<List<String>> parseAgentOptions(String agentOptions) {
        List<List<String>> options = new ArrayList<>();
        List<String> terms = new ArrayList<>();
        boolean isInQuotes = false;
        StringBuilder currentTerm = new StringBuilder();
        for (char c : agentOptions.toCharArray()) {
            if (c == '\'' || c == '"') {
                isInQuotes = !isInQuotes;
            } else if (c == '=' && !isInQuotes) {   // key-value pair uses '=' as separator
                terms.add(currentTerm.toString());
                currentTerm = new StringBuilder();
            } else if (c == ',' && !isInQuotes) {   // multiple options use ',' as separator
                terms.add(currentTerm.toString());
                currentTerm = new StringBuilder();

                options.add(terms);
                terms = new ArrayList<>();
            } else {
                currentTerm.append(c);
            }
        }
        // add the last term and option without separator
        terms.add(currentTerm.toString());
        options.add(terms);
        return options;
    }

    public static boolean isInitCompleted() {
        return IS_INIT_COMPLETED;
    }

    /**
     * Override the config by system properties. The property key must start with `skywalking`, the result should be as
     * same as in `agent.config`
     * <p>
     * such as: Property key of `agent.service_name` should be `skywalking.agent.service_name`
     */
    private static void overrideConfigBySystemProp() throws IllegalAccessException {
        Properties systemProperties = System.getProperties();
        for (final Map.Entry<Object, Object> prop : systemProperties.entrySet()) {
            String key = prop.getKey().toString();
            if (key.startsWith(ENV_KEY_PREFIX)) {
                String realKey = key.substring(ENV_KEY_PREFIX.length());
                AGENT_SETTINGS.put(realKey, prop.getValue());
            }
        }
    }

    /**
     * Load the specified config file or default config file
     *
     * @return the config file {@link InputStream}, or null if not needEnhance.
     */
    private static InputStreamReader loadConfig() throws AgentPackageNotFoundException, ConfigNotFoundException {
        // 如果系统参数指定了 skywalking_config 环境变量就读这个配置文件的值，否则就加载默认路径下的配置文件
        String specifiedConfigPath = System.getProperty(SPECIFIED_CONFIG_PATH);
        // 找到 skywalking agent 的配置文件
        File configFile = StringUtil.isEmpty(specifiedConfigPath) ? new File(
            AgentPackagePath.getPath(), DEFAULT_CONFIG_FILE_NAME) : new File(specifiedConfigPath);
        // AgentPackagePath.getPath() 用于定位 skywalking-agent.jar 文件所在的位置
        // (以此文件作为参照，可以相对定位找到整个 agent 包的所有文件所在的位置)

        if (configFile.exists() && configFile.isFile()) {
            try {
                LOGGER.info("Config file found in {}.", configFile);

                return new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8);
            } catch (FileNotFoundException e) {
                throw new ConfigNotFoundException("Failed to load agent.config", e);
            }
        }
        throw new ConfigNotFoundException("Failed to load agent.config.");
    }

    static void configureLogger() {
        switch (Config.Logging.RESOLVER) {
            case JSON:
                LogManager.setLogResolver(new JsonLogResolver());
                break;
            case PATTERN:
            default:
                LogManager.setLogResolver(new PatternLogResolver());
        }
    }
}
