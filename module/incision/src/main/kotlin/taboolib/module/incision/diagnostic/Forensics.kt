package taboolib.module.incision.diagnostic

import taboolib.common.PrimitiveSettings
import taboolib.common.platform.function.warning

/**
 * 结构化诊断日志器。
 *
 * 所有 incision 内部日志都走该入口，固定字段格式便于 grep:
 * `[Incision][<Phase>] id=... target=... resolver=... result=... took=...ms`
 *
 * 非错误输出仅在 TabooLib debug 模式下可见。
 */
object Forensics {

    /** 是否开启调试输出 — 跟随 TabooLib 的 PrimitiveSettings debug 开关。 */
    val DEBUG: Boolean
        get() = PrimitiveSettings.IS_DEBUG_MODE

    fun info(message: String) {
        if (DEBUG) emit("[Incision] $message") { taboolib.common.platform.function.info(it) }
    }

    fun debug(message: String) {
        if (DEBUG) emit("[Incision][DEBUG] $message") { taboolib.common.platform.function.debug(it) }
    }

    fun warn(message: String) {
        if (DEBUG) emit("[Incision][WARN] $message") { warning(it) }
    }

    /**
     * 安全地输出诊断日志。
     *
     * info/debug/warn 在 debug 模式下会转调平台日志函数，但这些函数在 CONST 等极早期生命周期
     * 会尝试解析尚未注册的插件实例（如 BukkitIO.getPlugin()）而抛出 NPE；若放任其在 object
     * 初始化（<clinit>）中抛出，会升级为 ExceptionInInitializerError 拖垮整个 incision 模块。
     *
     * 诊断日志绝不应反过来把宿主搞崩，故此处对平台调用兜底：平台未就绪时退化为标准输出，
     * 既不影响宿主初始化，又能保证 debug 输出仍然可见。
     */
    private inline fun emit(message: String, platform: (String) -> Unit) {
        try {
            platform(message)
        } catch (t: Throwable) {
            // 平台日志器尚未就绪 — 退化为标准输出
            println(message)
        }
    }

    fun error(message: String, cause: Throwable? = null) {
        System.err.println("[Incision][ERROR] $message")
        cause?.printStackTrace(System.err)
    }

    /**
     * 上报 Trauma — 输出结构化字段 + 触发栈。
     */
    fun report(trauma: Trauma) {
        System.err.println(buildString {
            append("[Incision][Trauma][").append(trauma.phase).append("] ")
            trauma.incisionId?.let { append("id=").append(it).append(' ') }
            trauma.target?.let { append("target=").append(it).append(' ') }
            trauma.surgeonClass?.let { append("surgeon=").append(it).append(' ') }
            trauma.resolverName?.let { append("resolver=").append(it).append(' ') }
            append("\n  reason: ").append(trauma.message)
            var c = trauma.cause
            while (c != null) {
                append("\n  cause : ").append(c)
                c = c.cause
            }
        })
    }
}
