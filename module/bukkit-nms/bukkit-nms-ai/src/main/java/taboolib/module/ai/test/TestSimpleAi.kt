package taboolib.module.ai.test

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.EntityType
import org.bukkit.entity.Villager
import org.tabooproject.reflex.Reflex.Companion.getProperty
import org.tabooproject.reflex.Reflex.Companion.invokeMethod
import org.tabooproject.reflex.Reflex.Companion.setProperty
import taboolib.common.Test
import taboolib.module.ai.*
import taboolib.module.nms.MinecraftVersion

/**
 * TabooLib
 * taboolib.test.TestSimpleAi
 *
 * @author 坏黑
 * @since 2023/8/4 02:32
 */
object TestSimpleAi : Test() {

    override fun check(): List<Result> {
        val worlds = Bukkit.getWorlds()
        if (worlds.isEmpty()) {
            return listOf(Failure.of("AI:NO_WORLD"))
        }
        val results = arrayListOf<Result>()
        val world = worlds[0]
        // 生成实体
        val mob = world.spawnEntity(world.spawnLocation, EntityType.VILLAGER) as Villager
        try {
            mob.isInvulnerable = true
        } catch (_: Throwable) {
        }
        val resetSelectors = {
            mob.clearGoalAi()
            mob.clearTargetAi()
        }
        val testAi = object : SimpleAi() {

            override fun shouldExecute(): Boolean {
                return false
            }
        }
        // 1.17+ 的 Reflex 才会执行字段重映射，旧版需直接读取 ControllerLook 的实际字段。
        val controllerLookRemap = MinecraftVersion.isUniversal
        val controllerWantedX = if (controllerLookRemap) "wantedX" else "e"
        val controllerWantedY = if (controllerLookRemap) "wantedY" else "f"
        val controllerWantedZ = if (controllerLookRemap) "wantedZ" else "g"
        // 测试功能
        results += sandbox("AI:clearAi()") {
            try {
                resetSelectors()
                check(mob.getGoalAi().none())
                check(mob.getTargetAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:goalSelectorRoundTrip") {
            try {
                resetSelectors()
                mob.addGoalAi(testAi, 7)
                val goals = mob.getGoalAi().toList()
                check(goals.size == 1)
                mob.setGoalAi(goals)
                check(mob.getGoalAi().count() == 1)
                mob.removeGoalAi(7)
                check(mob.getGoalAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:targetSelectorRoundTrip") {
            try {
                resetSelectors()
                mob.addTargetAi(testAi, 9)
                val goals = mob.getTargetAi().toList()
                check(goals.size == 1)
                mob.setTargetAi(goals)
                check(mob.getTargetAi().count() == 1)
                mob.removeTargetAi(9)
                check(mob.getTargetAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:replaceGoalAi(priority)") {
            try {
                resetSelectors()
                mob.addGoalAi(testAi, 7)
                val original = mob.getGoalAi().single()
                mob.replaceGoalAi(object : SimpleAi() {

                    override fun shouldExecute(): Boolean {
                        return false
                    }
                }, 7)
                val goals = mob.getGoalAi().toList()
                check(goals.size == 1)
                check(goals.single() !== original)
                mob.removeGoalAi(7)
                check(mob.getGoalAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:replaceTargetAi(priority)") {
            try {
                resetSelectors()
                mob.addTargetAi(testAi, 9)
                val original = mob.getTargetAi().single()
                mob.replaceTargetAi(object : SimpleAi() {

                    override fun shouldExecute(): Boolean {
                        return false
                    }
                }, 9)
                val goals = mob.getTargetAi().toList()
                check(goals.size == 1)
                check(goals.single() !== original)
                mob.removeTargetAi(9)
                check(mob.getTargetAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:replaceGoalAi(name)") {
            try {
                resetSelectors()
                mob.addGoalAi(testAi, 7)
                val original = mob.getGoalAi().single()
                mob.replaceGoalAi(object : SimpleAi() {

                    override fun shouldExecute(): Boolean {
                        return false
                    }
                }, 11, testAi.javaClass.name)
                val goals = mob.getGoalAi().toList()
                check(goals.size == 1)
                check(goals.single() !== original)
                mob.removeGoalAi(11)
                check(mob.getGoalAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:replaceTargetAi(name)") {
            try {
                resetSelectors()
                mob.addTargetAi(testAi, 9)
                val original = mob.getTargetAi().single()
                mob.replaceTargetAi(object : SimpleAi() {

                    override fun shouldExecute(): Boolean {
                        return false
                    }
                }, 13, testAi.javaClass.name)
                val goals = mob.getTargetAi().toList()
                check(goals.size == 1)
                check(goals.single() !== original)
                mob.removeTargetAi(13)
                check(mob.getTargetAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:removeGoalAi(name)") {
            try {
                resetSelectors()
                mob.addGoalAi(testAi, 7)
                mob.removeGoalAi(testAi.javaClass.name)
                check(mob.getGoalAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:removeTargetAi(name)") {
            try {
                resetSelectors()
                mob.addTargetAi(testAi, 9)
                mob.removeTargetAi(testAi.javaClass.name)
                check(mob.getTargetAi().none())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:navigationMove(Location)") {
            val origin = prepareNavigationLocation(world)
            val navigationEntity = world.spawnEntity(origin, EntityType.VILLAGER) as Villager
            val plugin = Bukkit.getPluginManager().getPlugin("TabooLibE2E") ?: error("E2E plugin is unavailable")
            // Paper 会拒绝为同 tick 内尚未完成落地的新实体创建路径，等待实体稳定后验证真实导航行为。
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                try {
                    if (MinecraftVersion.isLower(MinecraftVersion.V1_14)) {
                        pathfinderExecutor.getEntityInsentient(navigationEntity).setProperty("onGround", true, remap = false)
                    }
                    val target = origin.clone().add(2.0, 0.0, 0.0)
                    val moved = navigationEntity.navigationMove(target)
                    val reached = moved && navigationEntity.navigationReach()
                    if (moved && reached) {
                        Bukkit.getLogger().info("[E2E-PROBE] AI_NAVIGATION_LOCATION")
                    } else {
                        val below = navigationEntity.location.clone().add(0.0, -1.0, 0.0).block.type
                        Bukkit.getLogger().warning("[E2E] AI location navigation failed: moved=$moved, reached=$reached, onGround=${navigationEntity.isOnGround}, ticks=${navigationEntity.ticksLived}, y=${navigationEntity.location.y}, below=$below")
                    }
                } finally {
                    navigationEntity.remove()
                }
            }, 10L)
        }
        results += sandbox("AI:navigationMove(LivingEntity)") {
            val origin = prepareNavigationLocation(world)
            val navigationEntity = world.spawnEntity(origin, EntityType.VILLAGER) as Villager
            val target = world.spawnEntity(origin.clone().add(3.0, 0.0, 0.0), EntityType.VILLAGER) as Villager
            val plugin = Bukkit.getPluginManager().getPlugin("TabooLibE2E") ?: error("E2E plugin is unavailable")
            // 等待导航实体与目标实体进入可寻路状态，避免把服务端实体初始化差异当作 API 失败。
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                try {
                    if (MinecraftVersion.isLower(MinecraftVersion.V1_14)) {
                        pathfinderExecutor.getEntityInsentient(navigationEntity).setProperty("onGround", true, remap = false)
                    }
                    val moved = navigationEntity.navigationMove(target)
                    if (moved) {
                        Bukkit.getLogger().info("[E2E-PROBE] AI_NAVIGATION_ENTITY")
                    } else {
                        val below = navigationEntity.location.clone().add(0.0, -1.0, 0.0).block.type
                        Bukkit.getLogger().warning("[E2E] AI entity navigation failed: moved=false, onGround=${navigationEntity.isOnGround}, ticks=${navigationEntity.ticksLived}, y=${navigationEntity.location.y}, below=$below")
                    }
                } finally {
                    navigationEntity.remove()
                    target.remove()
                }
            }, 10L)
        }
        // results += sandbox("AI:navigationReach()") { villager.navigationReach() }
        results += sandbox("AI:controllerLookAt(Location)") {
            try {
                resetSelectors()
                val target = mob.location.clone().add(1.0, 2.0, 3.0)
                mob.controllerLookAt(target)
                val controller = pathfinderExecutor.getControllerLook(mob)
                check(controller.getProperty<Double>(controllerWantedX, remap = controllerLookRemap) == target.x)
                check(controller.getProperty<Double>(controllerWantedY, remap = controllerLookRemap) == target.y)
                check(controller.getProperty<Double>(controllerWantedZ, remap = controllerLookRemap) == target.z)
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:controllerLookAt(Entity)") {
            try {
                resetSelectors()
                val target = Bukkit.getOnlinePlayers().firstOrNull() ?: error("AI player target is unavailable")
                mob.controllerLookAt(target)
                val controller = pathfinderExecutor.getControllerLook(mob)
                check(controller.getProperty<Double>(controllerWantedX, remap = controllerLookRemap) == target.location.x)
                check(controller.getProperty<Double>(controllerWantedZ, remap = controllerLookRemap) == target.location.z)
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:controllerJumpReady()") {
            try {
                resetSelectors()
                mob.controllerJumpReady()
                check(mob.controllerJumpCurrent())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:controllerJumpCurrent()") {
            try {
                resetSelectors()
                mob.controllerJumpReady()
                check(mob.controllerJumpCurrent())
            } finally {
                resetSelectors()
            }
        }
        results += sandbox("AI:lifecycle") {
            val lifecycleEntity = world.spawnEntity(prepareNavigationLocation(world).add(0.0, 0.0, 1.0), EntityType.VILLAGER) as Villager
            lifecycleEntity.clearGoalAi()
            var didShouldExecute = false
            var didContinueExecute = false
            var didStartTask = false
            var didUpdateTask = false
            var didResetTask = false
            val lifecycleAi = object : SimpleAi() {

                override fun shouldExecute(): Boolean {
                    didShouldExecute = true
                    return true
                }

                override fun continueExecute(): Boolean {
                    didContinueExecute = true
                    return !didUpdateTask
                }

                override fun startTask() {
                    didStartTask = true
                }

                override fun updateTask() {
                    didUpdateTask = true
                }

                override fun resetTask() {
                    didResetTask = true
                }
            }
            lifecycleEntity.addGoalAi(lifecycleAi, 0)
            if (MinecraftVersion.isLower(MinecraftVersion.V1_14)) {
                // 旧 Paper 的实体激活优化不会稳定驱动测试实体，直接 tick 真实 GoalSelector 验证生命周期。
                val selector = pathfinderExecutor.getGoalSelector(lifecycleEntity)
                val tickMethod = if (MinecraftVersion.isEqual(MinecraftVersion.V1_12)) "a" else "doTick"
                repeat(10) { selector.invokeMethod<Void>(tickMethod, remap = false) }
            }
            // continueExecute 返回 false 后，由 NMS GoalSelector 真实触发 resetTask。
            val plugin = Bukkit.getPluginManager().getPlugin("TabooLibE2E") ?: error("E2E plugin is unavailable")
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                try {
                    if (didShouldExecute && didContinueExecute && didStartTask && didUpdateTask && didResetTask) {
                        Bukkit.getLogger().info("[E2E-PROBE] AI_LIFECYCLE")
                    } else {
                        Bukkit.getLogger().warning("[E2E] AI lifecycle incomplete: $didShouldExecute/$didContinueExecute/$didStartTask/$didUpdateTask/$didResetTask, ticks=${lifecycleEntity.ticksLived}, goals=${lifecycleEntity.getGoalAi().count()}")
                    }
                } finally {
                    lifecycleEntity.clearGoalAi()
                    lifecycleEntity.remove()
                }
            }, 40L)
        }
        return try {
            results
        } finally {
            resetSelectors()
            mob.remove()
        }
    }

    private fun prepareNavigationLocation(world: World): Location {
        val anchor = Bukkit.getOnlinePlayers().firstOrNull()?.location ?: world.spawnLocation
        val minHeight = if (MinecraftVersion.isUniversal) world.minHeight else 0
        val y = anchor.blockY.coerceIn(minHeight + 2, world.maxHeight - 2)
        val origin = Location(world, anchor.blockX + 4.5, y.toDouble(), anchor.blockZ + 0.5)
        for (x in origin.blockX - 4..origin.blockX + 4) {
            for (z in origin.blockZ - 2..origin.blockZ + 2) {
                world.getBlockAt(x, y - 1, z).type = Material.STONE
                world.getBlockAt(x, y, z).type = Material.AIR
                world.getBlockAt(x, y + 1, z).type = Material.AIR
            }
        }
        return origin
    }
}
