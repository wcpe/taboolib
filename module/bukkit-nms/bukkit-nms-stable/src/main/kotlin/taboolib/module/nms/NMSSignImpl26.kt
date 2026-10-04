package taboolib.module.nms

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket
import org.bukkit.block.Block
import org.bukkit.entity.Player

/**
 * 使用 26.1+ 非混淆名称打开牌子编辑器。
 * 数据包构造器的第二形参在 26.1 / 26.2 为「是否正面」布尔，26.3 起改为 SignTextSlot 枚举。
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
            // 26.1 / 26.2 的构造器第二形参为「是否正面」布尔。
            // 显式解析而非直接调用：形参类型一旦变化能在解析阶段被识别并交给兜底策略，而不是等到发包时才抛错。
            val constructor = ClientboundOpenSignEditorPacket::class.java.getDeclaredConstructor(BlockPos::class.java, java.lang.Boolean.TYPE)
            return@versionStrategy { blockPosition -> constructor.newInstance(blockPosition, true) }
        },
        // 兜底策略：26.2 等未实测版本及后续版本可能再次更换第二形参类型，
        // 这里按运行时真实存在的构造器签名构造，避免「能编译但选不到构造器」的静默失败。
        versionStrategy<(BlockPos) -> Any>("v26_other") {
            val constructor = ClientboundOpenSignEditorPacket::class.java.declaredConstructors.firstOrNull {
                it.parameterCount == 2 && it.parameterTypes[0] == BlockPos::class.java
            } ?: throw NoSuchMethodException("没有找到可用的 ${ClientboundOpenSignEditorPacket::class.java.name} 构造器")
            // 第二形参为布尔时表示「是否正面」，为枚举时取其 FRONT 常量
            val front = if (constructor.parameterTypes[1] == java.lang.Boolean.TYPE) {
                true
            } else {
                constructor.parameterTypes[1].getField("FRONT").get(null)
            }
            constructor.isAccessible = true
            return@versionStrategy { blockPosition ->
                constructor.newInstance(blockPosition, front)
            }
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
