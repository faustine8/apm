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

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.ClassInstanceMethodsEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.match.ClassMatch;
import org.apache.skywalking.apm.agent.core.plugin.match.NameMatch;

import static net.bytebuddy.matcher.ElementMatchers.named;

public class DubboInstrumentation extends ClassInstanceMethodsEnhancePluginDefine {

    // 通过这个类发起远程调用
    private static final String ENHANCE_CLASS = "org.apache.dubbo.monitor.support.MonitorFilter";

    private static final String INTERCEPT_CLASS = "org.apache.skywalking.apm.plugin.asf.dubbo.DubboInterceptor";

    // 针对 Dubbo 的哪个类的字节码进行增强
    @Override
    protected ClassMatch enhanceClass() {
        // 根据名称，完全匹配
        return NameMatch.byName(ENHANCE_CLASS);
    }

    // 获取构造方法拦截点(不需要就返回 null)
    @Override
    public ConstructorInterceptPoint[] getConstructorsInterceptPoints() {
        return null;
    }

    // 获取实例方法拦截点
    @Override
    public InstanceMethodsInterceptPoint[] getInstanceMethodsInterceptPoints() {
        return new InstanceMethodsInterceptPoint[] {
            new InstanceMethodsInterceptPoint() {
                // 此处表示要针对 Dubbo 中的 MonitorFilter 类的 invoke 方法进行增强，修改他的字节码
                @Override
                public ElementMatcher<MethodDescription> getMethodsMatcher() {
                    return named("invoke");
                }

                // 已知要修改 invoke 方法，那么怎么改呢？交给此方法返回的这个拦截器进行修改
                @Override
                public String getMethodsInterceptor() {
                    return INTERCEPT_CLASS;
                }

                // 修改原方法的字节码时，是否对原方法的入参做改变
                @Override
                public boolean isOverrideArgs() {
                    return false;
                }
            }
        };
    }
}
