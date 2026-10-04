package taboolib.module.incision.test

import taboolib.common.Inject
import taboolib.common.Test
import taboolib.module.incision.IncisionBootstrap
import taboolib.module.incision.loader.InstrumentationBackend
import taboolib.module.incision.loader.JvmtiBackend
import taboolib.module.incision.loader.LoadTimings
import taboolib.module.incision.loader.PipelineBackend

/**
 * Incision 端到端验收项 — 只回答两个问题：
 *
 * 1. `Incision:backendStatus`：模块是否真的加载，以及 retransform 后端的真实状态；
 * 2. `Incision:loadTiming`：模块加载耗时是否落在宽松上限之内。
 *
 * 后端可用性取决于服务端启动参数（`-javaagent` / `-XX:+EnableDynamicAgentLoading` / `jdk.attach.allowAttachSelf`），
 * 端到端跑的是普通 Paper，后端不可用属于**正常结果**，因此这里只做状态报告，不断言「必须可用」。
 * 只有「模块根本没加载 / 生命周期没跑」这类真异常才判为失败。
 *
 * 本项在主线程执行：只读取已完成的耗时记录与已解析的后端状态，不等待、不做重活。
 *
 * @see LoadTimings
 */
@Inject
object TestIncision : Test() {

    /**
     * 宽松上限：真机冷启动下 CONST + ENABLE 通常在数百毫秒级完成，
     * 只有整段加载被异常阻塞（达到 10 秒级）才算异常，避免 CI 机器抖动造成 flaky。
     */
    private const val LOAD_BUDGET_MILLIS = 10_000L

    override fun check(): List<Result> {
        return listOf(reportBackendStatus(), reportLoadSpeed())
    }

    /** 第一项：模块能否加载 + 织入后端真实状态（状态报告）。 */
    private fun reportBackendStatus(): Result {
        val constRecorded = LoadTimings.isRecorded(LoadTimings.CONST)
        val enableRecorded = LoadTimings.isRecorded(LoadTimings.ENABLE)
        if (!constRecorded || !enableRecorded) {
            // 本测试类能被发现，说明 incision 已进入自举清单；此时缺记录只可能是 Bootstrap 的 @Awake 生命周期没有执行
            return Failure.of(
                "Incision:backendStatus",
                "Incision 模块未完成初始化：CONST已记录=$constRecorded ENABLE已记录=$enableRecorded，" +
                    "IncisionBootstrap 的 @Awake 生命周期可能未执行",
            )
        }
        // 后端状态在此刻均已解析完成（CONST 的 Bridge 注入与 ENABLE 的收尾探测都调用过），下面只是读缓存结果。
        val instrumentation = runCatching { InstrumentationBackend.available() }.getOrDefault(false)
        val jvmti = runCatching { JvmtiBackend.available() }.getOrDefault(false)
        val pipeline = runCatching { PipelineBackend.available() }.getOrDefault(false)
        val conclusion = when {
            instrumentation -> "retransform 后端可用（Instrumentation），已加载类可直接织入"
            jvmti -> "retransform 后端可用（JVMTI native），已加载类可直接织入"
            pipeline -> "仅 Pipeline 路径可用（NMSProxy 生成期织入），已加载类无法 retransform"
            else -> "无可用织入后端"
        }
        // API_VERSION 是 const（编译期内联，不会触发 IncisionBootstrap 初始化）；且此处 ENABLE 已确认执行。
        return Success.of(
            "Incision:backendStatus 模块已加载 api=${IncisionBootstrap.API_VERSION} " +
                "instrumentation=$instrumentation jvmti=$jvmti pipeline=$pipeline → $conclusion",
        )
    }

    /** 第二项：加载耗时（读取 [LoadTimings] 观测点）并断言宽松上限。 */
    private fun reportLoadSpeed(): Result {
        val constMillis = LoadTimings.millis(LoadTimings.CONST)
        val enableMillis = LoadTimings.millis(LoadTimings.ENABLE)
        if (constMillis < 0L || enableMillis < 0L) {
            return Failure.of(
                "Incision:loadTiming",
                "未记录到加载耗时：CONST=${constMillis}ms ENABLE=${enableMillis}ms（-1 表示对应生命周期未执行）",
            )
        }
        val total = constMillis + enableMillis
        val detail = "CONST=${constMillis}ms ENABLE=${enableMillis}ms 合计=${total}ms 上限=${LOAD_BUDGET_MILLIS}ms" +
            "；子步骤: ${subStepDetail()}"
        return if (total <= LOAD_BUDGET_MILLIS) {
            Success.of("Incision:loadTiming $detail")
        } else {
            Failure.of(
                "Incision:loadTiming 加载耗时超过宽松上限",
                "合计 ${total}ms 超过上限 ${LOAD_BUDGET_MILLIS}ms：$detail",
            )
        }
    }

    /** 子步骤耗时明细（仅在已记录时输出），用于真机定位是哪一步拖慢加载。 */
    private fun subStepDetail(): String {
        val phases = listOf(
            LoadTimings.CONST_ASM_PROBE,
            LoadTimings.CONST_NMS_RESOLVER,
            LoadTimings.CONST_REFLEX_ADAPTER,
            LoadTimings.CONST_BRIDGE_INJECT,
            LoadTimings.CONST_PIPELINE,
            LoadTimings.ENABLE_GATE,
            LoadTimings.ENABLE_CHECKUP,
            LoadTimings.ENABLE_CONFLICT,
            LoadTimings.ENABLE_BACKEND,
        )
        return phases.mapNotNull { phase ->
            val millis = LoadTimings.millis(phase)
            if (millis < 0L) null else "$phase=${millis}ms"
        }.joinToString(" ")
    }
}
