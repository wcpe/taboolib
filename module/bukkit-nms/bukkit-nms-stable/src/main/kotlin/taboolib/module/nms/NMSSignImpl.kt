package taboolib.module.nms

import org.bukkit.block.Block
import org.bukkit.entity.Player
import taboolib.common.util.unsafeLazy
import java.lang.reflect.Constructor

// region NMSSignImpl
/**
 * 使用服务端映射名称打开牌子编辑器，适用于 26.1 之前的混淆服务端。
 *
 * @author sky
 */
class NMSSignImpl : NMSSign() {

    /**
     * 打开牌子编辑器数据包的映射构造器。
     */
    val constructorPacketOutSignEditor: Constructor<*> by unsafeLazy {
        net.minecraft.server.v1_16_R1.PacketPlayOutOpenSignEditor::class.java.getDeclaredConstructor(
            net.minecraft.server.v1_16_R1.BlockPosition::class.java,
            java.lang.Boolean.TYPE
        )
    }

    /**
     * 反序列化映射服务端的牌子组件。
     *
     * @param component 服务端网络组件
     * @return 组件对应的文本
     */
    override fun deserialize(component: Any): String {
        return net.minecraft.server.v1_12_R1.IChatBaseComponent.ChatSerializer.a(component as net.minecraft.server.v1_12_R1.IChatBaseComponent)
    }

    /**
     * 向映射服务端玩家打开牌子编辑器。
     *
     * @param player 目标玩家
     * @param block 牌子方块
     */
    override fun openSignEditor(player: Player, block: Block) {
        val blockPosition = net.minecraft.server.v1_12_R1.BlockPosition(block.x, block.y, block.z)
        // 1.20 -> 正反牌子
        if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_20)) {
            player.sendPacket(constructorPacketOutSignEditor.newInstance(blockPosition, true))
        } else {
            player.sendPacket(net.minecraft.server.v1_12_R1.PacketPlayOutOpenSignEditor(blockPosition))
        }
    }
}
// endregion
