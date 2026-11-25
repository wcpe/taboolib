package taboolib.common.platform.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import taboolib.common.util.Location

class PlatformSchedulerTest {

    @Test
    @DisplayName("三个时序入口统一提交且不就地执行任务")
    fun submitsTimingAndReturnsCancellationHandle() {
        val submissions = mutableListOf<Pair<Long, Long>>()
        var cancellations = 0
        var ranInline = false
        val scope = PlatformScheduler.Scope { delay, period, _ ->
            submissions += delay to period
            PlatformScheduler.Task { cancellations++ }
        }

        scope.schedule { ranInline = true }.cancel()
        scope.schedule(delayTicks = 4) { ranInline = true }
        scope.schedule(periodTicks = 7) { ranInline = true }

        assertEquals(listOf(0L to 0L, 4L to 0L, 0L to 7L), submissions)
        assertEquals(1, cancellations)
        assertFalse(ranInline)
    }

    @Test
    @DisplayName("负数时序在提交给后端以前被拒绝")
    fun rejectsInvalidTimingBeforeDispatch() {
        val scope = PlatformScheduler.Scope { _, _, _ -> error("不应调用后端") }

        assertThrows(IllegalArgumentException::class.java) { scope.schedule(delayTicks = -1) {} }
        assertThrows(IllegalArgumentException::class.java) { scope.schedule(periodTicks = -1) {} }
    }

    @Test
    @DisplayName("后端拒绝接收任务时异常向上传播")
    fun propagatesBackendRejection() {
        val scope = PlatformScheduler.Scope { _, _, _ -> throw IllegalStateException("插件已禁用") }

        val exception = assertThrows(IllegalStateException::class.java) { scope.schedule {} }
        assertEquals("插件已禁用", exception.message)
    }

    @Test
    @DisplayName("单参 entity 等价于传入空的退休回调")
    fun entityWithoutRetiredDelegatesToNull() {
        var received: Runnable? = Runnable { }
        val target = object : PlatformScheduler.EntityTarget {}
        val scheduler = object : PlatformScheduler {

            override fun global() = error("未使用")

            override fun async() = error("未使用")

            override fun region(location: Location) = error("未使用")

            override fun entity(target: PlatformScheduler.EntityTarget, onRetired: Runnable?): PlatformScheduler.Scope {
                received = onRetired
                return PlatformScheduler.Scope { _, _, _ -> PlatformScheduler.Task { } }
            }
        }

        scheduler.entity(target)

        assertNull(received)
    }
}
