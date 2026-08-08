package taboolib.module.incision.bridge;

/**
 * Bridge 路由测试夹具；测试会把同一份字节码定义到两个隔离 ClassLoader，模拟两个插件 lease。
 */
public final class BridgeFixtureDispatcher {

    private static String acceptedTarget;

    private BridgeFixtureDispatcher() {}

    public static Object dispatch(String targetSignature, Object self, Object[] args) {
        return targetSignature.startsWith(acceptedTarget) ? BridgeFixtureDispatcher.class.getClassLoader() : null;
    }

    public static Object dispatchBypass(String targetSignature, Object self, Object[] args) {
        return dispatch(targetSignature, self, args);
    }

    public static void configure(String targetSignature) {
        acceptedTarget = targetSignature;
    }
}
