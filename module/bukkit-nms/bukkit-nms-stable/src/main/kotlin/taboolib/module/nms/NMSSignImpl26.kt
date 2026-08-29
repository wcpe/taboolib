package taboolib.module.nms

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import org.bukkit.block.Block
import org.bukkit.entity.Player

/**
 * 使用 26.1+ 非混淆名称打开牌子编辑器。
 *
 * @author sky
 */
class NMSSignImpl26 : NMSSign() {

    private val openSignEditorPacket = versionAdaptor<(BlockPos) -> Any>(
        versionStrategy<(BlockPos) -> Any>("v26_3", guard = { MinecraftVersion.major == MinecraftVersion.V26_3 }) {
            // 26.1 编译依赖尚无 SignTextSlot，只在 26.3 运行时按精确签名构造并固定打开正面。
            val signTextSlot = nmsClass("world.level.block.entity.SignTextSlot")
            val front = signTextSlot.getField("FRONT").get(null)
            val constructor = ClientboundOpenSignEditorPacket::class.java.getDeclaredConstructor(BlockPos::class.java, signTextSlot)
            return@versionStrategy { blockPosition ->
                constructor.newInstance(blockPosition, front)
            }
        },
        versionStrategy<(BlockPos) -> Any>(
            name = "v26_1_2",
            guard = { MinecraftVersion.major == MinecraftVersion.V26_1 || MinecraftVersion.major == MinecraftVersion.V26_2 },
        ) {
            { blockPosition -> ClientboundOpenSignEditorPacket(blockPosition, true) }
        },
    )

    /**
     * 非混淆服务端不会走旧版组件反序列化路径。
     *
     * @param component 服务端网络组件
     * @return 不返回结果
     */
    override fun deserialize(component: Any): String {
        error("Sign component deserialization is unavailable on unobfuscated servers")
    }

    /**
     * 向非混淆服务端玩家打开牌子编辑器。
     *
     * @param player 目标玩家
     * @param block 牌子方块
     */
    override fun openSignEditor(player: Player, block: Block) {
        val blockPosition = BlockPos(block.x, block.y, block.z)
        player.sendPacket(openSignEditorPacket()(blockPosition))
    }
}
