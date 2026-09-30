package taboolib.e2e

import org.bukkit.event.player.PlayerJoinEvent
import taboolib.common.Inject
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info

/**
 * 玩家加入监听：bot 入服后触发 full 场景测试。
 */
@Inject
object E2EPlayerListener {

    @SubscribeEvent(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        info("[E2E] 玩家 ${player.name} 加入服务器")
        E2EPlugin.markPlayerReady("join:${player.name}")
    }
}
