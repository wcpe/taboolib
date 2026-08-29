package taboolib.module.nms

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.chat.ComponentSerializer
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import org.bukkit.boss.BossBar
import org.bukkit.craftbukkit.boss.CraftBossBar
import org.bukkit.craftbukkit.util.CraftChatMessage
import org.bukkit.entity.Player
import taboolib.module.nms.remap.DynamicOpcode
import taboolib.module.nms.remap.dynamic

/**
 * Minecraft 26.1 及以上非混淆服务端的 NMS 消息实现
 *
 * @author sky
 */
class NMSMessageImpl26 : NMSMessage() {

    override fun fromJson(json: String): Any {
        return CraftChatMessage.fromJSON(json)
    }

    override fun setRawTitle(bossBar: BossBar, title: String) {
        // 编译期依赖仍提供映射签名，使用 dynamic 绑定 26.1 的 Component 方法。
        dynamic(
            DynamicOpcode.INVOKEVIRTUAL,
            "net.minecraft.server.level.ServerBossEvent#setName(net.minecraft.network.chat.Component;)V",
            (bossBar as CraftBossBar).handle,
            fromJson(title),
        )
    }

    override fun sendRawTitle(player: Player, title: String?, subtitle: String?, fadein: Int, stay: Int, fadeout: Int) {
        // 编译期依赖仍提供映射签名，标题数据包构造必须在运行时绑定非混淆签名。
        // 时间
        player.sendPacket(ClientboundSetTitlesAnimationPacket(fadein, stay, fadeout))
        // 大标题
        if (title != null) {
            player.sendPacket(
                dynamic(
                    DynamicOpcode.INVOKESPECIAL,
                    "net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(net.minecraft.network.chat.Component;)V",
                    fromJson(title),
                ) as ClientboundSetTitleTextPacket
            )
        }
        // 小标题
        if (subtitle != null) {
            player.sendPacket(
                dynamic(
                    DynamicOpcode.INVOKESPECIAL,
                    "net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(net.minecraft.network.chat.Component;)V",
                    fromJson(subtitle),
                ) as ClientboundSetSubtitleTextPacket
            )
        }
    }

    override fun sendRawActionBar(player: Player, action: String) {
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *ComponentSerializer.parse(action))
    }
}
