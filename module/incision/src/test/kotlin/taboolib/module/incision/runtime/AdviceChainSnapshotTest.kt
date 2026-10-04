package taboolib.module.incision.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import taboolib.module.incision.api.MethodCoordinate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * AdviceChain 快照语义验收。
 *
 * 热路径现在读的是"链变更时重建、volatile 发布的不可修改快照"，因此测试必须守住三件事：
 *
 * 1. **可见性**：add / remove 返回后，同一线程立刻就能在 [AdviceChain.list] 里看到结果
 *    （不允许出现"注册了 advice 却仍走旧快照"）；
 * 2. **不可变性**：快照是共享视图，调用方改动必须失败，而不是静默污染整条链；
 * 3. **顺序**：优先级降序、同优先级按注册顺序 —— 与旧的 `entries.sortByDescending` 一致。
 */
@DisplayName("AdviceChain 快照语义")
class AdviceChainSnapshotTest {

    private val target = MethodCoordinate("com/example/Foo", "bar", "()V")

    private fun entry(id: String, priority: Int, cl: ClassLoader? = null) = AdviceEntry(
        id = id,
        kind = AdviceKind.LEAD,
        target = target,
        priority = priority,
        handler = { null },
        classLoader = cl?.let { java.lang.ref.WeakReference(it) },
    )

    @Test
    @DisplayName("add/remove 返回后立即可见（无过期快照）")
    fun mutationsAreImmediatelyVisible() {
        val chain = AdviceChain(target)
        assertTrue(chain.isEmpty())
        assertTrue(chain.list().isEmpty())

        chain.add(entry("a", 0))
        assertEquals(listOf("a"), chain.list().map { it.id }, "add 返回后必须立刻可见")
        assertFalse(chain.isEmpty())

        chain.add(entry("b", 0))
        assertEquals(listOf("a", "b"), chain.list().map { it.id })

        assertTrue(chain.remove("a"))
        assertEquals(listOf("b"), chain.list().map { it.id }, "remove 返回后必须立刻可见")
        assertFalse(chain.remove("missing"))
        assertEquals(listOf("b"), chain.list().map { it.id })
    }

    @Test
    @DisplayName("优先级降序、同优先级保持注册顺序，且变更后立即重排")
    fun keepsPriorityOrdering() {
        val chain = AdviceChain(target)
        chain.add(entry("low", 1))
        chain.add(entry("high", 9))
        chain.add(entry("mid-a", 5))
        chain.add(entry("mid-b", 5))
        assertEquals(listOf("high", "mid-a", "mid-b", "low"), chain.list().map { it.id })

        // 同 id 覆盖：替换而不是追加（聚合计划重装依赖该语义）
        chain.add(entry("mid-a", 7))
        assertEquals(listOf("high", "mid-a", "mid-b", "low"), chain.list().map { it.id })
        assertEquals(7, chain.list().first { it.id == "mid-a" }.priority)
        assertEquals(4, chain.list().size)
    }

    @Test
    @DisplayName("按 ClassLoader 卸载：计数正确且快照同步更新")
    fun removesByClassLoader() {
        val loaderA = ClassLoader.getSystemClassLoader()
        val loaderB = ClassLoader.getSystemClassLoader().parent
        val chain = AdviceChain(target)
        chain.add(entry("a1", 0, loaderA))
        chain.add(entry("a2", 0, loaderA))
        chain.add(entry("b1", 0, loaderB))
        chain.add(entry("none", 0))

        assertEquals(2, chain.removeByClassLoader(loaderA))
        assertEquals(listOf("b1", "none"), chain.list().map { it.id }.sorted())
        assertEquals(0, chain.removeByClassLoader(loaderA), "已卸载的 loader 再次卸载应为 0")
        assertEquals(2, chain.list().size)
    }

    @Test
    @DisplayName("快照不可被调用方修改（共享视图不能变成可变入口）")
    fun snapshotIsUnmodifiable() {
        val chain = AdviceChain(target)
        chain.add(entry("a", 0))
        val snapshot = chain.list()
        @Suppress("UNCHECKED_CAST")
        val mutable = snapshot as MutableList<AdviceEntry>
        assertThrows(UnsupportedOperationException::class.java) { mutable.add(entry("evil", 0)) }
        assertThrows(UnsupportedOperationException::class.java) { mutable.removeAt(0) }
        assertThrows(UnsupportedOperationException::class.java) { mutable.clear() }
        assertEquals(listOf("a"), chain.list().map { it.id }, "失败的改动不得影响链本身")
    }

    @Test
    @DisplayName("并发读写：读侧不抛异常，全部写完成后快照等于最终状态")
    fun concurrentAddAndReadStaysConsistent() {
        val chain = AdviceChain(target)
        val writers = 4
        val perWriter = 200
        val start = CountDownLatch(1)
        val readerFailure = AtomicReference<Throwable>()
        val threads = mutableListOf<Thread>()

        // 读侧：持续遍历快照；旧实现若把"边写边读"扩成可变集合，这里会看到撕裂/并发修改异常
        repeat(2) {
            threads += Thread {
                start.await()
                try {
                    var checksum = 0
                    while (!Thread.currentThread().isInterrupted) {
                        val snapshot = chain.list()
                        for (e in snapshot) checksum += e.id.length
                        if (checksum == Int.MIN_VALUE) break
                    }
                } catch (t: Throwable) {
                    readerFailure.compareAndSet(null, t)
                }
            }
        }
        // 写侧：每个线程只追加自己的唯一 id，不删除
        repeat(writers) { w ->
            threads += Thread {
                start.await()
                repeat(perWriter) { i -> chain.add(entry("w$w-$i", w * perWriter + i)) }
            }
        }

        threads.forEach { it.start() }
        start.countDown()
        threads.drop(2).forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }
        assertTrue(readerFailure.get() == null, "并发读侧异常: ${readerFailure.get()}")
        threads.take(2).forEach { it.interrupt() }
        threads.take(2).forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        val finalIds = chain.list().map { it.id }.toSet()
        assertEquals(writers * perWriter, finalIds.size, "全部写入完成后快照必须完整")
        assertEquals(chain.list().size, chain.list().distinctBy { it.id }.size, "快照不得出现重复条目")
    }
}
