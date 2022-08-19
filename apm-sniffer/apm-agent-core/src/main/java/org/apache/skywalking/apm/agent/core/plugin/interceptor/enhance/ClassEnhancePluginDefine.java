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

package org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.SuperMethodCall;
import net.bytebuddy.implementation.bind.annotation.Morph;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.AbstractClassEnhancePluginDefine;
import org.apache.skywalking.apm.agent.core.plugin.EnhanceContext;
import org.apache.skywalking.apm.agent.core.plugin.PluginException;
import org.apache.skywalking.apm.agent.core.plugin.bootstrap.BootstrapInstrumentBoost;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.ConstructorInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.DeclaredInstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.EnhanceException;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.InstanceMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.StaticMethodsInterceptPoint;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.v2.InstanceMethodsInterceptV2Point;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.v2.StaticMethodsInterceptV2Point;
import org.apache.skywalking.apm.util.StringUtil;

import static net.bytebuddy.jar.asm.Opcodes.ACC_PRIVATE;
import static net.bytebuddy.jar.asm.Opcodes.ACC_VOLATILE;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * This class controls all enhance operations, including enhance constructors, instance methods and static methods. All
 * the enhances base on three types interceptor point: {@link ConstructorInterceptPoint}, {@link
 * InstanceMethodsInterceptPoint} and {@link StaticMethodsInterceptPoint} If plugin is going to enhance constructors,
 * instance methods, or both, {@link ClassEnhancePluginDefine} will add a field of {@link Object} type.
 */
public abstract class ClassEnhancePluginDefine extends AbstractClassEnhancePluginDefine {
    private static final ILog LOGGER = LogManager.getLogger(ClassEnhancePluginDefine.class);

    /**
     * Enhance a class to intercept constructors and class instance methods.
     *
     * @param typeDescription target class description
     * @param newClassBuilder byte-buddy's builder to manipulate class bytecode.
     * @return new byte-buddy's builder for further manipulation.
     */
    protected DynamicType.Builder<?> enhanceInstance(TypeDescription typeDescription,
        DynamicType.Builder<?> newClassBuilder, ClassLoader classLoader,
        EnhanceContext context) throws PluginException {
        // 拿到所有的构造器拦截点
        ConstructorInterceptPoint[] constructorInterceptPoints = getConstructorsInterceptPoints();
        // 拿到所有实例方法拦截点
        InstanceMethodsInterceptPoint[] instanceMethodsInterceptPoints = getInstanceMethodsInterceptPoints();
        String enhanceOriginClassName = typeDescription.getTypeName();
        // 判断是否存在构造方法拦截点和实例方法拦截点
        boolean existedConstructorInterceptPoint = false;
        if (constructorInterceptPoints != null && constructorInterceptPoints.length > 0) {
            existedConstructorInterceptPoint = true;
        }
        boolean existedMethodsInterceptPoints = false;
        if (instanceMethodsInterceptPoints != null && instanceMethodsInterceptPoints.length > 0) {
            existedMethodsInterceptPoints = true;
        }

        /**
         * nothing need to be enhanced in class instance, maybe need enhance static methods.
         * 如果都不存在，直接返回
         */
        if (!existedConstructorInterceptPoint && !existedMethodsInterceptPoints) {
            return newClassBuilder;
        }

        /**
         * Manipulate class source code.<br/>
         *
         * new class need:<br/>
         * 1.Add field, name {@link #CONTEXT_ATTR_NAME}.
         * 2.Add a field accessor for this field.
         *
         * And make sure the source codes manipulation only occurs once.
         * 确保此处这个操作只执行一次. (所以做了双重判断)
         */
        if (!typeDescription.isAssignableTo(EnhancedInstance.class)) { // 如果当前拦截到的类，没有实现 EnhancedInstance 接口
            if (!context.isObjectExtended()) {// 通过上下文标识类，判断当前拦截到的类没有新增了成员变量或者实现了新的接口
                newClassBuilder = newClassBuilder.defineField(
                    CONTEXT_ATTR_NAME, Object.class, ACC_PRIVATE | ACC_VOLATILE) // 通过 bytebuddy API 添加 Object 类型的成员变量，并指定修饰符和变量名
                                                 .implement(EnhancedInstance.class) // 添加实现接口
                                                 .intercept(FieldAccessor.ofField(CONTEXT_ATTR_NAME)); // 将 EnhancedInstance 中的 getter/setter 作为刚刚新增的成员变量的 getter/setter
                context.extendObjectCompleted(); // 标记已经添加了成员变量/实现了新的接口
            }
        }

        /**
         * 2. enhance constructors
         */
        if (existedConstructorInterceptPoint) { // 如果存在构造器拦截点
            for (ConstructorInterceptPoint constructorInterceptPoint : constructorInterceptPoints) {
                if (isBootstrapInstrumentation()) { // 是 JDK 核心类库的类
                    newClassBuilder = newClassBuilder.constructor(constructorInterceptPoint.getConstructorMatcher())
                                                     .intercept(SuperMethodCall.INSTANCE.andThen(MethodDelegation.withDefaultConfiguration()
                                                                                                                 .to(BootstrapInstrumentBoost
                                                                                                                     .forInternalDelegateClass(constructorInterceptPoint
                                                                                                                         .getConstructorInterceptor()))));
                } else { // 不是 JDK 核心类库的类
                    newClassBuilder = newClassBuilder.constructor(constructorInterceptPoint.getConstructorMatcher())
                                                     .intercept(SuperMethodCall.INSTANCE.andThen(MethodDelegation.withDefaultConfiguration()
                                                                                                                 .to(new ConstructorInter(constructorInterceptPoint // 包装 ConstructorInter
                                                                                                                     .getConstructorInterceptor(), classLoader))));
                }
            }
        }

        /**
         * 3. enhance instance methods
         */
        if (existedMethodsInterceptPoints) { // 如果存在实例方法拦截点
            for (InstanceMethodsInterceptPoint instanceMethodsInterceptPoint : instanceMethodsInterceptPoints) {
                // 获取 interceptor 的全限定类名，如果不存在就抛除异常
                String interceptor = instanceMethodsInterceptPoint.getMethodsInterceptor();
                if (StringUtil.isEmpty(interceptor)) {
                    throw new EnhanceException("no InstanceMethodsAroundInterceptor define to enhance class " + enhanceOriginClassName);
                }
                // 首先要不是静态方法，而且要满足 getMethodsMatcher 中指定的条件
                ElementMatcher.Junction<MethodDescription> junction = not(isStatic()).and(instanceMethodsInterceptPoint.getMethodsMatcher());
                // 拦截点如果是 声明式 的(如 spring 的 controller) ,就添加必须满足：有当前这个类声明的
                if (instanceMethodsInterceptPoint instanceof DeclaredInstanceMethodsInterceptPoint) {
                    junction = junction.and(ElementMatchers.<MethodDescription>isDeclaredBy(typeDescription));
                }
                // 根据是否修改参数 + 是否 JDK 核心类库的类，走 4 个不同的分支
                if (instanceMethodsInterceptPoint.isOverrideArgs()) {
                    if (isBootstrapInstrumentation()) {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                                    .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
                    } else {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                                    .to(new InstMethodsInterWithOverrideArgs(interceptor, classLoader)));
                    }
                } else {
                    if (isBootstrapInstrumentation()) {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
                    } else {
                        newClassBuilder = newClassBuilder.method(junction)
                                                         .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                    .to(new InstMethodsInter(interceptor, classLoader)));
                    }
                }
            }
        }

        return newClassBuilder;
    }

    /**
     * Enhance a class to intercept class static methods.
     *
     * @param typeDescription target class description
     * @param newClassBuilder byte-buddy's builder to manipulate class bytecode.
     * @return new byte-buddy's builder for further manipulation.
     */
    protected DynamicType.Builder<?> enhanceClass(TypeDescription typeDescription, DynamicType.Builder<?> newClassBuilder,
        ClassLoader classLoader) throws PluginException {
        // 当前生效的插件的「静态方法拦截器」指定的「静态方法拦截点」
        StaticMethodsInterceptPoint[] staticMethodsInterceptPoints = getStaticMethodsInterceptPoints();
        String enhanceOriginClassName = typeDescription.getTypeName();
        // 如果没有拦截点，直接返回了
        if (staticMethodsInterceptPoints == null || staticMethodsInterceptPoints.length == 0) {
            return newClassBuilder;
        }

        // 拿到拦截点后，开始处理
        for (StaticMethodsInterceptPoint staticMethodsInterceptPoint : staticMethodsInterceptPoints) {
            // 拿到增强逻辑所在的类的全类名
            String interceptor = staticMethodsInterceptPoint.getMethodsInterceptor();
            if (StringUtil.isEmpty(interceptor)) {
                throw new EnhanceException("no StaticMethodsAroundInterceptor define to enhance class " + enhanceOriginClassName);
            }

            if (staticMethodsInterceptPoint.isOverrideArgs()) { // 是否需要改方法的参数
                if (isBootstrapInstrumentation()) { // 感兴趣的类是否是由 Bootstrap 类加载器加载的 (这类「类」需要独立处理)
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                                                     .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                                .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
                } else { // 不是 JDK 核心类库的类
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                                                     .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                .withBinders(Morph.Binder.install(OverrideCallable.class))
                                                                                .to(new StaticMethodsInterWithOverrideArgs(interceptor))); // 相较于不修改原方法入参的，这里使用的是 StaticMethodsInterWithOverrideArgs
                }
            } else { // 不需要修改原方法的入参
                if (isBootstrapInstrumentation()) { // 要修改的是 JDK 核心类库的类
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher()))
                                                     .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                .to(BootstrapInstrumentBoost.forInternalDelegateClass(interceptor)));
                } else { // 要修改的不是 JDK 核心类库的类
                    newClassBuilder = newClassBuilder.method(isStatic().and(staticMethodsInterceptPoint.getMethodsMatcher())) // 找到指定的方法
                                                     .intercept(MethodDelegation.withDefaultConfiguration()
                                                                                .to(new StaticMethodsInter(interceptor))); // 通过 interceptor 的全类名，真正做字节码增强
                }
            }

        }

        return newClassBuilder;
    }

    /**
     * @return null, means enhance no v2 instance methods.
     */
    @Override
    public InstanceMethodsInterceptV2Point[] getInstanceMethodsInterceptV2Points() {
        return null;
    }

    /**
     * @return null, means enhance no v2 static methods.
     */
    @Override
    public StaticMethodsInterceptV2Point[] getStaticMethodsInterceptV2Points() {
        return null;
    }

}
