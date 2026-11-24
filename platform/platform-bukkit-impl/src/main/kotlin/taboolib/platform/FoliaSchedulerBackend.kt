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
            // 实体在提交时即已失效，Folia 会同步触发退休回调并返回 null，此时没有可取消的句柄
            handle?.let { Task { it.cancel() } } ?: Task { }
        }
    }

    private fun millis(ticks: Long): Long = Math.multiplyExact(ticks, 50L)
}
