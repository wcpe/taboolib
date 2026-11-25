package taboolib.common.platform.service

import taboolib.common.Inject
import taboolib.common.platform.PlatformService
import taboolib.common.util.Location

/**
 * 由调用方指定任务的所有者，平台实现负责把任务送到对应线程。
 *
 * 目前只有 Bukkit（含 Folia）提供服务实例，通过
 * [taboolib.common.platform.function.schedulerService] 获取时在其它平台上为 null，
 * 跨平台代码需要判空并降级到 [taboolib.common.platform.function.executorService]。
 */
@Inject
@PlatformService
interface PlatformScheduler {

    /** 服务器范围的任务，不用于访问 Folia 的区域或实体状态。 */
    fun global(): Scope

    /** 不访问游戏世界或实体状态的任务。 */
    fun async(): Scope

    /** 位置所属区域的任务；普通 Bukkit 上使用主线程。目标位置在此处快照。 */
    fun region(location: Location): Scope

    /**
     * 在实体所属线程执行，跨区时跟随实体。
     *
     * 后端拒绝接收任务时（参数非法、插件已禁用等）抛出异常；实体在提交时即已失效，
     * 或任务已入队后才失效，都只通过 [onRetired] 通知，不抛出异常。
     *
     * [onRetired] 可能在任意线程被同步或异步调用，只做轻量清理：不访问世界或实体，
     * 不阻塞，也不要假定当时处于主线程。未提供 [onRetired] 时调用方无法感知实体失效。
     */
    fun entity(target: EntityTarget, onRetired: Runnable?): Scope

    /** 不关心实体失效通知的写法，等价于 [entity] 传入 `null`。 */
    fun entity(target: EntityTarget): Scope = entity(target, null)

    /** 由平台适配器包装的实体引用，不进行实体查找。 */
    interface EntityTarget

    fun interface Task {
        fun cancel()
    }

    class Scope(private val submit: (Long, Long, Runnable) -> Task) {

        /**
         * 提交任务，不在提交线程内联执行。参数单位为 tick；周期为 0 表示执行一次。
         * Folia 的异步调度按每 tick 50 毫秒换算，不随服务器 TPS 变化；
         * tick 过大导致换算溢出时抛出 [ArithmeticException]。
         * 周期任务在 Folia 上要求初始延时不小于 1 tick，delay 为 0 时按 1 tick（50 毫秒）起跳。
         */
        fun schedule(delayTicks: Long = 0, periodTicks: Long = 0, task: Runnable): Task {
            require(delayTicks >= 0) { "调度延时不能为负数" }
            require(periodTicks >= 0) { "调度周期不能为负数" }
            return submit(delayTicks, periodTicks, task)
        }
    }
}
