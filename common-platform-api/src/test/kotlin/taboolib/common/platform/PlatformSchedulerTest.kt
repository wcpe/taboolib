package taboolib.common.platform

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import taboolib.common.platform.service.PlatformScheduler

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
}
