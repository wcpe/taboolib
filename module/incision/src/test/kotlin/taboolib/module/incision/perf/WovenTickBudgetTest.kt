package taboolib.module.incision.perf

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.reflex.IncisionReflex
import taboolib.module.incision.remap.NoopResolver
import taboolib.module.incision.runtime.AdviceEntry
import taboolib.module.incision.runtime.AdviceKind
import taboolib.module.incision.runtime.CanonicalBridge
import taboolib.module.incision.runtime.TheatreDispatcher
import taboolib.module.incision.weaver.Scalpel
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntSupplier
import java.util.function.IntUnaryOperator

/**
 * 织入开销的 **tick / 高频场景** 验收（报告性质）—— 默认不随常规 test 执行。
 *
 * 打 `@Tag("perf")`，与 `WovenInvocationOverheadTest` 共用同一套默认排除策略
 * （`build.gradle.kts` 的 `tasks.test` 里 `excludeTags("perf")`）；需要人工看数值时显式点名：
 * ```
 * ./gradlew :module:incision:test --tests "*WovenTickBudgetTest*"
 * ```
 *
 * ## 它补充回答的问题
 *
 * `WovenInvocationOverheadTest` 回答的是"一次织入调用比不织入贵多少纳秒"；本类回答的是
 * **"这点纳秒放进真实服务端的高频调用里，会不会吃掉 tick 预算"**。模型很朴素：
 * Minecraft 服务端固定 20 TPS → 每 tick 50 ms；把测得的一次调用开销乘以"每 tick 调用次数"，
 * 就得到每 tick 的总开销与预算占比。数值本身就是结论，因此全部打印出来。
 *
 * ## 五个维度（各自独立用例，失败时能一眼看出是哪个形态退化）
 *
 * | 用例 | 维度 | 守的边界 |
 * |---|---|---|
 * | [callRateMapsToTickBudget] | 调用频次 100 / 1,000 / 10,000 次每 tick | 单次 < 2 μs；10,000 次/tick < 50 ms 的 20% |
 * | [tickBudgetScalesWithParameterArity] | 参数规模 0 / 1 / 4 / 8（基本类型与引用类型混合） | 每种形态各自满足上表边界 |
 * | [tickBudgetScalesWithAdviceCount] | advice 数 0 / 1 / 3 / 20 条 | 每种形态各自满足上表边界；且 20 条 > 0 条 |
 * | [primitiveArgsAgainstReferenceArgs] | 4 基本类型实参 vs 4 引用类型实参 | 每种形态各自满足上表边界；装箱差价 < [MAX_BOXING_DELTA_NANOS] |
 * | [longRunStability] | 12 轮长运行 + 链快照反复替换 | 首尾各 3 轮中位数比值落在 [0.5, 2.0]（量级判据） |
 *
 * ## 阈值策略
 *
 * 只使用**宽松的绝对阈值**与**量级判据**，与既有 perf 用例（`②-① < 500ns`、`③-② > 0`）同风格：
 * 断言"没有数量级劣化"，**不**断言精确倍数 `③/①`、`④/③` 之类 —— JIT 内联、GC、CPU 调度会让
 * 这些比值在机器之间剧烈漂移。所有断言消息都带上实测数值，便于事后区分"环境抖动"与"真退化"。
 *
 * ## 夹具复用
 *
 * 目标类构造、织入调用、ClassLoader 隔离、计时循环（固定迭代 + 预热 + 多轮取中位数、不用 sleep、
 * 累加值经 volatile 发布以防死代码删除）全部沿用 [WovenInvocationOverheadTest] 的写法，
 * 唯一差别是计时循环用 `inline` 抽成一份，让 6 种目标签名（`()I` / `(I)I` / 4 参混合 / 8 参混合 /
 * `(IIII)I` / 4 引用类型）共用形状完全一致的计时字节码，避免把夹具差异算进形态差异。
 * 预热的迭代量比既有验收大一个档（见 [WARMUP_ROUNDS]），因为实测这条链路要约 1M 次调用才收敛。
 */
@Tag("perf")
@DisplayName("织入的 tick 预算验收（计时型）")
class WovenTickBudgetTest {

    /** 黑盒出口：每轮把累加值发布到 volatile 字段。没有它，JIT 可以整轮删除计时循环。 */
    @Volatile
    private var sink: Int = 0

    /** advice 触发计数：既证明链真的被执行，也保证 handler 不会被优化成空操作。 */
    private val handlerCalls = AtomicInteger()

    /** 已注册的 (目标, advice id)；用例结束后统一注销，避免污染其它用例。 */
    private val registered = mutableListOf<Pair<MethodCoordinate, String>>()

    @AfterEach
    fun releaseAdvice() {
        // unregister 对不存在的 id 是幂等的，因此重复清理不会出错
        registered.forEach { (target, id) -> TheatreDispatcher.unregister(target, id) }
        registered.clear()
    }

    // ===== 1. 调用频次 ↔ tick 预算（主用例） =====

    @Test
    @DisplayName("每 tick 100 / 1,000 / 10,000 次调用：织入开销占 50ms tick 预算的比例远低于上限")
    fun callRateMapsToTickBudget() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val owner = "taboolib/module/incision/tickbudget/RateTarget"
        val target = MethodCoordinate(owner, "applyAsInt", "(I)I")
        val targets = defineTargets(owner, IntUnaryOperator::class.java, "applyAsInt", "(I)I", BodyKind.ADD_ONE)
        val unwoven = targets.unwoven as IntUnaryOperator
        val woven = targets.woven as IntUnaryOperator
        registerEmptyLead(target, "tick-rate-lead")

        // 语义基线：织入不得改变返回值（否则后面测的就不是同一个方法了）
        assertEquals(unwoven.applyAsInt(41), woven.applyAsInt(41), "织入后返回值必须与原方法一致")

        // 预热：让三个接口调用点都完成 C2 内联决策
        repeat(WARMUP_ROUNDS) { elapsedNanos(WARMUP_ITERATIONS) { unwoven.applyAsInt(it) } }
        repeat(WARMUP_ROUNDS) { elapsedNanos(WARMUP_ITERATIONS) { woven.applyAsInt(it) } }
        repeat(WARMUP_ROUNDS) {
            // 抑制路径必须整段包裹计时循环：ThreadLocal 检查发生在 dispatch 入口，而不是循环里
            IncisionReflex.withoutIncision { elapsedNanos(WARMUP_ITERATIONS) { woven.applyAsInt(it) } }
        }

        val baseline = median(measureRounds { unwoven.applyAsInt(it) })
        val callsBeforeSuppressed = handlerCalls.get()
        val suppressed = IncisionReflex.withoutIncision { median(measureRounds { woven.applyAsInt(it) }) }
        assertEquals(
            callsBeforeSuppressed, handlerCalls.get(),
            "withoutIncision 作用域内不得执行 advice 链（否则这一组测的不是抑制路径）",
        )
        val chained = median(measureRounds { woven.applyAsInt(it) })
        assertTrue(
            handlerCalls.get() > callsBeforeSuppressed,
            "已织入且未抑制时 advice 链必须被执行，实际链触发次数 ${handlerCalls.get()} - $callsBeforeSuppressed",
        )

        report(baseline, suppressed, chained)
        reportTickBudget("① 未织入基线（对照，不代表真实场景）", baseline)
        reportTickBudget("③ 已织入 + 1 条空 advice 链（真实场景，本用例的判据）", chained)

        assertAll(
            budgetChecks("已织入 + 1 条空 advice 链", chained) + listOf(
                Executable {
                    assertTrue(
                        chained > suppressed,
                        "链执行应产生可观测开销：③=${ns(chained)} ②（纯入口）=${ns(suppressed)}",
                    )
                },
            ),
        )
    }

    // ===== 2. 参数规模 =====

    /**
     * 参数规模 0 / 1 / 4 / 8 —— 实参数组构造 + 装箱量随参数增长，是固定开销之外最可能退化的地方。
     *
     * 各形态方法体刻意保持逐指令一致（[BodyKind.CONST_ZERO]），差异只来自签名。
     * 5 次运行实测（每次运行都会重新打印）：0 参 63.1~65.5 ns、1 参 61.8~63.8 ns、
     * 4 参混合 66.6~73.1 ns、8 参混合 74.2~79.5 ns —— 4 参 / 8 参稳定地比 0 参 / 1 参 贵 10~17 ns，
     * 与"多 8 次实参读取 + 6 次装箱 + 更长的数组填充"量级相符。
     * 但这十几纳秒仍小于同形态的逐轮波动（±20%），所以只作为趋势报告，**不**按倍数断言。
     */
    @Test
    @DisplayName("参数规模 0 / 1 / 4 / 8 个实参（基本类型与引用类型混合）：各形态都没吃掉 tick 预算")
    fun tickBudgetScalesWithParameterArity() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val results = mutableListOf<ShapeResult>()

        // 0 参：实参数组仍要构造（长度 0 的 Object[]），但不发生任何装箱
        run {
            val owner = "taboolib/module/incision/tickbudget/Arity0"
            val target = MethodCoordinate(owner, "getAsInt", "()I")
            val targets = defineTargets(owner, IntSupplier::class.java, "getAsInt", "()I", BodyKind.CONST_ZERO)
            val unwoven = targets.unwoven as IntSupplier
            val woven = targets.woven as IntSupplier
            registerEmptyLead(target, "tick-arity-0")
            assertEquals(unwoven.getAsInt(), woven.getAsInt(), "织入后返回值必须与原方法一致（0 参）")
            warmup { unwoven.getAsInt() }
            warmup { woven.getAsInt() }
            results += ShapeResult(
                "0 参 ()I",
                median(measureRounds { unwoven.getAsInt() }),
                median(measureRounds { woven.getAsInt() }),
            )
        }

        // 1 参：与既有验收同形（1 个基本类型实参），作为"参数规模"这一维度的基准
        run {
            val owner = "taboolib/module/incision/tickbudget/Arity1"
            val target = MethodCoordinate(owner, "applyAsInt", "(I)I")
            val targets = defineTargets(owner, IntUnaryOperator::class.java, "applyAsInt", "(I)I", BodyKind.CONST_ZERO)
            val unwoven = targets.unwoven as IntUnaryOperator
            val woven = targets.woven as IntUnaryOperator
            registerEmptyLead(target, "tick-arity-1")
            assertEquals(unwoven.applyAsInt(41), woven.applyAsInt(41), "织入后返回值必须与原方法一致（1 参）")
            warmup { unwoven.applyAsInt(it) }
            warmup { woven.applyAsInt(it) }
            results += ShapeResult(
                "1 参 (I)I",
                median(measureRounds { unwoven.applyAsInt(it) }),
                median(measureRounds { woven.applyAsInt(it) }),
            )
        }

        // 4 参：1 个 int、1 个 long、1 个 String、1 个 double（沿用 ScalpelHotPathShapeTest 的混合实参口径）
        run {
            val owner = "taboolib/module/incision/tickbudget/Arity4"
            val target = MethodCoordinate(owner, "apply", MIXED_QUAD_DESC)
            val targets = defineTargets(owner, MixedQuadFunction::class.java, "apply", MIXED_QUAD_DESC, BodyKind.CONST_ZERO)
            val unwoven = targets.unwoven as MixedQuadFunction
            val woven = targets.woven as MixedQuadFunction
            registerEmptyLead(target, "tick-arity-4")
            assertEquals(unwoven.apply(41, 1L, "s", 1.0), woven.apply(41, 1L, "s", 1.0), "织入后返回值必须与原方法一致（4 参）")
            warmup { unwoven.apply(it, 1L, "s", 1.0) }
            warmup { woven.apply(it, 1L, "s", 1.0) }
            results += ShapeResult(
                "4 参 混合",
                median(measureRounds { unwoven.apply(it, 1L, "s", 1.0) }),
                median(measureRounds { woven.apply(it, 1L, "s", 1.0) }),
            )
        }

        // 8 参：6 个基本类型 + 2 个引用类型，覆盖"实参数组构造 + 装箱量随参数增长"的最坏一侧
        run {
            val owner = "taboolib/module/incision/tickbudget/Arity8"
            val target = MethodCoordinate(owner, "apply", MIXED_OCT_DESC)
            val targets = defineTargets(owner, MixedOctFunction::class.java, "apply", MIXED_OCT_DESC, BodyKind.CONST_ZERO)
            val unwoven = targets.unwoven as MixedOctFunction
            val woven = targets.woven as MixedOctFunction
            registerEmptyLead(target, "tick-arity-8")
            assertEquals(
                unwoven.apply(41, 1L, "s", 1.0, 2, 3L, "t", payload),
                woven.apply(41, 1L, "s", 1.0, 2, 3L, "t", payload),
                "织入后返回值必须与原方法一致（8 参）",
            )
            warmup { unwoven.apply(it, 1L, "s", 1.0, 2, 3L, "t", payload) }
            warmup { woven.apply(it, 1L, "s", 1.0, 2, 3L, "t", payload) }
            results += ShapeResult(
                "8 参 混合",
                median(measureRounds { unwoven.apply(it, 1L, "s", 1.0, 2, 3L, "t", payload) }),
                median(measureRounds { woven.apply(it, 1L, "s", 1.0, 2, 3L, "t", payload) }),
            )
        }

        reportShapes("参数规模 ↔ 单次调用开销（已织入 = 入口 + 1 条空 advice 链）", results, results.first().woven)
        assertAll(results.flatMap { budgetChecks(it.label, it.woven) })
    }

    // ===== 3. advice 数量 =====

    @Test
    @DisplayName("advice 数 0 / 1 / 3 / 20 条：链执行的边际成本可见，且 20 条形态仍远低于 tick 预算上限")
    fun tickBudgetScalesWithAdviceCount() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val owner = "taboolib/module/incision/tickbudget/AdviceCount"
        val target = MethodCoordinate(owner, "applyAsInt", "(I)I")
        val targets = defineTargets(owner, IntUnaryOperator::class.java, "applyAsInt", "(I)I", BodyKind.ADD_ONE)
        val unwoven = targets.unwoven as IntUnaryOperator
        val woven = targets.woven as IntUnaryOperator

        warmup { unwoven.applyAsInt(it) }
        warmup { woven.applyAsInt(it) }
        val baseline = median(measureRounds { unwoven.applyAsInt(it) })

        // 同一份已织入字节码、同一个调用点，只改变 dispatcher 里登记的 advice 条数 ——
        // 这样各组之间的差异只剩"链长度"，不掺入字节码或调用点差异。
        val results = mutableListOf<AdviceCountResult>()
        // 每一档注册完都重新预热：链长变化会让 dispatch 里的分支剖面变化，必须重新让 C2 收敛
        results += measureAdviceCount("0 条（纯入口）", woven)
        registerEmptyLead(target, "tick-count-1")
        results += measureAdviceCount("1 条（空 handler）", woven)
        registerEmptyLead(target, "tick-count-2")
        registerEmptyLead(target, "tick-count-3")
        results += measureAdviceCount("3 条", woven)
        for (n in 4..20) registerEmptyLead(target, "tick-count-$n")
        results += measureAdviceCount("20 条", woven)

        println("===== advice 条数 ↔ 单次调用开销（未织入基线 ${ns(baseline)}）=====")
        results.forEachIndexed { index, r ->
            val marginal = if (index == 0) "" else String.format(Locale.ROOT, "  相对上一档 %+8.2f ns", r.perCall - results[index - 1].perCall)
            println(
                String.format(
                    Locale.ROOT,
                    "  %-18s %8.2f ns | 每 tick %d 次占 %7.4f%%%s",
                    r.label, r.perCall, MAX_ASSERT_CALL_RATE,
                    tickSharePercent(r.perCall, MAX_ASSERT_CALL_RATE), marginal,
                ),
            )
        }
        println("=========================================================")

        val emptyChain = results.first().perCall
        val heaviest = results.last().perCall
        assertAll(
            results.flatMap { budgetChecks("advice ${it.label}", it.perCall) } + listOf(
                Executable {
                    assertTrue(
                        heaviest > emptyChain,
                        "20 条 advice 的执行成本必须大于空链（证明链确实被执行而不是被短路）：" +
                            "20 条=${ns(heaviest)} 0 条=${ns(emptyChain)}",
                    )
                },
            ),
        )
    }

    // ===== 4. 引用类型 vs 基本类型参数 =====

    /**
     * 基本类型实参 vs 引用类型实参 —— **实测结论与"基本类型应更贵"的预期不符**，这里如实记录：
     *
     * 入口路径为基本类型实参发射的是 `NEW java/lang/Integer` + `<init>`（见 `ScalpelHotPathShapeTest`
     * 的 `boxingStrategyDiffersBetweenEntryAndSitePaths`），即每次都新建包装对象、拿不到 Integer 缓存，
     * 直觉上应当更贵。但 5 次运行的实测配对差价是 +0.62 / +0.13 / +10.68 / +7.19 / **−0.17** ns ——
     * 幅度落在十纳秒以内，**符号还会翻转**，而同一形态的逐轮波动就有 ±20%（±15 ns 量级），
     * 也就是这个差价**低于本夹具的分辨力**。
     *
     * 可信的解释：这条路径的固定成本（入口注入 + `IncisionBridge` 路由 + MethodHandle 调用 + 链执行）
     * 约 60~110 ns，其中 MethodHandle 调用点不可内联，4 次 TLAB 分配（每次约 1~3 ns）被完全淹没。
     *
     * 因此本用例守的是"装箱差价**不得**成为可观开销"这条**单侧上限**，而不是断言差价严格为正 ——
     * 十纳秒量级以内、符号会翻转的中位数，按它做断言必然 flaky。
     */
    @Test
    @DisplayName("4 个基本类型实参 vs 4 个引用类型实参：装箱差价落在噪声底内，两者都远低于 tick 预算上限")
    fun primitiveArgsAgainstReferenceArgs() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val primitiveOwner = "taboolib/module/incision/tickbudget/ArgsPrimitive4"
        val referenceOwner = "taboolib/module/incision/tickbudget/ArgsReference4"

        val primitiveTarget = MethodCoordinate(primitiveOwner, "apply", PRIMITIVE_QUAD_DESC)
        val referenceTarget = MethodCoordinate(referenceOwner, "apply", REFERENCE_QUAD_DESC)
        val primitiveTargets = defineTargets(primitiveOwner, IntQuadFunction::class.java, "apply", PRIMITIVE_QUAD_DESC, BodyKind.CONST_ZERO)
        val referenceTargets = defineTargets(referenceOwner, RefQuadFunction::class.java, "apply", REFERENCE_QUAD_DESC, BodyKind.CONST_ZERO)
        val primitiveUnwoven = primitiveTargets.unwoven as IntQuadFunction
        val primitiveWoven = primitiveTargets.woven as IntQuadFunction
        val referenceUnwoven = referenceTargets.unwoven as RefQuadFunction
        val referenceWoven = referenceTargets.woven as RefQuadFunction
        registerEmptyLead(primitiveTarget, "tick-args-primitive")
        registerEmptyLead(referenceTarget, "tick-args-reference")

        assertEquals(primitiveUnwoven.apply(1, 2, 3, 4), primitiveWoven.apply(1, 2, 3, 4), "织入后返回值必须与原方法一致（基本类型）")
        assertEquals(referenceUnwoven.apply(payload, payload, payload, payload), referenceWoven.apply(payload, payload, payload, payload), "织入后返回值必须与原方法一致（引用类型）")

        warmup { primitiveUnwoven.apply(it, it, it, it) }
        warmup { primitiveWoven.apply(it, it, it, it) }
        warmup { referenceUnwoven.apply(payload, payload, payload, payload) }
        warmup { referenceWoven.apply(payload, payload, payload, payload) }

        // 交替采样：两组各自连续采样会把环境漂移算进差值里；逐轮配对后取"配对差值的中位数"更稳
        val primitiveRounds = DoubleArray(MEASURE_ROUNDS)
        val referenceRounds = DoubleArray(MEASURE_ROUNDS)
        val unwovenPrimitiveRounds = DoubleArray(MEASURE_ROUNDS)
        val unwovenReferenceRounds = DoubleArray(MEASURE_ROUNDS)
        for (round in 0 until MEASURE_ROUNDS) {
            primitiveRounds[round] = roundNanos { primitiveWoven.apply(it, it, it, it) }
            referenceRounds[round] = roundNanos { referenceWoven.apply(payload, payload, payload, payload) }
            unwovenPrimitiveRounds[round] = roundNanos { primitiveUnwoven.apply(it, it, it, it) }
            unwovenReferenceRounds[round] = roundNanos { referenceUnwoven.apply(payload, payload, payload, payload) }
        }

        val primitive = median(primitiveRounds)
        val reference = median(referenceRounds)
        // 配对差值：抵消"测基本类型时机器刚好更忙"这类系统性漂移
        val pairedDelta = median(DoubleArray(MEASURE_ROUNDS) { primitiveRounds[it] - referenceRounds[it] })
        val controlDelta = median(DoubleArray(MEASURE_ROUNDS) { unwovenPrimitiveRounds[it] - unwovenReferenceRounds[it] })
        val primitiveUnwovenCost = median(unwovenPrimitiveRounds)
        val referenceUnwovenCost = median(unwovenReferenceRounds)

        println("===== 基本类型实参 vs 引用类型实参（4 参，已织入 = 入口 + 1 条空 advice 链）=====")
        println(String.format(Locale.ROOT, "  4 基本类型 (IIII)I                              : 未织入 %8.2f ns | 已织入 %8.2f ns", primitiveUnwovenCost, primitive))
        println(String.format(Locale.ROOT, "  4 引用类型 (Ljava/lang/Object;×4)I              : 未织入 %8.2f ns | 已织入 %8.2f ns", referenceUnwovenCost, reference))
        println(String.format(Locale.ROOT, "  装箱差价（基本 − 引用，配对差值中位数）         : 已织入 %+8.2f ns | 未织入（对照） %+8.2f ns", pairedDelta, controlDelta))
        println(String.format(Locale.ROOT, "  每 tick %d 次占 50ms 预算                        : 基本 %7.4f%% | 引用 %7.4f%%", MAX_ASSERT_CALL_RATE, tickSharePercent(primitive, MAX_ASSERT_CALL_RATE), tickSharePercent(reference, MAX_ASSERT_CALL_RATE)))
        println("  注：该差价在多次运行之间落在 −0.2 ~ +10.7 ns（符号会翻转），远小于同形态逐轮波动（±20%，约 ±15 ns），")
        println("      因此\"基本类型应更贵\"这一预期在本路径上未被观测到 —— 见用例注释的现状说明。")
        println("==========================================================================")

        assertAll(
            budgetChecks("4 基本类型实参", primitive) +
                budgetChecks("4 引用类型实参", reference) +
                listOf(
                    Executable {
                        assertTrue(
                            pairedDelta < MAX_BOXING_DELTA_NANOS,
                            "基本类型实参的装箱差价必须落在噪声底内（不得成为可观开销）：" +
                                "基本=${ns(primitive)} 引用=${ns(reference)} 配对差价=${ns(pairedDelta)}，" +
                                "上限=${ns(MAX_BOXING_DELTA_NANOS)}（未织入对照差价=${ns(controlDelta)}）",
                        )
                    },
                ),
        )
    }

    // ===== 5. 长运行稳定性 =====

    @Test
    @DisplayName("长运行稳定性：连续 12 轮中位数不漂移，advice 链快照反复替换后也不退化")
    fun longRunStability() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val owner = "taboolib/module/incision/tickbudget/StabilityTarget"
        val target = MethodCoordinate(owner, "applyAsInt", "(I)I")
        val targets = defineTargets(owner, IntUnaryOperator::class.java, "applyAsInt", "(I)I", BodyKind.ADD_ONE)
        val woven = targets.woven as IntUnaryOperator
        registerEmptyLead(target, "tick-stability-lead")
        assertEquals(
            (targets.unwoven as IntUnaryOperator).applyAsInt(41), woven.applyAsInt(41),
            "织入后返回值必须与原方法一致",
        )

        warmup { woven.applyAsInt(it) }

        // 第一段：同一形态连续 12 轮，观察中位数是否随时间漂移
        val first = DoubleArray(STABILITY_ROUNDS) { roundNanos { woven.applyAsInt(it) } }

        // 反复替换链快照：注册 / 注销额外 advice 会重建并发布新的不可变快照，
        // 用于发现"缓存/链快照"类优化在长运行下退化（读侧应当始终从 volatile 拿到最新快照）
        val churnCalls = AtomicInteger()
        repeat(SNAPSHOT_CHURN) { n ->
            val churnId = "tick-stability-churn-$n"
            registerEmptyLead(target, churnId, churnCalls)
            TheatreDispatcher.unregister(target, churnId)
        }

        // 第二段：快照被替换 SNAPSHOT_CHURN 次之后，同一形态再测 12 轮
        val second = DoubleArray(STABILITY_ROUNDS) { roundNanos { woven.applyAsInt(it) } }

        println("===== 长运行稳定性（同一形态连续采样，单次调用 ns）=====")
        first.forEachIndexed { index, value ->
            println(String.format(Locale.ROOT, "  第 %2d 轮 %9.2f ns%s", index + 1, value, if (index == 0) "（起）" else ""))
        }
        println(String.format(Locale.ROOT, "  —— 链快照替换 $SNAPSHOT_CHURN 次（注册/注销 advice id 轮转）——"))
        second.forEachIndexed { index, value -> println(String.format(Locale.ROOT, "  第 %2d 轮 %9.2f ns", index + 1, value)) }

        val early = median(first.copyOfRange(0, STABILITY_SAMPLE))
        val late = median(first.copyOfRange(first.size - STABILITY_SAMPLE, first.size))
        val afterChurn = median(second)
        val earlyVsLate = late / early
        val earlyVsAfter = afterChurn / early

        println(String.format(Locale.ROOT, "  最初 %d 轮中位数 %8.2f ns | 最后 %d 轮 %8.2f ns（比值 %.3f）", STABILITY_SAMPLE, early, STABILITY_SAMPLE, late, earlyVsLate))
        println(String.format(Locale.ROOT, "  快照替换后 %d 轮中位数 %8.2f ns（相对最初比值 %.3f）", STABILITY_ROUNDS, afterChurn, earlyVsAfter))
        println("=========================================================")

        assertAll(
            Executable {
                assertTrue(
                    earlyVsLate in DRIFT_RATIO_MIN..DRIFT_RATIO_MAX,
                    "长运行下每轮中位数不得漂移超过一个量级：最初 $STABILITY_SAMPLE 轮=${ns(early)}，" +
                        "最后 $STABILITY_SAMPLE 轮=${ns(late)}，比值=${String.format(Locale.ROOT, "%.3f", earlyVsLate)}，" +
                        "允许区间 [$DRIFT_RATIO_MIN, $DRIFT_RATIO_MAX]",
                )
            },
            Executable {
                assertTrue(
                    earlyVsAfter in DRIFT_RATIO_MIN..DRIFT_RATIO_MAX,
                    "链快照被替换 $SNAPSHOT_CHURN 次后不得退化：替换前=${ns(early)}，替换后=${ns(afterChurn)}，" +
                        "比值=${String.format(Locale.ROOT, "%.3f", earlyVsAfter)}，允许区间 [$DRIFT_RATIO_MIN, $DRIFT_RATIO_MAX]",
                )
            },
        )
    }

    // ===== 共同判据 =====

    /**
     * 每个形态都要守的两条**绝对**边界（失败信息里带实测数值）：
     *
     * 1. 单次调用 < [MAX_SINGLE_CALL_NANOS]；
     * 2. 每 tick [MAX_ASSERT_CALL_RATE] 次时，总开销占 50 ms 预算 < [MAX_TICK_SHARE_PERCENT]%。
     *
     * 两者都是宽上限：只断言"不是数量级劣化"，不引用任何归档报告里的具体数字当阈值
     * （那些数字随机器与 JVM 版本漂移）。
     */
    private fun budgetChecks(label: String, perCallNanos: Double): List<Executable> = listOf(
        Executable {
            assertTrue(
                perCallNanos < MAX_SINGLE_CALL_NANOS,
                "$label：单次调用开销必须低于 ${us(MAX_SINGLE_CALL_NANOS)}，实测 ${ns(perCallNanos)}",
            )
        },
        Executable {
            val share = tickSharePercent(perCallNanos, MAX_ASSERT_CALL_RATE)
            assertTrue(
                share < MAX_TICK_SHARE_PERCENT,
                "$label：每 tick $MAX_ASSERT_CALL_RATE 次时占 50ms 预算必须低于 ${pct(MAX_TICK_SHARE_PERCENT)}，" +
                    "实测 ${pct(share)}（单次 ${ns(perCallNanos)}，每 tick 合计 ${us(tickNanos(perCallNanos, MAX_ASSERT_CALL_RATE))}）",
            )
        },
    )

    // ===== tick 预算换算 =====

    /** 每 tick 调用 [calls] 次时的总耗时（纳秒）。 */
    private fun tickNanos(perCallNanos: Double, calls: Int): Double = perCallNanos * calls

    /** 每 tick 调用 [calls] 次时占 50 ms 预算的百分比。 */
    private fun tickSharePercent(perCallNanos: Double, calls: Int): Double =
        tickNanos(perCallNanos, calls) / TICK_BUDGET_NANOS * 100.0

    /** 打印"调用频次 ↔ tick 预算"换算表 —— 数值本身就是给人看的结论。 */
    private fun reportTickBudget(label: String, perCallNanos: Double) {
        println("===== $label =====")
        println(String.format(Locale.ROOT, "  单次调用 %8.2f ns（每 tick 50 ms = %.0f ns）", perCallNanos, TICK_BUDGET_NANOS))
        for (calls in CALL_RATES) {
            println(
                String.format(
                    Locale.ROOT,
                    "  每 tick %6d 次 → 合计 %9.3f μs，占 50ms 预算 %8.4f %%",
                    calls,
                    tickNanos(perCallNanos, calls) / 1000.0,
                    tickSharePercent(perCallNanos, calls),
                ),
            )
        }
        println(
            String.format(
                Locale.ROOT,
                "  反推：50ms 预算下最多可承受 %.0f 次/tick",
                TICK_BUDGET_NANOS / perCallNanos,
            ),
        )
        println("==================================================")
    }

    /** 打印三组对照（与 `WovenInvocationOverheadTest` 同口径，便于跨用例对照数值）。 */
    private fun report(baseline: Double, suppressed: Double, chained: Double) {
        listOf(
            "===== 织入调用开销（单次调用 ns 中位数：$ITERATIONS 次/轮 × $MEASURE_ROUNDS 轮）=====",
            "① 未织入基线                      : ${ns(baseline)}",
            "② 已织入 + withoutIncision{}      : ${ns(suppressed)}   (②-① = ${ns(suppressed - baseline)})",
            "③ 已织入 + 空 Lead 链              : ${ns(chained)}   (③-② = ${ns(chained - suppressed)})",
            "===============================================================",
        ).forEach { println(it) }
    }

    /** 打印形态对照表（未织入 / 已织入 / 织入净开销 / 每 tick 占比 / 相对基准）。 */
    private fun reportShapes(title: String, results: List<ShapeResult>, base: Double) {
        println("===== $title =====")
        results.forEach { r ->
            println(
                String.format(
                    Locale.ROOT,
                    "  %-12s 未织入 %8.2f ns | 已织入 %8.2f ns | 净开销 %8.2f ns | 每 tick %d 次占 %7.4f%% | 相对基准 %+8.2f ns",
                    r.label, r.unwoven, r.woven, r.delta, MAX_ASSERT_CALL_RATE,
                    tickSharePercent(r.woven, MAX_ASSERT_CALL_RATE), r.woven - base,
                ),
            )
        }
        println("  注：同一形态的逐轮波动可达 ±20%（见长运行稳定性用例），档位差异小于该波动时应视为等价")
        println("============================================================")
    }

    // ===== 夹具：注册 advice =====

    /**
     * 注册一条"空 Lead"advice：handler 只做一次原子自增。
     *
     * 计数不是可选的 —— 若 handler 完全是空操作，JIT 可以把整条链折叠掉，
     * "链执行成本"就无从测量；原子自增是任何优化都无法消除的真实副作用。
     */
    private fun registerEmptyLead(target: MethodCoordinate, id: String, counter: AtomicInteger = handlerCalls) {
        TheatreDispatcher.register(
            AdviceEntry(
                id = id,
                kind = AdviceKind.LEAD,
                target = target,
                priority = 0,
                handler = {
                    counter.incrementAndGet()
                    null
                },
            ),
        )
        registered += target to id
    }

    // ===== 夹具：目标类与字节码 =====

    /**
     * 目标方法体形态。
     *
     * [CONST_ZERO] 用于"参数规模 / 参数类型"对照：各形态方法体刻意保持逐指令一致（只返回 0），
     * 使差异只来自签名（也就是实参数组的构造与装箱），而不是方法体本身。
     * 返回值语义正确性由 [BodyKind.ADD_ONE] 的用例（以及既有 `WovenInvocationOverheadTest`）单独验证。
     */
    private enum class BodyKind { ADD_ONE, CONST_ZERO }

    /** 一对同名目标：未织入 / 已织入，各自定义在独立 [ByteClassLoader] 里。 */
    private class Targets(val unwoven: Any, val woven: Any)

    /**
     * 生成目标类字节码，并分别定义"未织入"与"已织入"两个独立 ClassLoader 实例。
     *
     * child-first 只对本用例生成的类生效，其余仍走父加载器，
     * 保证 `IncisionBridge` 与目标接口在测试与目标类之间是同一个类型。
     */
    private fun defineTargets(
        owner: String,
        iface: Class<*>,
        methodName: String,
        desc: String,
        body: BodyKind,
    ): Targets {
        val original = buildTargetBytes(owner, iface.name.replace('.', '/'), methodName, desc, body)
        val woven = weave(owner, original, methodName, desc)
        return Targets(defineInFreshLoader(owner, original), defineInFreshLoader(owner, woven))
    }

    private fun buildTargetBytes(
        owner: String,
        ifaceInternal: String,
        methodName: String,
        desc: String,
        body: BodyKind,
    ): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", arrayOf(ifaceInternal))
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, desc, null, null).apply {
            visitCode()
            when (body) {
                BodyKind.ADD_ONE -> {
                    visitVarInsn(Opcodes.ILOAD, 1)
                    visitInsn(Opcodes.ICONST_1)
                    visitInsn(Opcodes.IADD)
                }
                BodyKind.CONST_ZERO -> visitInsn(Opcodes.ICONST_0)
            }
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 纯字节进/字节出织入，不加载 JVMTI（`useJvmtiBaseline = false`）。 */
    private fun weave(owner: String, originalBytes: ByteArray, methodName: String, desc: String): ByteArray = Scalpel(
        resolver = NoopResolver,
        targetsByOwner = mapOf(
            owner to listOf(
                Scalpel.AdviceTargetSpec(MethodCoordinate(owner, methodName, desc), setOf(AdviceKind.LEAD)),
            ),
        ),
    ).weave(originalBytes)

    private fun defineInFreshLoader(owner: String, bytes: ByteArray): Any {
        val binaryName = owner.replace('/', '.')
        val cls = ByteClassLoader(binaryName, bytes).loadClass(binaryName)
        return cls.getDeclaredConstructor().newInstance()
    }

    private class ByteClassLoader(
        private val generatedName: String,
        private val generatedBytes: ByteArray,
    ) : ClassLoader(WovenTickBudgetTest::class.java.classLoader) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != generatedName) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: defineClass(name, generatedBytes, 0, generatedBytes.size)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }

    // ===== 计时 =====

    /**
     * 固定迭代数的一轮计时（纳秒）—— 循环体与 [WovenInvocationOverheadTest] 逐行一致。
     *
     * 迭代变量既作为实参传入（阻止常量折叠），累加值又通过 [sink] 发布（阻止死代码删除）。
     * 用 `inline` 抽成一份而不是给每种接口各写一遍，是为了让 5 种目标签名共用
     * **字节码形状完全相同**的计时循环，避免把夹具差异算进形态差异。
     */
    private inline fun elapsedNanos(iterations: Int, call: (Int) -> Int): Long {
        var acc = 0
        val start = System.nanoTime()
        for (i in 0 until iterations) {
            acc = acc xor call(i)
            if (i and PUBLISH_MASK == 0) sink = acc
        }
        sink = acc
        return System.nanoTime() - start
    }

    /** 单轮计时 → 单次调用纳秒。 */
    private inline fun roundNanos(call: (Int) -> Int): Double = elapsedNanos(ITERATIONS, call) / ITERATIONS.toDouble()

    /** 多轮采样，每轮取"单次调用纳秒"，由 [median] 收敛掉偶发抖动。 */
    private inline fun measureRounds(call: (Int) -> Int): DoubleArray {
        val rounds = DoubleArray(MEASURE_ROUNDS)
        for (round in 0 until MEASURE_ROUNDS) rounds[round] = roundNanos(call)
        return rounds
    }

    /**
     * 预热：让接口调用点完成 C2 内联决策。固定轮数 + 固定迭代数，不用 sleep。
     *
     * 迭代量刻意比 `WovenInvocationOverheadTest` 大一个档（见 [WARMUP_ROUNDS]/[WARMUP_ITERATIONS] 注释）：
     * 这条链路要跨 `目标方法 → IncisionBridge.dispatch → MethodHandle → TheatreDispatcher → 链` 五层，
     * 实测需要约 1M 次调用才收敛，预热不足会把 JIT 收敛过程误判成"形态差异"或"长运行漂移"。
     */
    private inline fun warmup(call: (Int) -> Int) {
        repeat(WARMUP_ROUNDS) { elapsedNanos(WARMUP_ITERATIONS, call) }
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }

    /** 同一份已织入字节码在**当前**链长下的一轮测量：先预热再采样，避免把 JIT 收敛过程算进链长差异。 */
    private fun measureAdviceCount(label: String, woven: IntUnaryOperator): AdviceCountResult {
        warmup { woven.applyAsInt(it) }
        return AdviceCountResult(label, median(measureRounds { woven.applyAsInt(it) }))
    }

    // ===== 数值格式化 =====

    private fun ns(value: Double): String = String.format(Locale.ROOT, "%.2f ns", value)

    private fun us(value: Double): String = String.format(Locale.ROOT, "%.3f μs", value / 1000.0)

    private fun pct(value: Double): String = String.format(Locale.ROOT, "%.4f%%", value)

    /** 一个"参数规模 / 参数类型"形态的实测结果（单次调用纳秒中位数）。 */
    private class ShapeResult(val label: String, val unwoven: Double, val woven: Double) {
        val delta: Double get() = woven - unwoven
    }

    /** 一个 advice 条数档位的实测结果（单次调用纳秒中位数）。 */
    private class AdviceCountResult(val label: String, val perCall: Double)

    /** 引用类型实参的形状需要喂一个真实对象，避免传 null 让 JIT 走出与真实场景不同的分支。 */
    private val payload: Any = "tick-budget-payload"

    private companion object {
        /** 每轮迭代数：单轮 ~15-30ms，足以摊薄 `System.nanoTime` 与调度噪声。 */
        const val ITERATIONS = 200_000

        /** 采样轮数；取中位数而非均值，避开偶发 GC / 抢占导致的离群轮次。 */
        const val MEASURE_ROUNDS = 7

        /**
         * 预热轮数 / 每轮迭代数：合计 6 × 200,000 = 1,200,000 次调用。
         *
         * 这是实测出来的下限 —— 4 × 60,000 = 240,000 次预热时，同一形态的前 3 个采样轮仍是
         * 约 94 ns，直到第 4 轮才落到约 67 ns 的稳态（见 [longRunStability] 打印的逐轮数值）。
         * 预热不足会把 JIT 收敛误读成"参数规模差异"或"长运行漂移"。
         */
        const val WARMUP_ROUNDS = 6

        const val WARMUP_ITERATIONS = 200_000

        /** 黑盒发布间隔掩码（2^n - 1）。 */
        const val PUBLISH_MASK = 0x3FF

        /** Minecraft 服务端 20 TPS → 每 tick 50 ms，换算成纳秒。 */
        const val TICK_BUDGET_NANOS = 50_000_000.0

        /** tick 预算换算表展示的调用频次档位。 */
        val CALL_RATES = intArrayOf(100, 1_000, 10_000)

        /**
         * 单次调用开销的宽上限（2 μs）。
         *
         * 它是"量级判据"而非精度判据：当前实测在几十纳秒量级，2 μs 留了约两个数量级的余量，
         * 只用来拦住"入口被写成逐调用反射查找""参数数组被反复重建"这类数量级劣化。
         */
        const val MAX_SINGLE_CALL_NANOS = 2_000.0

        /** tick 占比判据使用的每 tick 调用次数：比常见 MC 热路径高一个量级的压力值。 */
        const val MAX_ASSERT_CALL_RATE = 10_000

        /** 每 tick [MAX_ASSERT_CALL_RATE] 次时，织入开销允许占 50 ms 预算的比例上限（宽上限）。 */
        const val MAX_TICK_SHARE_PERCENT = 20.0

        /**
         * "4 个基本类型实参 − 4 个引用类型实参"的配对差价上限。
         *
         * 实测差价在 −0.2 ~ +10.7 ns 之间（5 次运行，符号会翻转），即低于本夹具分辨力。
         * 此处给到 50 ns 是为了：
         *  - 不会因为噪声误报（最差观测值 10.7 ns，仍有约 5 倍余量）；
         *  - 一旦入口路径的装箱退化成可观开销（例如换成逐调用反射装箱），会立刻报警。
         *
         * 注意这里**不**断言 `差价 > 0`：十纳秒以内、符号会翻转的中位数，
         * 断言为正必然在不同机器 / 不同轮次之间随机失败，属于典型的 flaky 断言。
         */
        const val MAX_BOXING_DELTA_NANOS = 50.0

        /** 长运行稳定性用例的采样轮数（两段各跑这么多轮）。 */
        const val STABILITY_ROUNDS = 12

        /** 漂移判据取"最初/最后"各多少轮的中位数。 */
        const val STABILITY_SAMPLE = 3

        /** 链快照替换（注册 + 注销）次数。 */
        const val SNAPSHOT_CHURN = 5

        /** 漂移允许区间：只判"是否漂出一个量级"，因此给到 2 倍宽。 */
        const val DRIFT_RATIO_MIN = 0.5
        const val DRIFT_RATIO_MAX = 2.0

        /** 4 参混合描述符：`I` `J` `Ljava/lang/String;` `D`（与 ScalpelHotPathShapeTest 的口径一致）。 */
        const val MIXED_QUAD_DESC = "(IJLjava/lang/String;D)I"

        /** 8 参混合描述符：6 个基本类型 + 2 个引用类型（必须与 [MixedOctFunction] 的签名逐字节一致）。 */
        const val MIXED_OCT_DESC = "(IJLjava/lang/String;DIJLjava/lang/String;Ljava/lang/Object;)I"

        /** 4 个基本类型实参。 */
        const val PRIMITIVE_QUAD_DESC = "(IIII)I"

        /** 4 个引用类型实参 —— 与 [PRIMITIVE_QUAD_DESC] 只差"要不要装箱"。 */
        const val REFERENCE_QUAD_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)I"
    }
}

// ===== 目标接口 =====
//
// 全部声明为顶层接口（而不是嵌套在测试类里），是为了让 ASM 生成的类能用最直白的 internal name 引用它们。
// 方法名统一为 apply / getAsInt：目标类里被织入的方法名必须与接口方法名一致。

/** 4 参混合形态：`(IJLjava/lang/String;D)I`。 */
interface MixedQuadFunction {
    fun apply(a: Int, b: Long, c: String?, d: Double): Int
}

/** 8 参混合形态：`(IJLjava/lang/String;DIJLjava/lang/Object;)I`。 */
interface MixedOctFunction {
    fun apply(a: Int, b: Long, c: String?, d: Double, e: Int, f: Long, g: String?, h: Any?): Int
}

/** 4 个基本类型实参：`(IIII)I`，每个实参都要装箱。 */
interface IntQuadFunction {
    fun apply(a: Int, b: Int, c: Int, d: Int): Int
}

/** 4 个引用类型实参：`(Ljava/lang/Object;×4)I`，全部不装箱。 */
interface RefQuadFunction {
    fun apply(a: Any?, b: Any?, c: Any?, d: Any?): Int
}
