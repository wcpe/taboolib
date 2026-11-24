package taboolib.platform

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.scheduler.BukkitRunnable
import taboolib.common.Inject
import taboolib.common.platform.Awake
import taboolib.common.platform.Platform
import taboolib.common.platform.PlatformSide
import taboolib.common.platform.service.PlatformScheduler
import taboolib.common.platform.service.PlatformScheduler.EntityTarget
import taboolib.common.platform.service.PlatformScheduler.Scope
import taboolib.common.platform.service.PlatformScheduler.Task
import taboolib.common.util.Location

/** 单一注册入口；初始化时选定后端，之后只委托给它。 */
@Awake
@Inject
@PlatformSide(Platform.BUKKIT)
class BukkitPlatformScheduler : PlatformScheduler by
    (if (Folia.isFolia) FoliaSchedulerBackend() else BukkitSchedulerBackend())

/** 把现有实体绑定到调度作用域，不读取实体的位置，也不查找实体。 */
fun Entity.asSchedulerTarget(): EntityTarget = BukkitEntityTarget(this)

private class BukkitEntityTarget(val entity: Entity) : EntityTarget

internal fun EntityTarget.requireBukkitEntity(): Entity {
    return requireNotNull((this as? BukkitEntityTarget)?.entity) { "需要由 Bukkit 实体创建的调度目标" }
}

internal fun Location.requireBukkitWorld(): World {
    return requireNotNull(world?.let(Bukkit::getWorld)) { "区域调度位置必须指定已加载的世界：$world" }
}

internal class BukkitSchedulerBackend : PlatformScheduler {

    private val mainScope = Scope { delay, period, task -> submit(delay, period, false, task) }
    private val asyncScope = Scope { delay, period, task -> submit(delay, period, true, task) }

    override fun global(): Scope = mainScope

    override fun async(): Scope = asyncScope

    override fun region(location: Location): Scope {
        location.requireBukkitWorld()
        return mainScope
    }

    override fun entity(target: EntityTarget, onRetired: Runnable?): Scope {
        val entity = target.requireBukkitEntity()
        return Scope { delay, period, task ->
            val work = object : BukkitRunnable() {
                override fun run() {
                    if (entity.isValid) {
                        task.run()
                    } else {
                        cancel()
                        onRetired?.run()
                    }
                }
            }
            val handle = when {
                period > 0 -> work.runTaskTimer(BukkitPlugin.getInstance(), delay, period)
                delay > 0 -> work.runTaskLater(BukkitPlugin.getInstance(), delay)
                else -> work.runTask(BukkitPlugin.getInstance())
            }
            Task { handle.cancel() }
        }
    }

    private fun submit(delay: Long, period: Long, async: Boolean, task: Runnable): Task {
        val plugin = BukkitPlugin.getInstance()
        val scheduler = plugin.server.scheduler
        val handle = if (async) {
            when {
                period > 0 -> scheduler.runTaskTimerAsynchronously(plugin, task, delay, period)
                delay > 0 -> scheduler.runTaskLaterAsynchronously(plugin, task, delay)
                else -> scheduler.runTaskAsynchronously(plugin, task)
            }
        } else {
            when {
                period > 0 -> scheduler.runTaskTimer(plugin, task, delay, period)
                delay > 0 -> scheduler.runTaskLater(plugin, task, delay)
                else -> scheduler.runTask(plugin, task)
            }
        }
        return Task { handle.cancel() }
    }
}
