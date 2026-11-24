package taboolib.platform

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.function.Consumer

/**
 * 实体退休契约：调度器已退休时三个提交入口都返回 null 且不触发回调，
 * 后端须补发一次 [onRetired]；正常路径由调度器自身回调，不重复通知。
 */
class FoliaSchedulerBackendEntityContractTest {

    private val plugin = Proxy.newProxyInstance(
        Plugin::class.java.classLoader,
        arrayOf(Plugin::class.java)
    ) { _, _, _ -> error("不应调用插件方法") } as Plugin

    private fun schedulerReturning(handle: ScheduledTask?): EntityScheduler {
        return Proxy.newProxyInstance(
            EntityScheduler::class.java.classLoader,
            arrayOf(EntityScheduler::class.java),
            InvocationHandler { _, _, _ -> handle }
        ) as EntityScheduler
    }

    private fun fakeTask(onCancel: () -> Unit = {}): ScheduledTask {
        return Proxy.newProxyInstance(
            ScheduledTask::class.java.classLoader,
            arrayOf(ScheduledTask::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "cancel" -> {
                    onCancel()
                    ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                }
                else -> error("未预期的调用：${method.name}")
            }
        } as ScheduledTask
    }

    @Test
    @DisplayName("调度器已退休：补发退休回调，任务不执行，取消句柄为空操作")
    fun retiredSchedulerCompletesRetiredCallback() {
        var retired = 0
        var ran = false
        val scheduler = schedulerReturning(null)

        val task = FoliaSchedulerBackend.submitEntityTask(scheduler, plugin, 0, 0, Runnable { ran = true }, Runnable { retired++ })
        task.cancel()

        assertEquals(1, retired)
        assertFalse(ran)
    }

    @Test
    @DisplayName("延迟与周期提交在已退休调度器上同样补发退休回调")
    fun retiredSchedulerCoversDelayedAndPeriodic() {
        var retired = 0
        val scheduler = schedulerReturning(null)

        FoliaSchedulerBackend.submitEntityTask(scheduler, plugin, 3, 0, Runnable { }, Runnable { retired++ })
        FoliaSchedulerBackend.submitEntityTask(scheduler, plugin, 0, 5, Runnable { }, Runnable { retired++ })

        assertEquals(2, retired)
    }

    @Test
    @DisplayName("正常提交：不补发退休回调，取消句柄透传到底层 ScheduledTask")
    fun liveSchedulerDoesNotDuplicateRetired() {
        var retired = 0
        var cancelled = false
        var ran = false
        val scheduler = schedulerReturning(fakeTask { cancelled = true })

        val task = FoliaSchedulerBackend.submitEntityTask(
            scheduler, plugin, 0, 0,
            Runnable { ran = true }, Runnable { retired++ }
        )
        task.cancel()

        assertEquals(0, retired)
        assertFalse(ran)
        assertTrue(cancelled)
    }
}
