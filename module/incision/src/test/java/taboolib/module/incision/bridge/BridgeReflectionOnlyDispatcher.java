package taboolib.module.incision.bridge;

/**
 * Bridge 路由调用语义夹具 —— 非公开类。
 *
 * 类本身不是 public：Lookup.unreflect 会因访问控制失败，Bridge 必须回落到优化前就存在的
 * Method.invoke 路径。同一条路径下反射调用本身也会失败（IllegalAccessException），
 * 因此测试锚定的是"兜底路径仍在，且错误信息与直接反射调用完全一致"。
 *
 * 注意：这是**有意的**访问控制边界，不要为了让它可访问而改成 public。
 */
final class BridgeReflectionOnlyDispatcher {

    private BridgeReflectionOnlyDispatcher() {}

    public static Object dispatch(String targetSignature, Object self, Object[] args) {
        return "reflection-route:" + targetSignature;
    }
}
