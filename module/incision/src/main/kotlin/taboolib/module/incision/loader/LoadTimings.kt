package taboolib.module.incision.loader

import java.util.concurrent.ConcurrentHashMap

/**
 * Incision 各加载阶段的耗时记录 — **这是给端到端验收用的观测点**。
 *
 * 由 [taboolib.module.incision.IncisionBootstrap] 在 CONST / ENABLE 阶段写入，
 * 由 `taboolib.module.incision.test.TestIncision` 在服务端主线程读取，
 * 使「模块加载速度」不再需要人工解析日志。
 *
 * 设计约束：
 * - 只记录、不参与任何加载决策；[timed] 的异常照常向外抛出，不改动既有行为。
 * - 与 Bootstrap 分离，避免测试读取耗时反过来触发 Bootstrap 初始化而伪造出「已加载」的假象。
 * - 阶段名按 `阶段/子步骤` 组织，[CONST] / [ENABLE] 为阶段总耗时（含其间的未单独计时的代码）。
 */
object LoadTimings {

    /** CONST 阶段（对象初始化 → [taboolib.module.incision.IncisionBootstrap.prepareConst]）总耗时 */
    const val CONST = "CONST"

    /** CONST 子步骤：asm-tree 运行时可用性自检 */
    const val CONST_ASM_PROBE = "CONST/asmProbe"

    /** CONST 子步骤：接入 TabooLib NMS resolver */
    const val CONST_NMS_RESOLVER = "CONST/nmsResolver"

    /** CONST 子步骤：安装反射穿透适配器 */
    const val CONST_REFLEX_ADAPTER = "CONST/reflexAdapter"

    /** CONST 子步骤：向 bootstrap / system ClassLoader 注入 IncisionBridge（含 native 加载与 Instrumentation append） */
    const val CONST_BRIDGE_INJECT = "CONST/bridgeInject"

    /** CONST 子步骤：接入 NMSProxy 生成期管线 */
    const val CONST_PIPELINE = "CONST/pipelineBackend"

    /** ENABLE 阶段（[taboolib.module.incision.IncisionBootstrap.onEnable]）总耗时 */
    const val ENABLE = "ENABLE"

    /** ENABLE 子步骤：接入 / 创建 IncisionGate */
    const val ENABLE_GATE = "ENABLE/gate"

    /** ENABLE 子步骤：startup checkup */
    const val ENABLE_CHECKUP = "ENABLE/checkup"

    /** ENABLE 子步骤：全量冲突分析 */
    const val ENABLE_CONFLICT = "ENABLE/conflict"

    /** ENABLE 子步骤：retransform 后端可用性探测（可能触发 self-attach 与 native 加载） */
    const val ENABLE_BACKEND = "ENABLE/backend"

    /**
     * 阶段名 → 耗时（纳秒）。
     *
     * 同一阶段被重复执行时以最后一次为准；写入来自不同生命周期阶段（可能不同线程），故使用并发容器。
     */
    private val phases = ConcurrentHashMap<String, Long>()

    /**
     * 记录一次阶段耗时（纳秒）。
     *
     * 计时属于诊断路径，任何异常都不得反向中断加载流程。
     */
    fun record(phase: String, nanos: Long) {
        try {
            phases[phase] = if (nanos > 0L) nanos else 0L
        } catch (_: Throwable) {
            // 忽略：观测点失败不影响加载
        }
    }

    /**
     * 执行 [block] 并记录其耗时到 [phase]。
     *
     * block 抛出的异常照常向外抛出（耗时在 finally 中记录），因此不改变既有行为。
     */
    inline fun <T> timed(phase: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(phase, System.nanoTime() - start)
        }
    }

    /** 是否已记录该阶段 — 用于区分「模块没跑起来」与「耗时恰好为 0」。 */
    fun isRecorded(phase: String): Boolean = phases.containsKey(phase)

    /** 读取指定阶段耗时（毫秒）；未记录时返回 `-1`。 */
    fun millis(phase: String): Long {
        val nanos = phases[phase] ?: return -1L
        return nanos / 1_000_000L
    }

    /** 只读快照，供日志 / 诊断输出使用。 */
    fun snapshot(): Map<String, Long> = HashMap(phases)
}
