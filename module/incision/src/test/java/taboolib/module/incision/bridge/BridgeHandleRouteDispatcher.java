package taboolib.module.incision.bridge;

/**
 * Bridge 路由调用语义夹具 —— 公开类。
 *
 * 公开类 + 公开静态 dispatch：Lookup.unreflect 能够成功，因此 Bridge 走 MethodHandle 路由。
 * 测试用它锚定"handler 抛出的异常仍以 InvocationTargetException 形态出现"这一与反射路径一致的语义。
 *
 * 测试通过 child-first ClassLoader 定义本类副本，路由键（ClassLoader）与其他用例互不干扰。
 */
public final class BridgeHandleRouteDispatcher {

    /** 路由标记：用于确认调用确实到达了本夹具 */
    public static final String MARKER = "handle-route";

    private static volatile boolean throwing;

    private BridgeHandleRouteDispatcher() {}

    /** 由测试在调用前设置。 */
    public static void setThrowing(boolean value) {
        throwing = value;
    }

    public static Object dispatch(String targetSignature, Object self, Object[] args) {
        if (throwing) {
            throw new IllegalStateException("handler-boom");
        }
        return MARKER + ':' + targetSignature;
    }
}
