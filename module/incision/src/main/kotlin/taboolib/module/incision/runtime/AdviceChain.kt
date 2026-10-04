package taboolib.module.incision.runtime

import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.api.Theatre
import taboolib.module.incision.pred.Predicate
import taboolib.module.incision.weaver.site.SiteSpec

/**
 * Advice 类型。
 */
enum class AdviceKind { LEAD, TRAIL, SPLICE, GRAFT, BYPASS, TRIM, EXCISE }

/**
 * 运行时单条 advice 记录 — dispatcher 表中的条目。
 */
data class AdviceEntry(
    val id: String,
    val kind: AdviceKind,
    val target: MethodCoordinate,
    val priority: Int,
    val handler: (Theatre) -> Any?,
    val predicate: ((Theatre) -> Boolean)? = null,
    /**
     * 编译后的字符串谓词（来自 DSL 的 `where("...")` 字面量）。
     * 与 [predicate] 互不影响：dispatcher 先 test 此字段，再 test 闭包 [predicate]。
     * 解析与编译在 advice 注册期完成；运行期零反射、零 AST 遍历。
     */
    val compiledPredicate: Predicate? = null,
    /** [compiledPredicate] 的源码，仅用于诊断输出。 */
    val predicateSource: String? = null,
    val pluginName: String = "",
    val classLoader: java.lang.ref.WeakReference<ClassLoader>? = null,
    val explicitResumeRequired: Boolean = false,
    val sourceKind: String = "dsl",
    val aliasGroup: String? = null,
    val siteSpec: SiteSpec? = null,
    /** TRAIL 是否在异常出口 (ATHROW) 触发；默认 true。对非 TRAIL 无效。 */
    val onThrow: Boolean = true,
    @Volatile var enabled: Boolean = true,
)

/**
 * 单个目标方法的 advice 链 — 按优先级降序，同优先级按注册顺序。
 *
 * 读多写少：dispatch 每次调用都要取一次条目表，而增删只发生在注册 / 卸载期。
 * 因此内部不暴露可变集合，而是"变更时整体重建 + volatile 发布不可变快照"：
 * 读侧零拷贝、零锁，写侧用一把锁保证"改条目"与"发布快照"是同一个原子步骤
 * （否则并发增删会互相覆盖，发布出永远停在旧状态的快照）。
 */
class AdviceChain(val target: MethodCoordinate) {

    /** 写侧互斥锁；只用于 add / remove，热路径（[list]）完全不碰。 */
    private val writeLock = Any()

    private val entries = ArrayList<AdviceEntry>()

    /** 读侧快照。每次变更后整体替换；读方拿到的引用在其使用期间内容不再变化。 */
    @Volatile
    private var snapshot: List<AdviceEntry> = emptyList()

    fun add(entry: AdviceEntry) {
        synchronized(writeLock) {
            // 聚合计划重装会再次同步逻辑/运行时别名；同一 id 必须替换而不是重复执行。
            entries.removeIf { it.id == entry.id }
            entries.add(entry)
            entries.sortByDescending { it.priority }
            publish()
        }
    }

    fun remove(id: String): Boolean = synchronized(writeLock) {
        val removed = entries.removeIf { it.id == id }
        if (removed) publish()
        removed
    }

    fun removeByClassLoader(cl: ClassLoader): Int = synchronized(writeLock) {
        var n = 0
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val held = iterator.next().classLoader?.get()
            if (held === cl) {
                iterator.remove()
                n++
            }
        }
        if (n > 0) publish()
        n
    }

    /**
     * 热路径读入口：直接返回当前快照，不再逐次拷贝。
     *
     * 返回的是**不可修改视图**：调用方拿到的不是私有副本，任何改动都必须失败
     * （抛出 [UnsupportedOperationException]），而不是静默污染整条链。
     */
    fun list(): List<AdviceEntry> = snapshot

    fun isEmpty(): Boolean = snapshot.isEmpty()

    /** 在锁内重建并发布快照：先复制成独立副本，再包成不可修改视图。 */
    private fun publish() {
        snapshot = java.util.Collections.unmodifiableList(ArrayList(entries))
    }
}
