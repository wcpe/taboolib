package taboolib.e2e

import taboolib.common.Inject
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.info
import org.bukkit.event.player.AsyncPlayerChatEvent

/**
 * 监听 bot 探针回报：约定聊天前缀 `E2E_PROBES:`。
 */
@Inject
object E2EProbeListener {

    @SubscribeEvent(priority = EventPriority.MONITOR)
    fun onChat(event: AsyncPlayerChatEvent) {
        val msg = event.message
        if (!msg.startsWith("E2E_PROBES:")) return
        val payload = msg.removePrefix("E2E_PROBES:")
        info("[E2E] 收到 ${event.player.name} 探针回报: $payload")
        E2ERunner.recordClientProbes(payload)
    }
}
