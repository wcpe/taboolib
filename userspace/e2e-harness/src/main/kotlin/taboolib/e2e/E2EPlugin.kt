package taboolib.e2e

import taboolib.common.Inject
import taboolib.common.platform.Awake
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.warning

/**
 * E2E 测试 Harness 插件主入口（mc-testkit 消费方）。
 *
 * - smoke-*：ACTIVE 后执行全量 Test，不等待玩家
 * - full-* / taboolib-full：等待真实协议玩家（bot）入服后再执行
 * - serve：空闲不判定、不关服
 */
@Inject
@Awake
object E2EPlugin : Plugin() {

    /** full 场景等待 bot 入服的 tick 上限（默认 120s）。 */
    private const val PLAYER_WAIT_TIMEOUT_TICKS = 2400

    private val autoLegacy = System.getProperty("taboolib.e2e.auto") == "true"
    private val waitPlayerLegacy = System.getProperty("taboolib.e2e.wait-player") == "true"
    private var started = false

    override fun onLoad() {
        info("[E2E] TabooLib E2E Harness 加载完成 scenario=${E2EMcTestkitVerdict.scenarioId() ?: "(none)"}")
    }

    override fun onEnable() {
        info("[E2E] TabooLib E2E Harness 启动完成")
    }

    override fun onActive() {
        if (E2EMcTestkitVerdict.isServeScenario()) {
            info("[E2E] serve 场景，空闲待手测")
            return
        }
        // mc-testkit：smoke 无需 bot；legacy 属性兼容旧 auto 模式
        val runNow = !E2EMcTestkitVerdict.requiresPlayer() &&
            (E2EMcTestkitVerdict.resultFile() != null || (autoLegacy && !waitPlayerLegacy))
        if (runNow && !started) {
            started = true
            info("[E2E] smoke/auto 模式，准备执行测试...")
            E2ERunner.runTestsAsync("auto:ACTIVE")
            return
        }
        if (E2EMcTestkitVerdict.requiresPlayer() && E2EMcTestkitVerdict.resultFile() != null) {
            info("[E2E] full 场景等待 bot 入服，超时 ${PLAYER_WAIT_TIMEOUT_TICKS / 20}s")
            submit(delay = PLAYER_WAIT_TIMEOUT_TICKS.toLong()) {
                if (!started) {
                    started = true
                    warning("[E2E] 等待玩家超时，写 FAIL 并关服")
                    E2EMcTestkitVerdict.report(
                        ok = false,
                        message = "timeout waiting for bot join",
                    )
                    E2EMcTestkitVerdict.shutdownServer()
                }
            }
        }
    }

    override fun onDisable() {
        info("[E2E] TabooLib E2E Harness 已卸载")
    }

    /** 玩家（bot）入服后触发；供 [E2EPlayerListener] 调用。 */
    fun markPlayerReady(reason: String) {
        if (started) return
        if (E2EMcTestkitVerdict.isServeScenario()) return
        if (!E2EMcTestkitVerdict.requiresPlayer() && E2EMcTestkitVerdict.resultFile() == null && !autoLegacy) return
        started = true
        info("[E2E] 玩家就绪，延迟执行测试 ($reason)")
        E2ERunner.runTestsAsync(reason, delayTicks = 40L)
    }

    /** 测试结束后请求 bot 回报探针（聊天约定），短等后由 Runner 合并。 */
    fun requestBotProbes() {
        submit(delay = 20L) {
            try {
                warning("[E2E] E2E_PROBE_COLLECT 广播请求 bot 探针")
                org.bukkit.Bukkit.broadcastMessage("E2E_PROBE_COLLECT")
            } catch (ex: Throwable) {
                info("[E2E] 无法广播探针请求: ${ex.message}")
            }
        }
    }
}
