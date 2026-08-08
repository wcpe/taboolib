package taboolib.module.incision.api

import org.tabooproject.reflex.Reflex.Companion.invokeMethod
import taboolib.common.util.unsafeLazy
import taboolib.module.incision.diagnostic.Forensics
import kotlin.reflect.KClass

/**
 * 版本匹配器 —— 抽象"如何取当前版本"以及"如何判断版本是否落在区间内"。
 *
 * 默认实现 [MinecraftVersionMatcher] 通过 Bukkit 服务端版本字符串读取当前 Minecraft 版本。
 *
 * 外部插件可实现该接口，从自己的 manifest / 配置 / API 拿到版本字符串，
 * 然后在 `@Version(matcher = "com.foo.MyMatcher")` 中指定 FQCN 接入。
 */
interface VersionMatcher {

    /**
     * 取当前运行环境下的版本字符串，例如 "1.20.4"。
     * 返回 null 表示无法获取 —— 此时 [matches] 应直接返回 true（不过滤）。
     */
    fun current(): String?

    /**
     * 判断 [current] 是否落在 [start, end] 闭区间。空字符串端点视为无界。
     * 默认实现：dot-decimal 段比较；缺失段视为 0。
     */
    fun matches(start: String, end: String): Boolean {
        val cur = current() ?: return false
        if (start.isNotBlank() && compare(cur, start) < 0) return false
        if (end.isNotBlank() && compare(cur, end) > 0) return false
        return true
    }

    /** dot-decimal 段比较：1.20 < 1.20.1 < 1.21；非数字段按字典序比较。 */
    fun compare(a: String, b: String): Int {
        val sa = splitSegments(a)
        val sb = splitSegments(b)
        val n = maxOf(sa.size, sb.size)
        for (i in 0 until n) {
            val x = sa.getOrNull(i) ?: 0
            val y = sb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun splitSegments(s: String): List<Int> {
        // 兼容形如 "1.20.4-SNAPSHOT" 的非纯数字尾巴：忽略尾巴、按数字段比较
        return s.split('.', '-', '_', '+').mapNotNull { it.toIntOrNull() }
    }
}

/**
 * 永远命中的版本匹配器 —— 不过滤任何 advice。
 */
object NoopVersionMatcher : VersionMatcher {
    override fun current(): String? = null
    override fun matches(start: String, end: String): Boolean = true
}

/**
 * 默认匹配器 —— 直接从 Bukkit 服务端版本字符串中读取当前 Minecraft 版本。
 */
object MinecraftVersionMatcher : VersionMatcher {

    val runningVersion: String? by unsafeLazy {
        try {
            val versionString = Class.forName("org.bukkit.Bukkit").invokeMethod<Any>("getServer", isStatic = true)!!.invokeMethod<String>("getVersion")!!
            val version = versionString.split("MC:")[1]
            version.substring(0, version.length - 1).split(" ")[1].trim()
        } catch (_: Throwable) {
            null
        }
    }

    override fun current(): String? = runningVersion
}

/**
 * 全局 matcher 仓库 —— 按 FQCN 缓存外部用户自定义匹配器实例。
 *
 * 解析顺序：
 * 1. FQCN 为空 → [MinecraftVersionMatcher]
 * 2. 找到 Kotlin object（INSTANCE 字段）→ 复用
 * 3. 否则尝试无参构造
 * 4. 全部失败 → 警告并回退 [NoopVersionMatcher]
 */
object VersionMatchers {

    /** ClassValue 随 matcher defining ClassLoader 生命周期回收，天然隔离不同插件的同名类。 */
    private val cache = object : ClassValue<VersionMatcher>() {
        override fun computeValue(cls: Class<*>): VersionMatcher {
            return try {
            val obj = runCatching { cls.getField("INSTANCE").get(null) as? VersionMatcher }.getOrNull()
            obj ?: (cls.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as VersionMatcher)
        } catch (t: Throwable) {
                Forensics.warn("VersionMatcher 解析失败: ${cls.name} — ${t.javaClass.name}: ${t.message}; fail-closed")
            NoopVersionMatcher
        }
        }
    }

    fun resolve(type: KClass<out VersionMatcher>): VersionMatcher = cache.get(type.java)

    fun resolve(type: Class<out VersionMatcher>): VersionMatcher = cache.get(type)

    /**
     * 多 ClassLoader 探查 —— 顺序：thread context → VersionMatchers 自身 CL → system CL。
     *
     * 必要性：incision 模块由 IsolatedClassLoader 加载，看不到承载它的插件类。
     * 用户在自己插件里写 @Version(matcher = "com.foo.MyMatcher") 时，MyMatcher 在插件 CL，
     * 必须穿透 system / context CL 才能解析到。
     */
    fun resolve(fqcn: String, definingLoader: ClassLoader): VersionMatcher {
        if (fqcn.isBlank()) return MinecraftVersionMatcher
        val cls = try { Class.forName(fqcn, true, definingLoader) } catch (t: Throwable) {
            Forensics.warn("VersionMatcher 加载失败: $fqcn — ${t.message}; fail-closed")
            return NoopVersionMatcher
        }
        return cache.get(cls)
    }

    private fun loadClass(fqcn: String): Class<*> {
        val candidates = sequenceOf(
            Thread.currentThread().contextClassLoader,
            VersionMatchers::class.java.classLoader,
            ClassLoader.getSystemClassLoader(),
        ).filterNotNull().distinct()
        var lastError: Throwable? = null
        for (cl in candidates) {
            try {
                return Class.forName(fqcn, true, cl)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: ClassNotFoundException(fqcn)
    }
}
