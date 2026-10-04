package io.izzel.incision.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Incision 字节码桥 — 所有被 incision 织入的 INVOKESTATIC 调用都指向这里。
 *
 * 设计背景：
 * TabooLib Gradle 插件 (<code>io.izzel.taboolib</code>) 的 RelocateRemapper 会在
 * 打包阶段把 <code>taboolib.*</code> 前缀重定向为 <code>&lt;user.group&gt;.taboolib.*</code>。
 * 如果桥类放在 <code>taboolib.module.incision.*</code>，每个插件最终 jar 中的桥
 * 会变成不同类，无法跨插件共享。
 *
 * 因此桥必须位于 <b>taboolib.* 之外</b> 的包（本类位于 <code>io.izzel.incision.bridge</code>），
 * 并且用 Java 编写以避免 Kotlin 运行时依赖（Kotlin 同样会被 <code>kotlin.*</code> 重定向）。
 *
 * 解析顺序：
 * <ol>
 *   <li>按目标签名查找声明该切术的 TheatreDispatcher；</li>
 *   <li>旧调用方未登记目标时，才按 defining ClassLoader 或唯一 lease 回退；</li>
 *   <li>本地没有可用路由时再交给系统 ClassLoader 上的 Gate。</li>
 * </ol>
 *
 * 本类通过 MethodHandle（unreflect 成功时）或反射（访问控制不允许时）跨 ClassLoader 调用
 * dispatch，避免类型一致性问题。两条路径的异常语义必须一致，见 {@link #invokeRoute}。
 */
public final class IncisionBridge {

    private IncisionBridge() {}

    private static final Object BYPASS_MISS = new Object();

    /**
     * 路由句柄类型：3 参与 4 参两种 dispatch 统一归一化到这一种。
     *
     * 调用点因此保持单态（MethodHandle.invoke 是签名多态方法，调用点类型须稳定才能被 JIT 内联）；
     * 第 4 位是 4 参 dispatch 的额外参数，3 参形态由 dropArguments 补一个被丢弃的占位。
     *
     * 必须声明在 systemHandle 之前：静态初始化按文本顺序执行，晚声明会让初始化期的句柄解析
     * 拿到 null 类型而静默退化。
     */
    private static final MethodType ROUTE_TYPE =
        MethodType.methodType(Object.class, String.class, Object.class, Object[].class, Object.class);

    /** 系统 ClassLoader 上的宿主类（若存在） */
    private static volatile Object systemHost = findSystemHost();

    /** 宿主的 dispatch 方法反射缓存 */
    private static volatile Method systemDispatch = resolveDispatch(systemHost);

    /** 宿主 dispatch 的句柄缓存（receiver 已绑定）；为 null 时回落反射 */
    private static volatile MethodHandle systemHandle = resolveRouteHandle(systemDispatch, systemHost);

    /** ClassLoader → 本地 TheatreDispatcher.dispatch Method 缓存（单插件 fallback 路径） */
    private static final ConcurrentHashMap<ClassLoader, Method> localCache = new ConcurrentHashMap<ClassLoader, Method>();

    /**
     * dispatcher 声明类 → 已归一化的 dispatch 句柄。
     *
     * 为什么不把句柄直接放进 localCache / targetRoutes 的值里：bootstrap 注入协议只复制
     * IncisionBridge.class 一份字节码（见 IncisionBootstrap 的注入逻辑），任何
     * IncisionBridge$Xxx.class 在 bootstrap ClassLoader 中都不存在，所以这里不能为路由定义包装类。
     *
     * 一个 dispatcher 类只有一个 dispatch（pickDispatchMethod 的选法），因此以声明类为键没有歧义；
     * 键是 Class，hashCode 为 identity hash，热路径查找成本远低于一次反射调用。
     */
    private static final ConcurrentHashMap<Class<?>, MethodHandle> routeHandles =
        new ConcurrentHashMap<Class<?>, MethodHandle>();

    /**
     * 运行时目标签名 → 声明该目标的 dispatcher。
     *
     * 被织入类可能属于 Leaf 的服务端 URLClassLoader，也可能属于 AuraSkills 等第三方插件；
     * 它的 defining loader 与切术声明方没有必然关系，因此 loader 绝不能作为正常路由依据。
     */
    private static final ConcurrentHashMap<String, CopyOnWriteArrayList<Method>> targetRoutes =
        new ConcurrentHashMap<String, CopyOnWriteArrayList<Method>>();

    /** 多插件声明同一目标的诊断去重；真正的跨插件优先级聚合必须由 Gate 完成。 */
    private static final ConcurrentHashMap<String, Boolean> routeConflictWarnings =
        new ConcurrentHashMap<String, Boolean>();

    /**
     * JVM 进程只能由一个隔离 ClassLoader 直接拥有已加载的 JVMTI DLL。
     * Bridge 保留该 owner，并把 native 回调广播给所有插件后端，避免后加载插件再次 System.load。
     */
    private static volatile Class<?> nativeOwner;
    private static volatile Method nativeOwnerInvoke;
    private static final CopyOnWriteArrayList<Class<?>> nativeDelegates = new CopyOnWriteArrayList<Class<?>>();
    private static final ConcurrentHashMap<Class<?>, Method> nativeTransformCache = new ConcurrentHashMap<Class<?>, Method>();
    /**
     * JVMTI 回调可能在 delegate 链接辅助类时再次进入 ClassFileLoadHook；线程级闸门保证
     * 递归加载只返回原始字节，避免隔离加载器重复定义 Backend$BackendToken。
     */
    private static final ThreadLocal<Boolean> nativeTransformGuard = new ThreadLocal<Boolean>();

    /**
     * Side-car body 的字段解析缓存。
     *
     * key 与 value 都必须是弱引用语义，避免系统级 Bridge 通过 Field 反向强持有插件 ClassLoader；
     * 此处不能使用匿名 ClassValue 子类，因为 bootstrap 注入协议只复制 IncisionBridge.class，
     * 任何 IncisionBridge$1.class 都会让 canonical 类初始化失败。
     */
    private static final Map<Class<?>, WeakReference<ConcurrentHashMap<String, Field>>> accessFields =
        Collections.synchronizedMap(new WeakHashMap<Class<?>, WeakReference<ConcurrentHashMap<String, Field>>>());

    /**
     * 供 weaver 注入的 INVOKESTATIC 目标。
     *
     * <pre>
     * INVOKESTATIC io/izzel/incision/bridge/IncisionBridge.dispatch
     *   (Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;
     * </pre>
     *
     * @param targetSignature 目标方法签名（编译期常量串）
     * @param self            this 引用（静态方法时为 null）
     * @param args            原方法实参
     * @return advice 链的最终返回值；若为 null 调用方应继续执行原方法
     */
    public static Object dispatch(Class<?> ownerClass, String targetSignature, Object self, Object[] args) {
        ClassLoader definingLoader = ownerClass == null ? null : ownerClass.getClassLoader();
        List<Method> locals = resolveLocalDispatches(definingLoader, targetSignature);
        if (!locals.isEmpty()) {
            Object result = null;
            boolean invoked = false;
            for (Method local : locals) {
                try {
                    // 句柄优先（每类一条，未命中即回落反射）；receiver 为 null —— 本地 dispatch 是静态方法
                    Object localResult = invokeRoute(
                        local, routeHandles.get(local.getDeclaringClass()), null, targetSignature, self, args
                    );
                    // 未持有该 target 的 dispatcher 以 null 表示未命中，不能覆盖前一个插件的有效结果。
                    if (localResult != null) result = localResult;
                    invoked = true;
                } catch (Throwable t) {
                    System.err.println("[Incision][Bridge] local dispatch failed: " + t);
                }
            }
            if (invoked) return result;
        }
        Method m = systemDispatch;
        Object host = systemHost;
        if (m != null && host != null) {
            try {
                return invokeRoute(m, systemHandle, host, targetSignature, self, args);
            } catch (Throwable t) {
                System.err.println("[Incision][Bridge] system host dispatch failed: " + t);
            }
        }
        // 精确 loader 路由失败属于生命周期错误，不能静默伪装成 advice 未命中。
        System.err.println("[Incision][Bridge] dispatch unavailable: owner=" +
            (ownerClass == null ? "null" : ownerClass.getName()) + " loader=" + definingLoader +
            " localLeases=" + localCache.size() + " targetRoutes=" + targetRoutes.size() +
            " target=" + targetSignature);
        return null;
    }

    public static Object dispatchBypass(Class<?> ownerClass, String targetSignature, Object self, Object[] args) {
        List<Method> dispatches = resolveLocalDispatches(
            ownerClass == null ? null : ownerClass.getClassLoader(), targetSignature
        );
        for (Method dispatch : dispatches) {
            Method local = resolveLocalSibling(dispatch, "dispatchBypass");
            if (local == null) continue;
            try {
                Object result = local.invoke(null, targetSignature, self, args);
                if (!isBypassMiss(result)) return result;
            } catch (Throwable t) {
                System.err.println("[Incision][Bridge] local bypass dispatch failed: " + t);
            }
        }
        return BYPASS_MISS;
    }

    public static Object bypassMiss() {
        return BYPASS_MISS;
    }

    public static boolean isBypassMiss(Object value) {
        return value == BYPASS_MISS;
    }

    /**
     * Side-car body 读取宿主私有字段的稳定入口。
     * 普通字段访问不应依赖某个插件 ClassLoader 独占的 JVMTI native image。
     */
    public static Object accessFieldGet(Object receiver, Class<?> ownerClass, String fieldName, String fieldDesc) {
        try {
            return resolveAccessField(ownerClass, fieldName, fieldDesc).get(receiver);
        } catch (Throwable t) {
            throw new IllegalStateException("Incision field read failed: " + ownerClass.getName() + "." + fieldName, t);
        }
    }

    /** Side-car body 写入宿主私有字段；访问规则与 {@link #accessFieldGet} 相同。 */
    public static void accessFieldSet(Object receiver, Class<?> ownerClass, String fieldName, String fieldDesc, Object value) {
        try {
            resolveAccessField(ownerClass, fieldName, fieldDesc).set(receiver, value);
        } catch (Throwable t) {
            throw new IllegalStateException("Incision field write failed: " + ownerClass.getName() + "." + fieldName, t);
        }
    }

    /** Side-car body 读取宿主私有静态字段。 */
    public static Object accessStaticFieldGet(Class<?> ownerClass, String fieldName, String fieldDesc) {
        return accessFieldGet(null, ownerClass, fieldName, fieldDesc);
    }

    /** Side-car body 写入宿主私有静态字段。 */
    public static void accessStaticFieldSet(Class<?> ownerClass, String fieldName, String fieldDesc, Object value) {
        accessFieldSet(null, ownerClass, fieldName, fieldDesc, value);
    }

    private static Field resolveAccessField(Class<?> ownerClass, String fieldName, String fieldDesc) throws NoSuchFieldException {
        String key = fieldName + ':' + fieldDesc;
        ConcurrentHashMap<String, Field> fields;
        synchronized (accessFields) {
            WeakReference<ConcurrentHashMap<String, Field>> reference = accessFields.get(ownerClass);
            fields = reference == null ? null : reference.get();
            if (fields == null) {
                fields = new ConcurrentHashMap<String, Field>();
                accessFields.put(ownerClass, new WeakReference<ConcurrentHashMap<String, Field>>(fields));
            }
        }
        Field cached = fields.get(key);
        if (cached != null) return cached;
        Class<?> cursor = ownerClass;
        while (cursor != null) {
            try {
                Field field = cursor.getDeclaredField(fieldName);
                field.setAccessible(true);
                Field previous = fields.putIfAbsent(key, field);
                return previous == null ? field : previous;
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(ownerClass.getName() + '.' + fieldName + ':' + fieldDesc);
    }

    /** 宿主绑定入口 — GateBootstrapper 创建 host 后调用此方法完成注册 */
    public static synchronized void bindSystemHost(Object host) {
        Method dispatch = resolveDispatch(host);
        // 先发布句柄再发布 Method/host：读到 host 的线程同时也应看到对应的句柄选择。
        systemHandle = resolveRouteHandle(dispatch, host);
        systemDispatch = dispatch;
        systemHost = host;
    }

    public static synchronized void unbindSystemHost() {
        systemHandle = null;
        systemDispatch = null;
        systemHost = null;
    }

    public static boolean hasSystemHost() {
        return systemHost != null && systemDispatch != null;
    }

    /** JVM 级 lease 数量；Gate holder 只能在该值归零后释放共享 delegate。 */
    public static int localLeaseCount() {
        return localCache.size();
    }

    /** 注册插件后端；返回 JVM 当前是否已有可用 native owner。 */
    public static synchronized boolean registerNativeBackend(Class<?> backendClass, boolean ownsNative) {
        if (backendClass == null) return nativeOwner != null;
        try {
            // getMethods 会解析 Backend 继承树上的所有公开签名。在 ClassFileLoadHook 激活期间，
            // 这会递归触发 Backend$BackendToken 的定义并让隔离加载器报 duplicate definition。
            // 注册阶段只解析 Bridge 协议声明本身，并在进入 native hook 前完成缓存。
            // 同时提前初始化 delegate；否则首次 Method.invoke 仍可能在 ClassFileLoadHook 内
            // 解析 Backend 继承树，把 Backend$BackendToken 的延迟定义重新带入回调递归。
            Class.forName(backendClass.getName(), true, backendClass.getClassLoader());
            Method transform = backendClass.getDeclaredMethod(
                "onSharedClassFileLoad", ClassLoader.class, String.class, byte[].class
            );
            nativeTransformCache.put(backendClass, transform);
            if (ownsNative && nativeOwner == null) {
                Method invoke = backendClass.getDeclaredMethod("sharedNativeInvoke", String.class, Object[].class);
                nativeOwner = backendClass;
                nativeOwnerInvoke = invoke;
            }
        } catch (ReflectiveOperationException e) {
            nativeTransformCache.remove(backendClass);
            throw new IllegalArgumentException("Invalid Incision native backend protocol: " + backendClass.getName(), e);
        }
        if (!nativeDelegates.contains(backendClass)) nativeDelegates.add(backendClass);
        return nativeOwner != null;
    }

    /**
     * native ClassFileLoadHook 的 JVM 级聚合入口。每个 delegate 接收前一个插件产生的字节码，
     * 因而两个插件对同一方法的织入会形成确定的先后链，而不是互相覆盖。
     */
    public static byte[] transformNative(ClassLoader loader, String name, byte[] bytes) {
        // BackendToken 是 Incision 协议接口，不是业务目标。对它进行织入会在接口定义尚未完成
        // 时重新解析同一个接口，JVM 会以 duplicate interface definition 拒绝第二次定义。
        if (name != null && (name.endsWith("/Backend$BackendToken") || name.endsWith("$BackendToken"))) {
            return null;
        }
        if (Boolean.TRUE.equals(nativeTransformGuard.get())) return null;
        nativeTransformGuard.set(Boolean.TRUE);
        byte[] current = bytes;
        boolean changed = false;
        try {
            for (Class<?> backend : nativeDelegates) {
                try {
                    Method method = nativeTransformCache.get(backend);
                    if (method == null) {
                        throw new IllegalStateException("native transformer delegate was not pre-resolved");
                    }
                    byte[] output = (byte[]) method.invoke(null, loader, name, current);
                    if (output != null) {
                        current = output;
                        changed = true;
                    }
                } catch (Throwable t) {
                    System.err.println("[Incision][Bridge] native transformer delegate failed: " + backend.getName() + " — " + t);
                }
            }
            return changed ? current : null;
        } finally {
            nativeTransformGuard.remove();
        }
    }

    /** 非 owner 插件通过这一入口复用唯一 native image。 */
    public static Object invokeNative(String operation, Object[] args) {
        Class<?> owner = nativeOwner;
        Method method = nativeOwnerInvoke;
        if (owner == null || method == null) throw new IllegalStateException("Incision native owner unavailable");
        try {
            return method.invoke(null, operation, args);
        } catch (Throwable t) {
            throw new IllegalStateException("Incision shared native invocation failed: " + operation, t);
        }
    }

    /**
     * 插件卸载只移除自己的 delegate。最后一个 lease 才关闭 JVMTI；若 owner 先卸载，
     * 其 Class 对象必须暂留到最后一个 lease 结束，否则其他插件无法继续调用 native image。
     */
    public static synchronized void unregisterNativeBackend(Class<?> backendClass) {
        if (backendClass == null) return;
        nativeDelegates.remove(backendClass);
        nativeTransformCache.remove(backendClass);
        if (!nativeDelegates.isEmpty()) return;
        Class<?> owner = nativeOwner;
        Method invoke = nativeOwnerInvoke;
        nativeOwner = null;
        nativeOwnerInvoke = null;
        if (owner == null || invoke == null) return;
        try {
            invoke.invoke(null, "dispose", new Object[0]);
        } catch (Throwable t) {
            System.err.println("[Incision][Bridge] native dispose failed: " + t);
        }
    }

    // -----------------------------------------------------------------

    private static Object findSystemHost() {
        try {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            Class<?> hostCls = Class.forName("io.izzel.incision.bridge.IncisionGateHost", true, sys);
            // 约定：IncisionGateHost.INSTANCE 是公开静态字段
            return hostCls.getField("INSTANCE").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveDispatch(Object host) {
        if (host == null) return null;
        try {
            return host.getClass().getMethod(
                "dispatch", String.class, Object.class, Object[].class
            );
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method resolveLocalDispatch(ClassLoader definingLoader) {
        Method cached = definingLoader == null ? null : localCache.get(definingLoader);
        if (cached != null) return cached;
        // 兼容未登记 target 的旧调用方：只有一个插件持有 lease 时路由没有歧义。
        // 多 lease 的广播由 resolveLocalDispatches 生成快照，禁止退化为“最后注册 dispatcher”。
        if (localCache.size() == 1) return localCache.values().iterator().next();
        return null;
    }

    private static List<Method> resolveLocalDispatches(ClassLoader definingLoader, String targetSignature) {
        CopyOnWriteArrayList<Method> routed = targetRoutes.get(baseSignature(targetSignature));
        if (routed != null && !routed.isEmpty()) return new ArrayList<Method>(routed);
        Method legacy = resolveLocalDispatch(definingLoader);
        if (legacy != null) return java.util.Collections.singletonList(legacy);
        // 旧版调用方不会登记 target。owner loader 又可能属于 Leaf 或第三方插件，
        // 此时广播给快照中的 dispatcher，由各自的 chain 表自行判定是否命中，禁止再因多 lease 直接断链。
        return new ArrayList<Method>(localCache.values());
    }

    /**
     * 登记目标的真实声明方。相位与 Site advice id 属于调用后缀，不参与路由键。
     */
    public static void registerLocalTarget(Class<?> dispatcherClass, String targetSignature) {
        if (dispatcherClass == null || targetSignature == null) return;
        Method dispatch = pickDispatchMethod(dispatcherClass);
        if (dispatch == null) return;
        String base = baseSignature(targetSignature);
        CopyOnWriteArrayList<Method> routes = targetRoutes.computeIfAbsent(
            base, ignored -> new CopyOnWriteArrayList<Method>()
        );
        for (Method route : routes) {
            if (route.getDeclaringClass() == dispatcherClass) return;
        }
        routes.add(dispatch);
        // 句柄解析失败（访问控制等）时保持空白，dispatch 会回落反射 —— 绝不能因此断链。
        MethodHandle handle = resolveRouteHandle(dispatch, null);
        if (handle != null) routeHandles.putIfAbsent(dispatcherClass, handle);
        if (routes.size() > 1 && routeConflictWarnings.putIfAbsent(base, Boolean.TRUE) == null) {
            System.err.println("[Incision][Bridge] multiple dispatchers registered for target=" + base +
                " routes=" + routes.size() + " (cross-plugin priority requires Gate aggregation)");
        }
    }

    /** 仅移除当前插件对指定目标的路由，不影响其他同时安装 Incision 的插件。 */
    public static void unregisterLocalTarget(ClassLoader classLoader, String targetSignature) {
        if (classLoader == null || targetSignature == null) return;
        String base = baseSignature(targetSignature);
        CopyOnWriteArrayList<Method> routes = targetRoutes.get(base);
        if (routes == null) return;
        routes.removeIf(method -> method.getDeclaringClass().getClassLoader() == classLoader);
        if (routes.isEmpty()) targetRoutes.remove(base, routes);
        if (routes.size() <= 1) routeConflictWarnings.remove(base);
    }

    /** 目标签名 → 基础签名（去掉 adviceId 与 phase）的 memo：签名是织入期写死的常量、取值有限，纯函数无需失效。 */
    private static final ConcurrentHashMap<String, String> baseSignatureCache = new ConcurrentHashMap<String, String>();

    private static String baseSignature(String targetSignature) {
        String cached = baseSignatureCache.get(targetSignature);
        if (cached != null) return cached;
        String computed = baseSignatureUncached(targetSignature);
        baseSignatureCache.putIfAbsent(targetSignature, computed);
        return computed;
    }

    private static String baseSignatureUncached(String targetSignature) {
        int hash = targetSignature.indexOf('#');
        String withoutAdvice = hash < 0 ? targetSignature : targetSignature.substring(0, hash);
        int phase = withoutAdvice.lastIndexOf('@');
        return phase < 0 ? withoutAdvice : withoutAdvice.substring(0, phase);
    }

    /** 由 IncisionBootstrap 在 CONST 阶段调用，显式注册经过重定向后的 dispatcher 类 */
    public static void registerLocalDispatcher(Class<?> dispatcherClass) {
        if (dispatcherClass == null) return;
        Method best = pickDispatchMethod(dispatcherClass);
        if (best != null) {
            MethodHandle handle = resolveRouteHandle(best, null);
            if (handle != null) routeHandles.put(dispatcherClass, handle);
            localCache.put(dispatcherClass.getClassLoader(), best);
        }
    }

    /**
     * 把 dispatch 方法编译成归一化路由句柄；任何失败都返回 null，由调用方回落反射。
     *
     * unreflect 要做访问控制检查：跨插件 ClassLoader、声明方非公开、模块不导出等场景都可能失败。
     * 这些场景下必须保留优化前就存在的 Method.invoke 路径 —— 拿不到句柄不是断链的理由。
     *
     * @param receiver 非 null 时按实例方法 bindTo（Gate host 的 dispatch 是实例方法）
     */
    private static MethodHandle resolveRouteHandle(Method method, Object receiver) {
        if (method == null) return null;
        try {
            MethodHandle handle = MethodHandles.lookup().unreflect(method);
            if (receiver != null) handle = handle.bindTo(receiver);
            // 3 参形态补一个被丢弃的第 4 参，与 4 参形态对齐到同一个 MethodType
            return handle.type().parameterCount() == 4
                ? handle.asType(ROUTE_TYPE)
                : MethodHandles.dropArguments(handle, 3, Object.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 执行一条路由：句柄可用时走 MethodHandle，否则走反射。
     *
     * 异常语义必须与优化前完全一致：Method.invoke 会把 handler 抛出的任何 Throwable 包成
     * InvocationTargetException，而 MethodHandle.invoke 会原样抛出。这里显式补回同样的包装，
     * 保证 Bridge 的日志文案与上层可观察行为（含异常类型）不因换用句柄而改变。
     */
    private static Object invokeRoute(
        Method method,
        MethodHandle handle,
        Object receiver,
        String targetSignature,
        Object self,
        Object[] args
    ) throws Throwable {
        if (handle != null) {
            try {
                return handle.invoke(targetSignature, self, args, (Object) null);
            } catch (Throwable t) {
                throw new InvocationTargetException(t);
            }
        }
        return method.getParameterCount() == 4
            ? method.invoke(receiver, targetSignature, self, args, null)
            : method.invoke(receiver, targetSignature, self, args);
    }

    /** 借鉴 MeteorInjector 的 getMethod(clazz, returnType, index) 风格 — 形状优先，名字次之 */
    private static Method pickDispatchMethod(Class<?> cls) {
        Method named3 = null, named4 = null, anyShape3 = null, anyShape4 = null;
        for (Method m : cls.getMethods()) {
            Class<?>[] pt = m.getParameterTypes();
            boolean shape3 = pt.length == 3 && pt[0] == String.class && pt[1] == Object.class && pt[2] == Object[].class;
            boolean shape4 = pt.length == 4 && pt[0] == String.class && pt[1] == Object.class && pt[2] == Object[].class;
            if (m.getName().equals("dispatch")) {
                if (shape3) named3 = m;
                else if (shape4) named4 = m;
            }
            if (anyShape3 == null && shape3) anyShape3 = m;
            if (anyShape4 == null && shape4) anyShape4 = m;
        }
        if (named4 != null) return named4;
        if (named3 != null) return named3;
        return anyShape4 != null ? anyShape4 : anyShape3;
    }

    private static Method resolveLocalSibling(Method local, String methodName) {
        if (local == null) return null;
        try {
            return local.getDeclaringClass().getMethod(methodName, String.class, Object.class, Object[].class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 插件 DISABLE 时调用 — 移除该 ClassLoader 关联的本地 dispatcher 缓存 */
    public static void unregisterLocalDispatcher(ClassLoader cl) {
        if (cl == null) return;
        localCache.remove(cl);
        // 句柄与 Method 一样强引用声明类，必须随 lease 一起释放，否则会拖住插件 ClassLoader。
        for (Class<?> dispatcherClass : new ArrayList<Class<?>>(routeHandles.keySet())) {
            if (dispatcherClass.getClassLoader() == cl) routeHandles.remove(dispatcherClass);
        }
        for (String target : new ArrayList<String>(targetRoutes.keySet())) {
            unregisterLocalTarget(cl, target);
        }
        // 最后一个插件退出后必须断开 Gate 对首个插件 ClassLoader 的强引用；更早解绑会破坏其他 lease。
        if (localCache.isEmpty()) unbindSystemHost();
    }
}
