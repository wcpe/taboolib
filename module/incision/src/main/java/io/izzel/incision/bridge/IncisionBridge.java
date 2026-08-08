package io.izzel.incision.bridge;

import java.lang.reflect.Field;
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
 * 本类通过反射跨 ClassLoader 调用 dispatch，避免类型一致性问题。
 */
public final class IncisionBridge {

    private IncisionBridge() {}

    private static final Object BYPASS_MISS = new Object();

    /** 系统 ClassLoader 上的宿主类（若存在） */
    private static volatile Object systemHost = findSystemHost();

    /** 宿主的 dispatch 方法反射缓存 */
    private static volatile Method systemDispatch = resolveDispatch(systemHost);

    /** ClassLoader → 本地 TheatreDispatcher.dispatch Method 缓存（单插件 fallback 路径） */
    private static final ConcurrentHashMap<ClassLoader, Method> localCache = new ConcurrentHashMap<ClassLoader, Method>();

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
                    Object localResult;
                    if (local.getParameterCount() == 4) {
                        localResult = local.invoke(null, targetSignature, self, args, null);
                    } else {
                        localResult = local.invoke(null, targetSignature, self, args);
                    }
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
                return m.invoke(host, targetSignature, self, args);
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
        systemHost = host;
        systemDispatch = resolveDispatch(host);
    }

    public static synchronized void unbindSystemHost() {
        systemHost = null;
        systemDispatch = null;
    }

    public static boolean hasSystemHost() {
        return systemHost != null && systemDispatch != null;
    }

    /** JVM 级 lease 数量；Gate holder 只能在该值归零后释放共享 delegate。 */
    public static int localLeaseCount() {
        return localCache.size();
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

    private static String baseSignature(String targetSignature) {
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
            localCache.put(dispatcherClass.getClassLoader(), best);
        }
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
        for (String target : new ArrayList<String>(targetRoutes.keySet())) {
            unregisterLocalTarget(cl, target);
        }
        // 最后一个插件退出后必须断开 Gate 对首个插件 ClassLoader 的强引用；更早解绑会破坏其他 lease。
        if (localCache.isEmpty()) unbindSystemHost();
    }
}
