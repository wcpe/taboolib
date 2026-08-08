package taboolib.module.incision.loader

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * JvmtiBackend 防重入保护测试
 *
 * 复现场景：
 *   Scalpel.weave(AdvancementDataPlayer)
 *     → RemapperBridge.mapFieldName
 *       → RemapTranslationLegacy.findParents
 *         → ClassHelper.getClass → Class.forName
 *           → ClassLoader.defineClass1 (触发 JVMTI ClassFileLoadHook)
 *             → JvmtiBackend.onClassFileLoad (再次进入 transformer)
 *               → Scalpel.weave → ... StackOverflowError
 */
@DisplayName("JvmtiBackend 防重入保护")
class JvmtiBackendReentrantTest {

    @Suppress("UNCHECKED_CAST")
    private fun getTransformers(): ConcurrentHashMap<String, CopyOnWriteArrayList<(ByteArray) -> ByteArray?>> {
        val field = JvmtiBackend::class.java.getDeclaredField("transformers")
        field.isAccessible = true
        // 测试通过反射直接操作生产表，value 类型必须与生产代码完全一致；ArrayList 会在
        // onClassFileLoad 读取字段时触发 erased generic 之后的 CopyOnWriteArrayList 强转失败。
        return field.get(JvmtiBackend) as ConcurrentHashMap<String, CopyOnWriteArrayList<(ByteArray) -> ByteArray?>>
    }

    private fun getReentrantGuard(): ThreadLocal<Boolean> {
        val field = JvmtiBackend::class.java.getDeclaredField("reentrantGuard")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(JvmtiBackend) as ThreadLocal<Boolean>
    }

    @BeforeEach
    fun reset() {
        getReentrantGuard().remove()
        getTransformers().clear()
    }

    // ===== 基础功能 =====

    @Test
    @DisplayName("正常情况下 transformer 被调用并返回修改后的字节")
    fun normalTransformerInvocation() {
        val callCount = AtomicInteger(0)
        val transformers = getTransformers()
        transformers.computeIfAbsent("com/example/TestClass") { CopyOnWriteArrayList() }
            .add { bytes -> callCount.incrementAndGet(); bytes }

        val result = JvmtiBackend.onClassFileLoad(null, "com/example/TestClass", byteArrayOf(1, 2, 3))

        assertEquals(1, callCount.get())
        assertNotNull(result)
    }

    @Test
    @DisplayName("无注册 transformer 时返回 null 不做任何处理")
    fun noTransformerReturnsNull() {
        val result = JvmtiBackend.onClassFileLoad(null, "com/example/Unknown", byteArrayOf(1))
        assertNull(result)
    }

    // ===== 核心：递归地狱复现 =====

    @Test
    @DisplayName("互相触发类加载的两个 transformer 形成 A→B→A 递归，防重入保护打断递归链")
    fun recursiveTransformerHellIsPrevented() {
        val callCountA = AtomicInteger(0)
        val callCountB = AtomicInteger(0)
        val transformers = getTransformers()

        // transformer B：处理 InnerClass 时反向触发 OuterClass 加载
        transformers.computeIfAbsent("com/example/InnerClass") { CopyOnWriteArrayList() }
            .add { bytes ->
                callCountB.incrementAndGet()
                JvmtiBackend.onClassFileLoad(null, "com/example/OuterClass", byteArrayOf(0xCA.toByte()))
                bytes
            }

        // transformer A：处理 OuterClass 时触发 InnerClass 加载
        transformers.computeIfAbsent("com/example/OuterClass") { CopyOnWriteArrayList() }
            .add { bytes ->
                callCountA.incrementAndGet()
                JvmtiBackend.onClassFileLoad(null, "com/example/InnerClass", byteArrayOf(0xFE.toByte()))
                bytes
            }

        // 入口：加载 OuterClass
        val result = JvmtiBackend.onClassFileLoad(null, "com/example/OuterClass", byteArrayOf(1, 2, 3))

        // A 执行 1 次，B 被防重入拦截不执行
        assertEquals(1, callCountA.get())
        assertEquals(0, callCountB.get())
        assertNotNull(result)
    }

    @Test
    @DisplayName("单类自引用递归（transformer 内部再次触发同一类的 onClassFileLoad）被拦截")
    fun selfRecursiveTransformerIsPrevented() {
        val callCount = AtomicInteger(0)
        val transformers = getTransformers()

        transformers.computeIfAbsent("com/example/SelfRef") { CopyOnWriteArrayList() }
            .add { bytes ->
                val depth = callCount.incrementAndGet()
                if (depth > 100) {
                    fail<Unit>("递归深度超过 100，防重入保护失效")
                }
                JvmtiBackend.onClassFileLoad(null, "com/example/SelfRef", bytes)
                bytes
            }

        val result = JvmtiBackend.onClassFileLoad(null, "com/example/SelfRef", byteArrayOf(1))

        assertEquals(1, callCount.get())
        assertNotNull(result)
    }

    @Test
    @DisplayName("三层循环依赖 A→B→C→A 被防重入保护打断")
    fun deepRecursiveChainIsBroken() {
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val transformers = getTransformers()

        for (cls in listOf("com/example/A", "com/example/B", "com/example/C")) {
            counts[cls] = AtomicInteger(0)
            val nextCls = when (cls) {
                "com/example/A" -> "com/example/B"
                "com/example/B" -> "com/example/C"
                else -> "com/example/A"
            }
            transformers.computeIfAbsent(cls) { CopyOnWriteArrayList() }
                .add { bytes ->
                    counts[cls]!!.incrementAndGet()
                    JvmtiBackend.onClassFileLoad(null, nextCls, byteArrayOf(0))
                    bytes
                }
        }

        JvmtiBackend.onClassFileLoad(null, "com/example/A", byteArrayOf(1))

        assertEquals(1, counts["com/example/A"]!!.get())
        assertEquals(0, counts["com/example/B"]!!.get())
        assertEquals(0, counts["com/example/C"]!!.get())
    }

    // ===== 线程隔离 =====

    @Test
    @DisplayName("防重入标记是 ThreadLocal 的，不阻塞其他线程的正常 transformer 执行")
    fun reentrantGuardIsPerThread() {
        val threadBCount = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val transformers = getTransformers()

        transformers.computeIfAbsent("com/example/SharedClass") { CopyOnWriteArrayList() }
            .add { bytes -> threadBCount.incrementAndGet(); bytes }

        // 线程 A：手动设置重入标记模拟正在 weave
        val threadA = Thread({
            getReentrantGuard().set(true)
            val result = JvmtiBackend.onClassFileLoad(null, "com/example/SharedClass", byteArrayOf(1))
            assertNull(result)
            latch.countDown()
        }, "thread-A")

        // 线程 B：正常调用
        val threadB = Thread({
            latch.await()
            val result = JvmtiBackend.onClassFileLoad(null, "com/example/SharedClass", byteArrayOf(2))
            assertNotNull(result)
        }, "thread-B")

        threadA.start()
        threadB.start()
        threadA.join(5000)
        threadB.join(5000)

        assertEquals(1, threadBCount.get())
    }

    // ===== 异常安全 =====

    @Test
    @DisplayName("transformer 抛异常后重入标记正确清除，后续调用不受影响")
    fun reentrantGuardClearedAfterException() {
        val transformers = getTransformers()
        transformers.computeIfAbsent("com/example/ErrorClass") { CopyOnWriteArrayList() }
            .add { throw RuntimeException("模拟异常") }

        // 第一次：异常
        JvmtiBackend.onClassFileLoad(null, "com/example/ErrorClass", byteArrayOf(1))

        // 第二次：标记应已清除
        val secondCount = AtomicInteger(0)
        transformers["com/example/ErrorClass"]!!.clear()
        transformers["com/example/ErrorClass"]!!.add { bytes -> secondCount.incrementAndGet(); bytes }

        val result = JvmtiBackend.onClassFileLoad(null, "com/example/ErrorClass", byteArrayOf(2))
        assertEquals(1, secondCount.get())
        assertNotNull(result)
    }
}
