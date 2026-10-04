package taboolib.module.incision.perf

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
import java.util.function.IntUnaryOperator

/**
 * 织入调用开销**计时型**验收（报告性质）—— 默认不随常规 test 执行。
 *
 * 打 `@Tag("perf")`，并在 `build.gradle.kts` 的 `tasks.test` 里 `excludeTags("perf")`；
 * 需要人工看数值时显式点名：
 * ```
 * ./gradlew :module:incision:test --tests "*WovenInvocationOverheadTest*"
 * ```
 *
 * 三组对照（同一份目标类的两种字节码，分别定义在两个独立 [ByteClassLoader] 里）：
 *
 * | 组 | 内容 | 覆盖的实际成本 |
 * |---|---|---|
 * | ① | 未织入字节码 | 纯接口调用 + 循环/黑盒开销（基线） |
 * | ② | 已织入 + [IncisionReflex.withoutIncision] | 入口注入 + `IncisionBridge` 反射路由 + 抑制短路 |
 * | ③ | 已织入 + 已注册空 Lead 链 | ② 的全部 + dispatcher 解析 + 链执行（`THEATRE` 那一路） |
 *
 * 只守两条与机器无关的边界：
 * - `② - ① < 500ns/次`（宽上限：只断定"不是数量级劣化"，**不**引用归档报告里的具体数字当阈值）；
 * - `③ - ② > 0`（证明链确实被执行，而不是被静默短路）。
 *
 * 明确**不**断言 `③/①` 倍数：JIT 内联、GC、CPU 调度都会让倍数在机器间剧烈漂移。
 */
@Tag("perf")
@DisplayName("织入调用开销（计时型）")
class WovenInvocationOverheadTest {

    private val ownerInternal = "taboolib/module/incision/perf/WovenTarget"
    private val target = MethodCoordinate(ownerInternal, "applyAsInt", "(I)I")
    private val adviceId = "perf-empty-lead"

    /**
     * 黑盒出口：每轮把累加值发布到 volatile 字段。
     * 没有它，JIT 可以证明循环结果无人使用而整轮删除（计时就会变成"测了个空循环"）。
     */
    @Volatile
    private var sink: Int = 0

    private val handlerCalls = AtomicInteger()

    @AfterEach
    fun releaseAdvice() {
        TheatreDispatcher.unregister(target, adviceId)
    }

    @Test
    @DisplayName("空 Lead 链的织入开销低于宽上限，且 advice 链确实被执行")
    fun wovenOverheadStaysWithinWideBound() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val unwovenBytes = buildTargetBytes()
        val wovenBytes = weave(unwovenBytes)
        val unwoven = defineInFreshLoader(unwovenBytes)
        val woven = defineInFreshLoader(wovenBytes)
        registerEmptyLeadAdvice()

        // 语义基线：织入不得改变返回值（否则后面测的就不是同一个方法了）
        assertEquals(unwoven.applyAsInt(41), woven.applyAsInt(41), "织入后返回值必须与原方法一致")

        // 预热：让两个接口调用点都完成 C2 内联决策，三种形态各预热一轮
        repeat(WARMUP_ROUNDS) {
            elapsedNanos(unwoven, WARMUP_ITERATIONS)
            elapsedNanos(woven, WARMUP_ITERATIONS)
            IncisionReflex.withoutIncision { elapsedNanos(woven, WARMUP_ITERATIONS) }
        }

        val baseline = median(measureRounds(unwoven))
        val callsBeforeSuppressed = handlerCalls.get()
        val suppressed = IncisionReflex.withoutIncision { median(measureRounds(woven)) }
        assertEquals(
            callsBeforeSuppressed, handlerCalls.get(),
            "withoutIncision 作用域内不得执行 advice 链（否则 ② 组测的不是抑制路径）",
        )
        val chained = median(measureRounds(woven))
        assertTrue(
            handlerCalls.get() > callsBeforeSuppressed,
            "已织入且未抑制时 advice 链必须被执行，实际链触发次数 ${handlerCalls.get()} - $callsBeforeSuppressed",
        )

        report(baseline, suppressed, chained)

        assertTrue(
            suppressed - baseline < MAX_WOVEN_OVERHEAD_NANOS,
            "② - ① 必须低于宽上限：②=${ns(suppressed)} ①=${ns(baseline)} 差=${ns(suppressed - baseline)}，" +
                "上限=${ns(MAX_WOVEN_OVERHEAD_NANOS)}",
        )
        assertTrue(
            chained > suppressed,
            "③ 必须大于 ②（链执行应产生可观测开销）：③=${ns(chained)} ②=${ns(suppressed)}",
        )
    }

    // ===== 目标类与字节码 =====

    /**
     * 目标类：实现 JDK 的 [IntUnaryOperator]，`applyAsInt(I)I` 只做一次加法。
     *
     * 用 JDK 接口而不是反射调用来驱动：`Method.invoke` 自身的开销就有几十纳秒，会淹没织入开销、
     * 让 ②-① 失去判别力。方法体保持极短，三组之间的差异就只剩"入口注入 + Bridge + 链"。
     */
    private fun buildTargetBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            ownerInternal,
            null,
            "java/lang/Object",
            arrayOf("java/util/function/IntUnaryOperator"),
        )
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "applyAsInt", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 纯字节进/字节出织入，不加载 JVMTI（`useJvmtiBaseline = false`）。 */
    private fun weave(originalBytes: ByteArray): ByteArray = Scalpel(
        resolver = NoopResolver,
        targetsByOwner = mapOf(
            ownerInternal to listOf(
                Scalpel.AdviceTargetSpec(target, setOf(AdviceKind.LEAD)),
            ),
        ),
    ).weave(originalBytes)

    /** 注册"空 Lead"：handler 什么都不做，只计数；由此 ③-② 就是纯链执行成本。 */
    private fun registerEmptyLeadAdvice() {
        TheatreDispatcher.register(
            AdviceEntry(
                id = adviceId,
                kind = AdviceKind.LEAD,
                target = target,
                priority = 0,
                handler = {
                    handlerCalls.incrementAndGet()
                    null
                },
            ),
        )
    }

    /**
     * 每组对照独立一份 ClassLoader：同一份字节码定义两份，互不影响。
     *
     * child-first 只对本用例生成的类生效，其余仍走父加载器，
     * 保证 `IncisionBridge` / `IntUnaryOperator` 在测试与目标类之间是同一个类型。
     */
    private fun defineInFreshLoader(bytes: ByteArray): IntUnaryOperator {
        val binaryName = ownerInternal.replace('/', '.')
        val cls = ByteClassLoader(binaryName, bytes).loadClass(binaryName)
        return cls.getDeclaredConstructor().newInstance() as IntUnaryOperator
    }

    // ===== 计时 =====

    /**
     * 固定迭代数的一轮计时（纳秒）。
     *
     * 迭代变量既作为实参传入（阻止常量折叠），累加值又通过 [sink] 发布（阻止死代码删除）。
     */
    private fun elapsedNanos(op: IntUnaryOperator, iterations: Int): Long {
        var acc = 0
        val start = System.nanoTime()
        for (i in 0 until iterations) {
            acc = acc xor op.applyAsInt(i)
            if (i and PUBLISH_MASK == 0) sink = acc
        }
        sink = acc
        return System.nanoTime() - start
    }

    /** 多轮采样，每轮取"单次调用纳秒"，由 [median] 收敛掉偶发抖动。 */
    private fun measureRounds(op: IntUnaryOperator): DoubleArray {
        val rounds = DoubleArray(MEASURE_ROUNDS)
        for (round in 0 until MEASURE_ROUNDS) {
            rounds[round] = elapsedNanos(op, ITERATIONS) / ITERATIONS.toDouble()
        }
        return rounds
    }

    private fun median(values: DoubleArray): Double {
        val sorted = values.sortedArray()
        return sorted[sorted.size / 2]
    }

    /** 打印三组数值，便于人工直接读（Gradle 用 `--info` 或测试报告查看标准输出）。 */
    private fun report(baseline: Double, suppressed: Double, chained: Double) {
        listOf(
            "===== 织入调用开销（单次调用 ns 中位数：$ITERATIONS 次/轮 × $MEASURE_ROUNDS 轮）=====",
            "① 未织入基线                      : ${ns(baseline)}",
            "② 已织入 + withoutIncision{}      : ${ns(suppressed)}   (②-① = ${ns(suppressed - baseline)})",
            "③ 已织入 + 空 Lead 链              : ${ns(chained)}   (③-② = ${ns(chained - suppressed)})",
            "===============================================================",
        ).forEach { println(it) }
    }

    private fun ns(value: Double): String = String.format(Locale.ROOT, "%8.2f ns", value)

    private class ByteClassLoader(
        private val generatedName: String,
        private val generatedBytes: ByteArray,
    ) : ClassLoader(WovenInvocationOverheadTest::class.java.classLoader) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != generatedName) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: defineClass(name, generatedBytes, 0, generatedBytes.size)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }

    private companion object {
        /** 每轮迭代数：单轮 ~15-30ms，足以摊薄 `System.nanoTime` 与调度噪声。 */
        const val ITERATIONS = 200_000

        /** 采样轮数；取中位数而非均值，避开偶发 GC / 抢占导致的离群轮次。 */
        const val MEASURE_ROUNDS = 7

        /**
         * 预热轮数与每轮迭代：这条链路跨「目标方法 → Bridge → MethodHandle → Dispatcher → 链」五层，
         * 实测需 ~1M 次调用才收敛到稳态——旧的 240K 次（4 × 60,000）下前几轮仍偏高约 40%，
         * 既会让形态之间的比较失真，也会让"优化前后"的对比带上系统性偏差。
         */
        const val WARMUP_ROUNDS = 6
        const val WARMUP_ITERATIONS = 200_000

        /** 黑盒发布间隔掩码（2^n - 1）。 */
        const val PUBLISH_MASK = 0x3FF

        /**
         * ② - ① 的宽上限。
         *
         * 它是"量级判据"而非精度判据：只用来拦住数量级劣化（例如入口被写成逐调用反射查找、
         * 或参数数组被反复重建）。**刻意不引用任何归档报告中的具体数字**，那些数字随机器与 JVM 版本漂移。
         */
        const val MAX_WOVEN_OVERHEAD_NANOS = 500.0
    }
}
