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

package org.apache.skywalking.apm.network.trace.component.command;

import java.util.ArrayList;
import java.util.List;
import org.apache.skywalking.apm.network.common.v3.Command;
import org.apache.skywalking.apm.network.common.v3.KeyStringValuePair;

public class ConfigurationDiscoveryCommand extends BaseCommand implements Serializable, Deserializable<ConfigurationDiscoveryCommand> {
    public static final Deserializable<ConfigurationDiscoveryCommand> DESERIALIZER = new ConfigurationDiscoveryCommand(
        "", "", new ArrayList<>());
    public static final String NAME = "ConfigurationDiscoveryCommand";

    public static final String UUID_CONST_NAME = "UUID";
    public static final String SERIAL_NUMBER_CONST_NAME = "SerialNumber";

    /*
     * If config is unchanged, then could response the same uuid, and config is not required.
     *
     * 如果配置文件没有发生变化, 则返回的 uuid 也不会变更.
     */
    private String uuid;
    /*
     * The configuration of service.
     */
    private List<KeyStringValuePair> config;

    public ConfigurationDiscoveryCommand(String serialNumber,
                                         String uuid,
                                         List<KeyStringValuePair> config) {
        super(NAME, serialNumber);
        this.uuid = uuid;
        this.config = config;
    }

    @Override
    public ConfigurationDiscoveryCommand deserialize(Command command) {
        String serialNumber = null;
        String uuid = null;
        List<KeyStringValuePair> config = new ArrayList<>();

        // 迭代所有的 arguments
        for (final KeyStringValuePair pair : command.getArgsList()) {
            // Key 是 SerialNumber 或者 UUID 的情况, 特殊处理; 否则直接加入 config 集合
            if (SERIAL_NUMBER_CONST_NAME.equals(pair.getKey())) {
                serialNumber = pair.getValue();
            } else if (UUID_CONST_NAME.equals(pair.getKey())) {
                uuid = pair.getValue();
            } else {
                config.add(pair);
            }
        }
        // 然后调用构造器构建的对象
        return new ConfigurationDiscoveryCommand(serialNumber, uuid, config);
    }

    @Override
    public Command.Builder serialize() {
        final Command.Builder builder = commandBuilder();
        builder.addArgs(KeyStringValuePair.newBuilder().setKey(UUID_CONST_NAME).setValue(uuid));
        builder.addAllArgs(config);
        return builder;
    }

    public String getUuid() {
        return uuid;
    }

    public List<KeyStringValuePair> getConfig() {
        return config;
    }

    @Override
    public String toString() {
        return "ConfigurationDiscoveryCommand{" +
            "uuid='" + uuid + '\'' +
            ", config=" + config +
            '}';
    }
}
