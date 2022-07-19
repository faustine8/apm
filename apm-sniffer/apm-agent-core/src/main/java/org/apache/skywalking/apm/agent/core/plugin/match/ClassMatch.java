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

package org.apache.skywalking.apm.agent.core.plugin.match;

/**
 * 标识接口，表示类的匹配器。
 *
 * 因为要在一个复杂的系统中，想找到想要的类并不容易，所以提供了各种实现。
 * 最简单的就是根据名称匹配的实现 NameMatch。
 * 另外还需注意一个实现 IndirectMatch 间接匹配。
 */
public interface ClassMatch {
}
