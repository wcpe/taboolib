package taboolib.platform

import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.plugin.Plugin
import taboolib.common.platform.service.PlatformScheduler
import taboolib.common.platform.service.PlatformScheduler.EntityTarget
import taboolib.common.platform.service.PlatformScheduler.Scope
import taboolib.common.platform.service.PlatformScheduler.Task
import taboolib.common.util.Location
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * 只在 Folia 上装配，调度和取消均使用公开 API。
 *
 * 调度器实例统一取自 [FoliaExecutor]：主源集按旧 Bukkit 接口解析 Server，无法直接调用
 * getGlobalRegionScheduler 之类的方法，而 [FoliaExecutor] 已在静态初始化时用反射取好。
 */
internal class FoliaSchedulerBackend : PlatformScheduler {

    private val globalScheduler
        get() = FoliaExecutor.GLOBAL_REGION_SCHEDULER ?: error("Folia 全局区域调度器未初始化")

    private val asyncScheduler
        get() = FoliaExecutor.ASYNC_SCHEDULER ?: error("Folia 异步调度器未初始化")

    private val regionScheduler
        get() = FoliaExecutor.REGION_SCHEDULER ?: error("Folia 区域调度器未初始化")

    private val globalScope = Scope { delay, period, task ->
        val plugin = BukkitPlugin.getInstance()
        val scheduler = globalScheduler
        val handle = when {
            period > 0 -> scheduler.runAtFixedRate(plugin, { task.run() }, delay.coerceAtLeast(1), period)
            delay > 0 -> scheduler.runDelayed(plugin, { task.run() }, delay)
            else -> scheduler.run(plugin) { task.run() }
        }
        Task { handle.cancel() }
    }

    private val asyncScope = Scope { delay, period, task ->
        val plugin = BukkitPlugin.getInstance()
        val scheduler = asyncScheduler
        val handle = when {
            // Folia 的异步调度要求初始延时大于零，delay 为 0 时按最早的一毫秒处理
            period > 0 -> scheduler.runAtFixedRate(plugin, { task.run() }, millis(delay.coerceAtLeast(1)), millis(period), MILLISECONDS)
            delay > 0 -> scheduler.runDelayed(plugin, { task.run() }, millis(delay), MILLISECONDS)
            else -> scheduler.runNow(plugin) { task.run() }
        }
        Task { handle.cancel() }
    }

    override fun global(): Scope = globalScope

    override fun async(): Scope = asyncScope

    override fun region(location: Location): Scope {
        val target = with(location) { org.bukkit.Location(requireBukkitWorld(), x, y, z, yaw, pitch) }
        return Scope { delay, period, task ->
            val plugin = BukkitPlugin.getInstance()
            val scheduler = regionScheduler
            val handle = when {
                period > 0 -> scheduler.runAtFixedRate(plugin, target, { task.run() }, delay.coerceAtLeast(1), period)
                delay > 0 -> scheduler.runDelayed(plugin, target, { task.run() }, delay)
                else -> scheduler.run(plugin, target) { task.run() }
            }
            Task { handle.cancel() }
        }
    }

    override fun entity(target: EntityTarget, onRetired: Runnable?): Scope {
        val scheduler = FoliaExecutor.getEntityScheduler(target.requireBukkitEntity())
        return Scope { delay, period, task ->
            submitEntityTask(scheduler, BukkitPlugin.getInstance(), delay, period, task, onRetired)
        }
    }

    private fun millis(ticks: Long): Long = Math.multiplyExact(ticks, 50L)

    internal companion object {

        /**
         * 向实体调度器提交任务。
         *
         * 调度器已退休时 `run` / `runDelayed` / `runAtFixedRate` 返回 null 且不会触发任何回调，
         * 这里补发一次退休通知以兑现 [PlatformScheduler.entity] 的契约；实体在任务入队后才
         * 失效的正常路径由调度器自身触发退休回调，不重复通知。
         */
        internal fun submitEntityTask(scheduler: EntityScheduler, plugin: Plugin, delay: Long, period: Long, task: Runnable, onRetired: Runnable?): Task {
            val handle: ScheduledTask? = when {
                period > 0 -> scheduler.runAtFixedRate(plugin, { task.run() }, onRetired, delay.coerceAtLeast(1), period)
                delay > 0 -> scheduler.runDelayed(plugin, { task.run() }, onRetired, delay)
                else -> scheduler.run(plugin, { task.run() }, onRetired)
            }
            if (handle == null) {
                onRetired?.run()
            }
            return handle?.let { Task { it.cancel() } } ?: Task { }
        }
    }
}
