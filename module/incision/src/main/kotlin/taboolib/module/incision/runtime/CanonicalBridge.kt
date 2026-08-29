package taboolib.module.incision.runtime

import taboolib.module.incision.diagnostic.Forensics

/**
 * 当前插件对 JVM 唯一 IncisionBridge 的反射句柄。
 *
 * 同名 Bridge 可能同时存在于插件与 bootstrap ClassLoader；业务代码若直接调用静态方法，
 * JVM 会在链接时永久绑定到其中一个版本。这里强制所有 lease 与目标路由操作都落到启动期
 * 选定的 canonical 类，避免出现日志显示两个 lease、实际却写入另一份静态表的分裂状态。
 */
internal object CanonicalBridge {

    /** 仅在 Bridge 注入彻底失败时使用；该场景本来就不支持跨 ClassLoader 织入。 */
    private val fallbackBypassMiss = Any()

    @Volatile
    private var bridgeClass: Class<*>? = null

    /** 只能在 Bridge 注入完成后绑定；重复绑定同一个类是幂等的。 */
    fun bind(type: Class<*>) {
        bridgeClass = type
    }

    fun registerDispatcher(dispatcherClass: Class<*>) {
        invoke("registerLocalDispatcher", arrayOf<Class<*>>(Class::class.java), dispatcherClass)
    }

    fun registerNativeBackend(backendClass: Class<*>, ownsNative: Boolean): Boolean =
        invokeResult(
            "registerNativeBackend",
            arrayOf<Class<*>>(Class::class.java, Boolean::class.javaPrimitiveType!!),
            backendClass,
            ownsNative,
        ) as? Boolean ?: false

    fun transformNative(loader: ClassLoader?, name: String, bytes: ByteArray): ByteArray? =
        invokeResult(
            "transformNative",
            arrayOf<Class<*>>(ClassLoader::class.java, String::class.java, ByteArray::class.java),
            loader,
            name,
            bytes,
        ) as? ByteArray

    fun invokeNative(operation: String, vararg args: Any?): Any? =
        invokeResult(
            "invokeNative",
            arrayOf<Class<*>>(String::class.java, Array<Any?>::class.java),
            operation,
            args,
        )

    fun unregisterNativeBackend(backendClass: Class<*>) {
        invoke("unregisterNativeBackend", arrayOf<Class<*>>(Class::class.java), backendClass)
    }

    fun unregisterDispatcher(classLoader: ClassLoader) {
        invoke("unregisterLocalDispatcher", arrayOf<Class<*>>(ClassLoader::class.java), classLoader)
    }

    fun registerTarget(dispatcherClass: Class<*>, targetSignature: String) {
        invoke(
            "registerLocalTarget",
            arrayOf<Class<*>>(Class::class.java, String::class.java),
            dispatcherClass,
            targetSignature,
        )
    }

    fun unregisterTarget(classLoader: ClassLoader, targetSignature: String) {
        invoke(
            "unregisterLocalTarget",
            arrayOf<Class<*>>(ClassLoader::class.java, String::class.java),
            classLoader,
            targetSignature,
        )
    }

    fun bindSystemHost(host: Any) {
        invoke("bindSystemHost", arrayOf<Class<*>>(Any::class.java), host)
    }

    /** 返回 JVM canonical Bridge 当前持有的插件 lease 数，而不是当前重定位副本的本地状态。 */
    fun localLeaseCount(): Int =
        (invokeResult("localLeaseCount", emptyArray<Class<*>>()) as? Number)?.toInt() ?: 0

    /** Bypass sentinel 必须来自 canonical Bridge，引用身份才能跨插件 ClassLoader 保持一致。 */
    fun bypassMiss(): Any =
        invokeResult("bypassMiss", emptyArray<Class<*>>()) ?: fallbackBypassMiss

    private fun invoke(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?) {
        invokeResult(name, parameterTypes, *args)
    }

    private fun invokeResult(name: String, parameterTypes: Array<Class<*>>, vararg args: Any?): Any? {
        val type = bridgeClass
        if (type == null) {
            Forensics.warn("Incision canonical Bridge 尚未绑定，忽略操作: $name")
            return null
        }
        return try {
            type.getMethod(name, *parameterTypes).invoke(null, *args)
        } catch (t: Throwable) {
            Forensics.warn("Incision canonical Bridge 调用失败: method=$name reason=${t.message}")
            null
        }
    }
}
