package taboolib.module.incision.loader

/**
 * 旧 ClassLoader Hook 后端的兼容占位。
 *
 * JVM 没有可移植 API 可以替换一个已经存在的 ClassLoader 的 defineClass 实现；旧版本仅把
 * transformer 转交给 Instrumentation，却仍无条件报告可用，会造成后端能力虚报。保留该对象
 * 是为了避免二进制引用立刻失效，但所有新调用都应根据 [available] 的 false 结果回退。
 */
@Deprecated("ClassLoader hook cannot transform bytecode without Instrumentation or JVMTI")
object ClassLoaderHookBackend : Backend {

    override val name: String = "ClassLoaderHook"

    override fun available(): Boolean = false

    override fun addTransformer(className: String, transformer: (ByteArray) -> ByteArray?): Backend.BackendToken {
        throw UnsupportedOperationException("ClassLoaderHook backend is unavailable without Instrumentation or JVMTI")
    }
}
