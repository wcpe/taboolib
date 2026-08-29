package taboolib.module.nms

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.chat.ComponentSerializer
import net.minecraft.server.v1_8_R3.IChatBaseComponent as NMSChatComponent8
import org.bukkit.boss.BossBar
import org.bukkit.craftbukkit.v1_21_R3.util.CraftChatMessage
import org.bukkit.entity.Player
import org.tabooproject.reflex.Reflex.Companion.getProperty
import org.tabooproject.reflex.Reflex.Companion.setProperty
import taboolib.common.UnsupportedVersionException

// region NMSMessageImpl
/**
 * Minecraft 1.8 至 1.21.11 的映射 NMS 消息实现
 *
 * @author sky
 */
class NMSMessageImpl : NMSMessage() {

    override fun fromJson(json: String): Any {
        // 1.8 至 1.20.4 使用 NMS 的 ChatSerializer，旧 CraftChatMessage 没有 fromJSON(String) 签名。
        if (MinecraftVersion.versionId >= 12005) {
            return CraftChatMessage.fromJSON(json)
        }
        if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_16)) {
            val component = NMSChatSerializer16.a(json) ?: error("无法解析聊天组件")
            return component
        } else {
            val component: NMSChatComponent8 = NMSChatComponent8.ChatSerializer.a(json)
            return component
        }
    }

    override fun setRawTitle(bossBar: BossBar, title: String) {
        // 1.20.5+
        if (MinecraftVersion.versionId >= 12005) {
            val craftBossBar = bossBar as org.bukkit.craftbukkit.v1_21_R3.boss.CraftBossBar
            craftBossBar.handle.setName(fromJson(title) as net.minecraft.network.chat.IChatBaseComponent)
        }
        // 1.16+
        // ChatSerializer.a 的返回值由 IChatBaseComponent 变为 IChatMutableComponent
        else if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_16)) {
            val craftBossBar = bossBar as org.bukkit.craftbukkit.v1_16_R3.boss.CraftBossBar
            craftBossBar.handle.a(net.minecraft.server.v1_16_R3.IChatBaseComponent.ChatSerializer.a(title))
        } else {
            val craftBossBar = bossBar as org.bukkit.craftbukkit.v1_12_R1.boss.CraftBossBar
            craftBossBar.getProperty<net.minecraft.server.v1_12_R1.BossBattleServer>("handle")!!.a(net.minecraft.server.v1_12_R1.IChatBaseComponent.ChatSerializer.a(title))
        }
    }

    override fun sendRawTitle(player: Player, title: String?, subtitle: String?, fadein: Int, stay: Int, fadeout: Int) {
        if (MinecraftVersion.isLower(MinecraftVersion.V1_9)) {
            throw UnsupportedVersionException()
        }
        if (MinecraftVersion.isUniversal) {
            // title
            // 时间
            player.sendPacket(net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(fadein, stay, fadeout))
            // 大标题
            if (title != null) {
                player.sendPacket(net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(fromJson(title) as net.minecraft.network.chat.IChatBaseComponent))
            }
            // 小标题
            if (subtitle != null) {
                player.sendPacket(net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(fromJson(subtitle) as net.minecraft.network.chat.IChatBaseComponent))
            }
        } else {
            // title
            // 时间
            player.sendPacket(net.minecraft.server.v1_16_R3.PacketPlayOutTitle(fadein, stay, fadeout))
            // 大标题
            if (title != null) {
                // 1.16+
                // ChatSerializer.a 的返回值由 IChatBaseComponent 变为 IChatMutableComponent
                if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_16)) {
                    player.sendPacket(net.minecraft.server.v1_16_R3.PacketPlayOutTitle(net.minecraft.server.v1_16_R3.PacketPlayOutTitle.EnumTitleAction.TITLE, net.minecraft.server.v1_16_R3.IChatBaseComponent.ChatSerializer.a(title)))
                } else {
                    player.sendPacket(net.minecraft.server.v1_8_R3.PacketPlayOutTitle(net.minecraft.server.v1_8_R3.PacketPlayOutTitle.EnumTitleAction.TITLE, net.minecraft.server.v1_8_R3.IChatBaseComponent.ChatSerializer.a(title)))
                }
            }
            // 小标题
            if (subtitle != null) {
                if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_16)) {
                    player.sendPacket(net.minecraft.server.v1_16_R3.PacketPlayOutTitle(net.minecraft.server.v1_16_R3.PacketPlayOutTitle.EnumTitleAction.SUBTITLE, net.minecraft.server.v1_16_R3.IChatBaseComponent.ChatSerializer.a(subtitle)))
                } else {
                    player.sendPacket(net.minecraft.server.v1_8_R3.PacketPlayOutTitle(net.minecraft.server.v1_8_R3.PacketPlayOutTitle.EnumTitleAction.SUBTITLE, net.minecraft.server.v1_8_R3.IChatBaseComponent.ChatSerializer.a(subtitle)))
                }
            }
        }
    }

    // action bar
    override fun sendRawActionBar(player: Player, action: String) {
        try {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *ComponentSerializer.parse(action))
        } catch (ex: NoSuchMethodError) {
            player.sendPacket(net.minecraft.server.v1_16_R3.PacketPlayOutChat().also {
                it.setProperty("b", 2.toByte())
                it.setProperty("components", ComponentSerializer.parse(action))
            })
        }
    }
}
// endregion

// region Typealias
typealias NMSChatSerializer16 = net.minecraft.server.v1_16_R3.IChatBaseComponent.ChatSerializer
// endregion
