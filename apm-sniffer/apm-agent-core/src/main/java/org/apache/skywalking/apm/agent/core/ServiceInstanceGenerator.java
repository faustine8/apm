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

package org.apache.skywalking.apm.agent.core;

import java.util.UUID;
import lombok.Getter;
import org.apache.skywalking.apm.agent.core.boot.BootService;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.os.OSUtil;

import static org.apache.skywalking.apm.util.StringUtil.isEmpty;

/**
 * 专门用来生成 ServiceInstance 名称
 */
@Getter
public class ServiceInstanceGenerator implements BootService {
    @Override
    public void prepare() throws Throwable {
        // 如果配置了 serviceInstance 名称, 直接返回
        if (!isEmpty(Config.Agent.INSTANCE_NAME)) {
            return;
        }

        // 默认生成规则: uuid@ipv4
        Config.Agent.INSTANCE_NAME = UUID.randomUUID().toString().replaceAll("-", "") + "@" + OSUtil.getIPV4();
    }

    @Override
    public void boot() throws Throwable {

    }

    @Override
    public void onComplete() throws Throwable {

    }

    @Override
    public void shutdown() throws Throwable {

    }

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }
}
