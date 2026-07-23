package taboolib.module.nms

/**
 * 版本适配分发器
 * 用于在同一份调用代码中延迟选择可用的 NMS 实现，并在首次命中后缓存策略。
 * 旧版 lambda 用法仍然可用，适合只需要“能构造就用”的简单分支。
 * 当某个高版本实现可能在低版本也能被反射探测命中、但语义不兼容时，应使用 [versionStrategy]
 * 显式声明 [Strategy.guard]，避免“能跑但选错”的静默错误。
 *
 * 用法（以 R 为函数类型实现零开销参数传递）：
 * ```kotlin
 * // 旧用法：R 为函数类型，策略 lambda 返回函数引用
 * val impl = versionAdaptor<(Player, Int, Int) -> Boolean>(
 *     { { p, cx, cz -> NMS20p.isChunkSent(p, cx, cz) } },
 *     { { p, cx, cz -> NMS19p.isChunkSent(p, cx, cz) } },
 *     { { p, cx, cz -> legacyCheck(p, cx, cz) } }
 * )
 *
 * // 带版本门禁与诊断名的用法
 * val guardedImpl = versionAdaptor<(Player, Int, Int) -> Boolean>(
 *     versionStrategy("nms21", guard = { MinecraftVersion.versionId >= 12005 }) {
 *         { p, cx, cz -> NMS21.isChunkSent(p, cx, cz) }
 *     },
 *     versionStrategy("nms20", guard = { MinecraftVersion.versionId >= 12002 }) {
 *         { p, cx, cz -> NMS20p.isChunkSent(p, cx, cz) }
 *     },
 * )
 *
 * // 使用：首次调用确定策略，后续零开销
 * fun isChunkVisible(player: Player, chunkX: Int, chunkZ: Int): Boolean {
 *     return impl()(player, chunkX, chunkZ)
 * }
 * ```
 *
 * @author sky
 */
class VersionAdaptor<R>(val strategies: List<Strategy<R>>) {

    /**
     * 一个候选版本实现。
     * guard 用于显式声明版本边界，避免高版本实现因反射探测成功而误匹配低版本。
     */
    class Strategy<R>(val name: String, val guard: () -> Boolean, val factory: () -> R)

    @Volatile
    var resolved: Strategy<R>? = null

    @Volatile
    var selectedName = "unresolved"

    /**
     * 执行分发
     * 首次调用时按声明顺序遍历候选策略，先检查 guard，再尝试构造实现。
     * 第一个 guard 通过且不抛异常的策略会被缓存，并把 [selectedName] 更新为该策略名称。
     * 后续调用直接使用缓存的策略，无 try-catch 开销。
     */
    operator fun invoke(): R {
        resolved?.let { return it.factory() }
        synchronized(this) {
            resolved?.let { return it.factory() }
            for (strategy in strategies) {
                try {
                    if (!strategy.guard()) {
                        continue
                    }
                    val result = strategy.factory()
                    resolved = strategy
                    selectedName = strategy.name
                    return result
                } catch (_: Throwable) {
                }
            }
        }
        error("No suitable version implementation found")
    }
}

/**
 * 创建版本适配分发器
 */
fun <R> versionAdaptor(vararg strategies: () -> R): VersionAdaptor<R> {
    return VersionAdaptor(strategies.mapIndexed { index, strategy -> VersionAdaptor.Strategy("strategy-$index", { true }, strategy) })
}

/**
 * 创建带名称与版本门禁的版本适配策略
 * name 会在 VersionAdaptor.selectedName 中暴露，便于诊断实际命中的分支。
 */
fun <R> versionStrategy(name: String, guard: () -> Boolean = { true }, factory: () -> R): VersionAdaptor.Strategy<R> {
    return VersionAdaptor.Strategy(name, guard, factory)
}

/**
 * 创建带显式策略对象的版本适配分发器
 */
fun <R> versionAdaptor(vararg strategies: VersionAdaptor.Strategy<R>): VersionAdaptor<R> {
    return VersionAdaptor(strategies.toList())
}
