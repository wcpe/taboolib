package taboolib.module.incision.loader

import taboolib.module.incision.diagnostic.Forensics
import taboolib.module.incision.diagnostic.Trauma
import taboolib.module.incision.loader.attach.ManualSelfAttach
import taboolib.module.incision.loader.attach.ByteBuddyAttacher
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Instrumentation 后端 — 主力路径。
 *
 * 启动时尝试 self-attach 拿到 Instrumentation：
 * 1. 优先 ByteBuddyAttacher（如果 byte-buddy-agent 在 classpath）
 * 2. 兜底 ManualSelfAttach（JDK 8 走 tools.jar；JDK 9+ 走 jdk.attach 模块）
 *
 * 拿到后注册一个 ClassFileTransformer，按 className 路由到具体 transformer。
 */
object InstrumentationBackend : Backend {

    @Volatile
    private var inst: Instrumentation? = null

    @Volatile
    private var resolveFailed = false

    private val transformers = ConcurrentHashMap<String, CopyOnWriteArrayList<(ByteArray) -> ByteArray?>>()

    override val name: String = "Instrumentation"

    override fun available(): Boolean = ensure() != null

    fun ensure(): Instrumentation? {
        inst?.let { return it }
        if (resolveFailed) return null
        synchronized(this) {
            inst?.let { return it }
            if (resolveFailed) return null
            val resolved = resolve()
            if (resolved == null) {
                resolveFailed = true
                return null
            }
            inst = resolved
            register(resolved)
            return resolved
        }
    }

    private fun resolve(): Instrumentation? {
        // self-attach 在大多数 Minecraft 服务端（Paper / Spigot）被默认禁用，是预期路径。
        // 只在 -Dtaboolib.incision.debug=true 时打印；否则静默回退到 ASM-only 路径。
        return try {
            ByteBuddyAttacher.tryInstall()
        } catch (t: Throwable) {
            Forensics.debug("ByteBuddyAttacher 不可用: ${t.message}")
            null
        } ?: try {
            ManualSelfAttach.attach()
        } catch (t: Trauma.Attach) {
            Forensics.debug("ManualSelfAttach 不可用: ${t.message}")
            null
        } catch (t: Throwable) {
            Forensics.debug("ManualSelfAttach 不可用: ${t.message}")
            null
        }
    }

    private fun register(i: Instrumentation) {
        i.addTransformer(object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?, className: String?,
                classBeingRedefined: Class<*>?, protectionDomain: ProtectionDomain?,
                classfileBuffer: ByteArray,
            ): ByteArray? {
                if (className == null) return null
                val list = transformers[className] ?: return null
                var bytes = classfileBuffer
                val prev = taboolib.module.incision.weaver.Scalpel.currentTransformLoader.get()
                taboolib.module.incision.weaver.Scalpel.currentTransformLoader.set(loader)
                try {
                    for (t in list) {
                        val out = try { t(bytes) } catch (e: Throwable) {
                            Forensics.error("transformer 执行失败: $className", e); null
                        }
                        if (out != null) bytes = out
                    }
                } finally {
                    taboolib.module.incision.weaver.Scalpel.currentTransformLoader.set(prev)
                }
                return if (bytes === classfileBuffer) null else bytes
            }
        }, true)
    }

    override fun addTransformer(className: String, transformer: (ByteArray) -> ByteArray?): Backend.BackendToken {
        val key = className.replace('.', '/')
        transformers.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(transformer)
        return object : Backend.BackendToken {
            override fun remove() { transformers[key]?.remove(transformer) }
        }
    }

    override fun retransform(className: String): Boolean {
        val i = ensure() ?: return false
        val cls = findClass(className) ?: return false
        if (!i.isRetransformClassesSupported || !i.isModifiableClass(cls)) {
            Forensics.warn("retransform 不受支持: $className (supported=${i.isRetransformClassesSupported}, modifiable=${i.isModifiableClass(cls)})")
            return false
        }
        return try { i.retransformClasses(cls); true } catch (e: Throwable) {
            Forensics.warn("retransform 失败: $className — ${e.message}"); false
        }
    }

    private fun findClass(className: String): Class<*>? {
        // 1. context classloader
        try { return Class.forName(className, false, Thread.currentThread().contextClassLoader) } catch (_: Throwable) {}
        // 2. all loaded classes via Instrumentation
        val i = inst ?: return null
        val internalName = className.replace('.', '/')
        return i.allLoadedClasses.firstOrNull { it.name == className || it.name.replace('.', '/') == internalName }
    }

    override fun isClassLoaded(className: String): Boolean {
        val binaryName = className.replace('/', '.')
        return ensure()?.allLoadedClasses?.any { it.name == binaryName } == true
    }

    /**
     * 返回当前 Instrumentation 可见类的快照。pointcut 的 GLOB owner 只能在有界的已加载类集合中展开，
     * 不能通过扫描 classpath 猜测尚未定义的同名类。
     */
    fun loadedClasses(): List<Class<*>> = ensure()?.allLoadedClasses?.toList().orEmpty()

    fun clearTransformers() = transformers.clear()
}
