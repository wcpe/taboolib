package taboolib.module.nms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 验证版本策略只解析一次，并且不会吞掉真实实现错误。
 *
 * @author sky
 */
class VersionAdaptorTest {

    @Test
    fun `concurrent resolution reuses one implementation`() {
        val factoryCalls = AtomicInteger()
        val expected = Any()
        val adaptor = versionAdaptor(versionStrategy("current") {
            factoryCalls.incrementAndGet()
            expected
        })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 32).map {
                executor.submit<Any> {
                    start.await()
                    adaptor()
                }
            }
            start.countDown()
            futures.forEach { assertSame(expected, it.get()) }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(1, factoryCalls.get())
        assertEquals("current", adaptor.selectedName)
    }

    @Test
    fun `guard and compatibility failure select next strategy`() {
        val guardedCalls = AtomicInteger()
        val expected = Any()
        val adaptor = versionAdaptor(
            versionStrategy("guarded", guard = { false }) {
                guardedCalls.incrementAndGet()
                Any()
            },
            versionStrategy("missing") { throw NoSuchMethodException("missing") },
            versionStrategy("current") { expected },
        )

        assertSame(expected, adaptor())
        assertEquals(0, guardedCalls.get())
        assertEquals("current", adaptor.selectedName)
    }

    @Test
    fun `implementation failure is not treated as version mismatch`() {
        val expected = IllegalStateException("implementation bug")
        val fallbackCalls = AtomicInteger()
        val adaptor = versionAdaptor(
            versionStrategy("broken") { throw expected },
            versionStrategy("fallback") {
                fallbackCalls.incrementAndGet()
                Any()
            },
        )

        val actual = assertThrows(IllegalStateException::class.java) { adaptor() }
        assertSame(expected, actual)
        assertEquals(0, fallbackCalls.get())
        assertEquals("unresolved", adaptor.selectedName)
    }
}
