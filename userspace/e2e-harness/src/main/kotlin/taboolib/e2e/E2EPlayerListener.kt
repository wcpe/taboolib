package taboolib.e2e

import org.bukkit.event.player.PlayerJoinEvent
import taboolib.common.Inject
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info

/**
 * 玩家加入监听器：当协议客户端进入服务器后，触发测试
 *
 * @author sky
 */
@Inject
object E2EPlayerListener {

    private var hasTriggered = false

    /**
     * 在真实协议玩家完成 Bukkit 登录后触发自动测试。
     *
     * @param event 玩家加入事件
     */
    @SubscribeEvent(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        info("[E2E] 玩家 ${player.name} 加入服务器")

        val autoRun = System.getProperty("taboolib.e2e.auto") == "true"
        if (autoRun && !hasTriggered) {
            hasTriggered = true
            info("[E2E] 自动运行已启用，等待 40 ticks（2 秒）使网络握手稳定后开始全量测试...")
            E2ERunner.runTestsAsync("join:${player.name}", delayTicks = 40L)
        }
    }
}
