package taboolib.module.incision.loader

/**
 * 织入后端 — 负责让 weaver 的字节码生效。
 *
 * 运行时后端：
 * - [InstrumentationBackend] 主力：通过 java.lang.instrument 跨 ClassLoader retransform
 * - [PipelineBackend] NMSProxy 兼容：接入 TabooLib RemapTranslation 的额外 transformer 链
 *
 * [ClassLoaderHookBackend] 仅作为旧 API 占位保留。Java 无法在不使用 agent/JVMTI 的前提下
 * 替换任意既有 ClassLoader 的 defineClass，因此它必须明确报告不可用，不能参与能力选择。
 */
interface Backend {

    /** 安装结果必须区分真正生效、等待加载、已回滚失败和后端不可用，诊断不得再用布尔值猜测。 */
    enum class InstallStatus { INSTALLED, PENDING_LOAD, FAILED_ROLLED_BACK, UNAVAILABLE }

    data class Installation(
        val status: InstallStatus,
        val token: BackendToken? = null,
        val reason: String? = null,
    )

    val name: String

    fun available(): Boolean

    /** 为指定类名注册 transformer；返回可用于取消的 token */
    fun addTransformer(className: String, transformer: (ByteArray) -> ByteArray?): BackendToken

    /** 立即触发一次 retransform（若支持） */
    fun retransform(className: String): Boolean = false

    /** true/false 表示已加载/待加载，null 表示后端无法可靠判断。 */
    fun isClassLoaded(className: String): Boolean? = null

    /**
     * 事务式注册入口：即时重转换失败时必须先移除 transformer，再向上层返回失败。
     */
    fun install(className: String, transformer: (ByteArray) -> ByteArray?): Installation {
        if (!available()) return Installation(InstallStatus.UNAVAILABLE, reason = "$name unavailable")
        var attempted = false
        var failure: Throwable? = null
        val guarded: (ByteArray) -> ByteArray? = { bytes ->
            attempted = true
            try {
                transformer(bytes) ?: throw IllegalStateException("weaver returned null")
            } catch (t: Throwable) {
                failure = t
                throw t
            }
        }
        val token = addTransformer(className, guarded)
        if (isClassLoaded(className) == false) {
            return Installation(InstallStatus.PENDING_LOAD, token, "target class is not loaded")
        }
        val retransformed = retransform(className.replace('/', '.'))
        if (retransformed && attempted && failure == null) return Installation(InstallStatus.INSTALLED, token)
        token.remove()
        if (attempted) runCatching { retransform(className.replace('/', '.')) }
        return Installation(
            InstallStatus.FAILED_ROLLED_BACK,
            reason = failure?.let { "${it.javaClass.name}: ${it.message}" }
                ?: "retransform=$retransformed attempted=$attempted",
        )
    }

    interface BackendToken {
        fun remove()
    }
}
