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

package org.apache.skywalking.apm.agent.core.boot;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.loader.AgentClassLoader;

/**
 * The <code>ServiceManager</code> bases on {@link ServiceLoader}, load all {@link BootService} implementations.
 */
public enum ServiceManager {
    INSTANCE;

    private static final ILog LOGGER = LogManager.getLogger(ServiceManager.class);
    private Map<Class, BootService> bootedServices = Collections.emptyMap();

    public void boot() {
        // 首先加载所有的 BootService 的实现
        bootedServices = loadAllServices();

        // 对所有 BootService 根据 priority 进行排序，然后调用 prepare,startup,onComplete 方法
        prepare();
        startup();
        onComplete();
    }

    // shutdown 的时候根据优先级「倒序」排序后，执行 shutdown 方法
    public void shutdown() {
        bootedServices.values().stream().sorted(Comparator.comparingInt(BootService::priority).reversed()).forEach(service -> {
            try {
                service.shutdown();
            } catch (Throwable e) {
                LOGGER.error(e, "ServiceManager try to shutdown [{}] fail.", service.getClass().getName());
            }
        });
    }

    private Map<Class, BootService> loadAllServices() {
        Map<Class, BootService> bootedServices = new LinkedHashMap<>();
        List<BootService> allServices = new LinkedList<>();
        // 通过自定义的 AgentClassLoader 加载所有的 BootService 的实例，然后添加到 allServices 集合中
        load(allServices);
        for (final BootService bootService : allServices) {
            // 获取每一个加载到的服务的 Class
            Class<? extends BootService> bootServiceClass = bootService.getClass();
            // 获取到加载到的 Class，判断是否是默认实现
            boolean isDefaultImplementor = bootServiceClass.isAnnotationPresent(DefaultImplementor.class);
            if (isDefaultImplementor) {
                // 如果是默认实现，看是否放入了 bootedServices Map 中，如果没有就放入
                if (!bootedServices.containsKey(bootServiceClass)) {
                    // key 是 Class，value 是已经加载的实例对象
                    bootedServices.put(bootServiceClass, bootService);
                } else {
                    //ignore the default service
                }
            } else {
                // 不是默认实现的情况下
                // 首先看是不是覆盖实现
                OverrideImplementor overrideImplementor = bootServiceClass.getAnnotation(OverrideImplementor.class);
                // 如果也不是覆盖实现，那么他就是「只有一种实现」
                if (overrideImplementor == null) {
                    if (!bootedServices.containsKey(bootServiceClass)) {
                        // key 是 Class，value 是已经加载的实例对象
                        bootedServices.put(bootServiceClass, bootService);
                    } else {
                        // 只有一种实现的情况下，如果有相同 Class 的实例对象，则抛出 重复实现 的异常
                        throw new ServiceConflictException("Duplicate service define for :" + bootServiceClass);
                    }
                } else { // 没有 DefaultImplementor 但是有 OverrideImplementor: 覆盖实现的类
                    Class<? extends BootService> targetService = overrideImplementor.value();
                    if (bootedServices.containsKey(targetService)) {// bootedServices 中已经有默认实现了 (即：当前「覆盖实现」要覆盖的「默认实现」已经加载进来)
                        // 确认一下 targetService 是不是真的默认实现
                        boolean presentDefault = bootedServices.get(targetService)
                                                               .getClass()
                                                               .isAnnotationPresent(DefaultImplementor.class);
                        if (presentDefault) { // 确认之后使用 默认实现的 Class 作为key，覆盖实现的对象作为 value。(也就是用覆盖实现，覆盖默认实现)
                            bootedServices.put(targetService, bootService);
                            // 思考：此处通过「覆盖实现」覆盖了默认实现，那么我的「默认实现」不就白写了吗？
                            // 不白写。因为「覆盖实现」首先要继承「默认实现」，然后「覆盖实现」在写自己的逻辑之前，往往会先调用 super() 方法，先执行默认实现的逻辑。
                        } else { // OverrideImplementor 注解的 value 指向的类并不是默认实现(没有DefaultImplementor注解修饰)，则抛出异常
                            throw new ServiceConflictException(
                                "Service " + bootServiceClass + " overrides conflict, " + "exist more than one service want to override :" + targetService);
                        }
                    } else { // bootedServices 中还没有加载默认实现(即：当前「覆盖实现」要覆盖的「默认实现」还没有加载进来)，直接加入(即：把当前「覆盖实现」当成其服务的「默认实现」)。
                        bootedServices.put(targetService, bootService);
                        // 思考：如果先加载了覆盖实现，后加载到默认实现怎么办呢？会走到前面 75 行的位置，直接忽略，并不会用默认实现覆盖「覆盖实现」
                    }
                }
            }

        }
        return bootedServices;
    }

    private void prepare() {
        // 对所有 BootService 根据 priority 进行排序，然后调用 prepare 方法
        bootedServices.values().stream().sorted(Comparator.comparingInt(BootService::priority)).forEach(service -> {
            try {
                service.prepare();
            } catch (Throwable e) {
                LOGGER.error(e, "ServiceManager try to pre-start [{}] fail.", service.getClass().getName());
            }
        });
    }

    private void startup() {
        bootedServices.values().stream().sorted(Comparator.comparingInt(BootService::priority)).forEach(service -> {
            try {
                service.boot();
            } catch (Throwable e) {
                LOGGER.error(e, "ServiceManager try to start [{}] fail.", service.getClass().getName());
            }
        });
    }

    private void onComplete() {
        for (BootService service : bootedServices.values()) {
            try {
                service.onComplete();
            } catch (Throwable e) {
                LOGGER.error(e, "Service [{}] AfterBoot process fails.", service.getClass().getName());
            }
        }
    }

    /**
     * Find a {@link BootService} implementation, which is already started.
     *
     * @param serviceClass class name.
     * @param <T>          {@link BootService} implementation class.
     * @return {@link BootService} instance
     */
    public <T extends BootService> T findService(Class<T> serviceClass) {
        return (T) bootedServices.get(serviceClass);
    }

    void load(List<BootService> allServices) {
        // 通过自定义的 AgentClassLoader 加载所有的 BootService 的实例，然后添加到 allServices 集合中
        for (final BootService bootService : ServiceLoader.load(BootService.class, AgentClassLoader.getDefault())) {
            allServices.add(bootService);
        }
    }
}
