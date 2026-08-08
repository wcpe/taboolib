package taboolib.module.incision.diagnostic

import taboolib.common.PrimitiveSettings
import taboolib.common.platform.function.debug as platformDebug
import taboolib.common.platform.function.info as platformInfo
import taboolib.common.platform.function.warning as platformWarning

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
        if (DEBUG) emitSafely("[Incision] $message", false) { platformInfo(it) }
    }

    fun debug(message: String) {
        if (DEBUG) emitSafely("[Incision][DEBUG] $message", false) { platformDebug(it) }
    }

    fun warn(message: String) {
        if (DEBUG) emitSafely("[Incision][WARN] $message", true) { platformWarning(it) }
    }

    fun error(message: String, cause: Throwable? = null) {
        System.err.println("[Incision][ERROR] $message")
        cause?.printStackTrace(System.err)
    }

    /**
     * CONST 阶段可能早于 BukkitPlugin 实例完成构造，平台日志实现此时会访问尚未就绪的插件实例。
     * 诊断路径绝不能反向中断 Incision 初始化，因此平台输出失败时只退回 JDK 标准流。
     */
    private fun emitSafely(message: String, stderr: Boolean, platformLog: (String) -> Unit) {
        try {
            platformLog(message)
        } catch (_: Throwable) {
            if (stderr) System.err.println(message) else System.out.println(message)
        }
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
