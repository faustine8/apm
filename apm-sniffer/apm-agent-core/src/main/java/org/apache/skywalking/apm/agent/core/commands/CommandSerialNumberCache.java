/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.skywalking.apm.agent.core.commands;

import java.util.Deque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 命令的序列号缓存. 序列号被放到一个队列里面, 并且做了容量控制.
 */
public class CommandSerialNumberCache {
    private static final int DEFAULT_MAX_CAPACITY = 64;
    private final Deque<String> queue;
    private final int maxCapacity;

    /**
     * 默认容量为 64
     */
    public CommandSerialNumberCache() {
        this(DEFAULT_MAX_CAPACITY);
    }

    /**
     * 可以自己指定容量
     *
     * @param maxCapacity 容量大小
     */
    public CommandSerialNumberCache(int maxCapacity) {
        queue = new LinkedBlockingDeque<String>(maxCapacity);
        this.maxCapacity = maxCapacity;
    }

    /**
     * 往队列中添加数据. 如果队列满, 就删除第一个元素, 然后添加
     *
     * @param number 新元素(序列号)
     */
    public void add(String number) {
        if (queue.size() >= maxCapacity) {
            queue.pollFirst();
        }

        queue.add(number);
    }

    /**
     * 判断队列中是否包含命令(的序列号)
     */
    public boolean contain(String command) {
        return queue.contains(command);
    }
}
