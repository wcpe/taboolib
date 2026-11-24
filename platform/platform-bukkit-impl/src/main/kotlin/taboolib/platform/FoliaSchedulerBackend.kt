package taboolib.platform

import taboolib.common.platform.service.PlatformScheduler
import taboolib.common.platform.service.PlatformScheduler.EntityTarget
import taboolib.common.platform.service.PlatformScheduler.Scope
import taboolib.common.platform.service.PlatformScheduler.Task
import taboolib.common.util.Location
import java.util.concurrent.TimeUnit.MILLISECONDS

/** 只在 Folia 上装配，调度和取消均使用公开 API。 */
internal class FoliaSchedulerBackend : PlatformScheduler {

    private val globalScope = Scope { delay, period, task ->
        val plugin = BukkitPlugin.getInstance()
        val scheduler = FoliaSchedulerAccess.global(plugin.server)
        val handle = when {
            period > 0 -> scheduler.runAtFixedRate(plugin, { task.run() }, delay.coerceAtLeast(1), period)
            delay > 0 -> scheduler.runDelayed(plugin, { task.run() }, delay)
            else -> scheduler.run(plugin) { task.run() }
        }
        Task { handle.cancel() }
    }

    private val asyncScope = Scope { delay, period, task ->
        val plugin = BukkitPlugin.getInstance()
        val scheduler = FoliaSchedulerAccess.async(plugin.server)
        val handle = when {
            period > 0 -> scheduler.runAtFixedRate(plugin, { task.run() }, millis(delay), millis(period), MILLISECONDS)
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
            val scheduler = FoliaSchedulerAccess.region(plugin.server)
            val handle = when {
                period > 0 -> scheduler.runAtFixedRate(plugin, target, { task.run() }, delay.coerceAtLeast(1), period)
                delay > 0 -> scheduler.runDelayed(plugin, target, { task.run() }, delay)
                else -> scheduler.run(plugin, target) { task.run() }
            }
            Task { handle.cancel() }
        }
    }

    override fun entity(target: EntityTarget, onRetired: Runnable?): Scope {
        val scheduler = FoliaSchedulerAccess.entity(target.requireBukkitEntity())
        return Scope { delay, period, task ->
            val plugin = BukkitPlugin.getInstance()
            val handle = when {
                period > 0 -> scheduler.runAtFixedRate(plugin, { task.run() }, onRetired, delay.coerceAtLeast(1), period)
                delay > 0 -> scheduler.runDelayed(plugin, { task.run() }, onRetired, delay)
                else -> scheduler.run(plugin, { task.run() }, onRetired)
            }
            checkNotNull(handle) { "实体已失效，任务未入队" }
            Task { handle.cancel() }
        }
    }

    private fun millis(ticks: Long): Long = Math.multiplyExact(ticks, 50L)
}
