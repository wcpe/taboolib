package taboolib.module.ai

import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.ai.control.JumpControl
import net.minecraft.world.entity.ai.control.LookControl
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.entity.ai.goal.GoalSelector
import net.minecraft.world.entity.ai.goal.WrappedGoal
import net.minecraft.world.entity.ai.navigation.PathNavigation
import org.bukkit.Location
import org.bukkit.craftbukkit.entity.CraftEntity
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.remap.DynamicOpcode
import taboolib.module.nms.remap.dynamic
import taboolib.module.nms.versionAdaptor
import taboolib.module.nms.versionStrategy
import java.lang.reflect.Field

/**
 * 该类仅用作生成 ASM 代码，无任何意义
 *
 * @author sky
 * @since 2018-09-20 20:57
 */
class PathfinderExecutorImpl26 : PathfinderExecutor() {

    private val pathEntity: Field = PathNavigation::class.java.getDeclaredField("path").apply { isAccessible = true }
    private val targetSelectorField: Field = Mob::class.java.getDeclaredField("targetSelector").apply { isAccessible = true }
    private val controllerJumpCurrent: Field = JumpControl::class.java.getDeclaredField("jump").apply { isAccessible = true }
    private val goalSelectorAccessor = versionAdaptor<(Mob) -> GoalSelector>(
        versionStrategy<(Mob) -> GoalSelector>("26.3+", guard = { MinecraftVersion.isHigherOrEqual(MinecraftVersion.V26_3) }) {
            // 26.x 之间 getter 的编译期签名不一致，运行时优先使用 getter，旧实现再回退到可访问字段。
            val getter = Mob::class.java.getMethod("getGoalSelector")
            return@versionStrategy { mob -> getter.invoke(mob) as GoalSelector }
        },
        versionStrategy<(Mob) -> GoalSelector>("26.1-26.2") {
            val field = Mob::class.java.getDeclaredField("goalSelector").apply { isAccessible = true }
            return@versionStrategy { mob -> field.get(mob) as GoalSelector }
        },
    )

    override fun getEntityInsentient(entity: LivingEntity): Any {
        return (entity as CraftEntity).handle
    }

    override fun getNavigation(entity: LivingEntity): Any {
        return (getEntityInsentient(entity) as Mob).getNavigation()
    }

    override fun getControllerJump(entity: LivingEntity): Any {
        return (getEntityInsentient(entity) as Mob).getJumpControl()
    }

    override fun getControllerMove(entity: LivingEntity): Any {
        return (getEntityInsentient(entity) as Mob).getMoveControl()
    }

    override fun getControllerLook(entity: LivingEntity): Any {
        return (getEntityInsentient(entity) as Mob).getLookControl()
    }

    override fun getGoalSelector(entity: LivingEntity): Any {
        return goalSelector(getEntityInsentient(entity) as Mob)
    }

    override fun getTargetSelector(entity: LivingEntity): Any {
        return targetSelector(getEntityInsentient(entity) as Mob)
    }

    override fun getPathEntity(entity: LivingEntity): Any {
        return (getNavigation(entity) as PathNavigation).path!!
    }

    override fun setPathEntity(entity: LivingEntity, pathEntity: Any) {
        this.pathEntity.set(getNavigation(entity), pathEntity)
    }

    override fun addGoalAi(entity: LivingEntity, ai: SimpleAi, priority: Int) {
        goalSelector(getEntityInsentient(entity) as Mob).addGoal(priority, pathfinderCreator.createPathfinderGoal(ai) as Goal)
    }

    override fun addTargetAi(entity: LivingEntity, ai: SimpleAi, priority: Int) {
        targetSelector(getEntityInsentient(entity) as Mob).addGoal(priority, pathfinderCreator.createPathfinderGoal(ai) as Goal)
    }

    override fun replaceGoalAi(entity: LivingEntity, ai: SimpleAi, priority: Int) {
        replaceGoalAi(entity, ai, priority, null)
    }

    override fun replaceTargetAi(entity: LivingEntity, ai: SimpleAi, priority: Int) {
        replaceTargetAi(entity, ai, priority, null)
    }

    override fun replaceGoalAi(entity: LivingEntity, ai: SimpleAi, priority: Int, name: String?) {
        if (name == null) {
            removeGoal(priority, goalSelector(getEntityInsentient(entity) as Mob))
        } else {
            removeGoal(name, goalSelector(getEntityInsentient(entity) as Mob))
        }
        addGoalAi(entity, ai, priority)
    }

    override fun replaceTargetAi(entity: LivingEntity, ai: SimpleAi, priority: Int, name: String?) {
        if (name == null) {
            removeGoal(priority, targetSelector(getEntityInsentient(entity) as Mob))
        } else {
            removeGoal(name, targetSelector(getEntityInsentient(entity) as Mob))
        }
        addTargetAi(entity, ai, priority)
    }

    override fun removeGoalAi(entity: LivingEntity, priority: Int) {
        removeGoal(priority, goalSelector(getEntityInsentient(entity) as Mob))
    }

    override fun removeTargetAi(entity: LivingEntity, priority: Int) {
        removeGoal(priority, targetSelector(getEntityInsentient(entity) as Mob))
    }

    override fun removeGoalAi(entity: LivingEntity, name: String) {
        removeGoal(name, goalSelector(getEntityInsentient(entity) as Mob))
    }

    override fun removeTargetAi(entity: LivingEntity, name: String) {
        removeGoal(name, targetSelector(getEntityInsentient(entity) as Mob))
    }

    private fun removeGoal(name: String, selector: GoalSelector) {
        selector.availableGoals.toList().forEach { wrappedGoal ->
            val goal = wrappedGoal.goal
            val matchesSimpleAi = goal.javaClass.simpleName == "PathfinderCreatorImpl26" &&
                (goal as PathfinderCreator).simpleAi.javaClass.name.contains(name)
            if (goal.javaClass.name.contains(name) || matchesSimpleAi) {
                selector.removeGoal(goal)
            }
        }
    }

    private fun removeGoal(priority: Int, selector: GoalSelector) {
        selector.availableGoals.toList().forEach { wrappedGoal ->
            if (wrappedGoal.priority == priority) {
                selector.removeGoal(wrappedGoal.goal)
            }
        }
    }

    private fun goalSelector(mob: Mob): GoalSelector {
        return goalSelectorAccessor()(mob)
    }

    private fun targetSelector(mob: Mob): GoalSelector {
        return targetSelectorField.get(mob) as GoalSelector
    }

    private fun replaceGoals(selector: GoalSelector, ai: Iterable<*>?) {
        val goals = ai?.map {
            require(it is WrappedGoal) { "AI collection must contain WrappedGoal values" }
            it.priority to it.goal
        }.orEmpty()
        selector.removeAllGoals { true }
        goals.forEach { (priority, goal) -> selector.addGoal(priority, goal) }
    }

    override fun clearGoalAi(entity: LivingEntity) {
        goalSelector(getEntityInsentient(entity) as Mob).removeAllGoals { true }
    }

    override fun clearTargetAi(entity: LivingEntity) {
        targetSelector(getEntityInsentient(entity) as Mob).removeAllGoals { true }
    }

    override fun getGoalAi(entity: LivingEntity): Iterable<*>? {
        return goalSelector(getEntityInsentient(entity) as Mob).availableGoals
    }

    override fun getTargetAi(entity: LivingEntity): Iterable<*>? {
        return targetSelector(getEntityInsentient(entity) as Mob).availableGoals
    }

    override fun setGoalAi(entity: LivingEntity, ai: Iterable<*>?) {
        replaceGoals(goalSelector(getEntityInsentient(entity) as Mob), ai)
    }

    override fun setTargetAi(entity: LivingEntity, ai: Iterable<*>?) {
        replaceGoals(targetSelector(getEntityInsentient(entity) as Mob), ai)
    }

    override fun navigationMove(entity: LivingEntity, location: Location): Boolean {
        return navigationMove(entity, location, 0.6)
    }

    override fun navigationMove(entity: LivingEntity, location: Location, speed: Double): Boolean {
        return (getNavigation(entity) as PathNavigation).moveTo(location.x, location.y, location.z, speed)
    }

    override fun navigationMove(entity: LivingEntity, target: LivingEntity): Boolean {
        return navigationMove(entity, target, 0.6)
    }

    override fun navigationMove(entity: LivingEntity, target: LivingEntity, speed: Double): Boolean {
        return (getNavigation(entity) as PathNavigation).moveTo((target as CraftEntity).handle, speed)
    }

    override fun navigationReach(entity: LivingEntity): Boolean {
        return dynamic(
            DynamicOpcode.INVOKEVIRTUAL,
            "net.minecraft.world.level.pathfinder.Path#canReach()Z",
            getPathEntity(entity)
        ) as Boolean
    }

    override fun controllerLookAt(entity: LivingEntity, target: Location) {
        (getControllerLook(entity) as LookControl).setLookAt(target.x, target.y, target.z, 10f, 40f)
    }

    override fun controllerLookAt(entity: LivingEntity, target: Entity) {
        (getControllerLook(entity) as LookControl).setLookAt((target as CraftEntity).handle, 10f, 40f)
    }

    override fun controllerJumpReady(entity: LivingEntity) {
        controllerJumpCurrent.setBoolean(getControllerJump(entity), true)
    }

    override fun controllerJumpCurrent(entity: LivingEntity): Boolean {
        return controllerJumpCurrent.getBoolean(getControllerJump(entity))
    }

    override fun setFollowRange(entity: LivingEntity, value: Double) {
        (getEntityInsentient(entity) as Mob).getAttribute(Attributes.FOLLOW_RANGE)!!.baseValue = value
    }
}
