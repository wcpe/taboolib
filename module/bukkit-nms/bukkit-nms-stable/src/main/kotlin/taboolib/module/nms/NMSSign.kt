package taboolib.module.nms

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import taboolib.common.util.unsafeLazy

/**
 * 捕获玩家的牌子输入
 *
 * @param lines 牌子内容（不足 4 行补齐至 4 行）
 * @param callback 回调函数
 */
fun Player.inputSign(lines: Array<String> = arrayOf(), callback: (lines: Array<String>) -> Unit) {
    val location = location.clone()
    // 如果版本低于 1.20，则修改 y 到 0
    if (MinecraftVersion.major < 12) {
        location.y = 0.0
    } else {
        // 否则 y - 2
        location.y -= 2
    }
    // 发送虚拟牌子
    try {
        sendBlockChange(location, sign().createBlockData())
    } catch (t: NoSuchMethodError) {
        sendBlockChange(location, sign(), 0.toByte())
    }
    // 设置牌子内容
    try {
        sendSignChange(location, lines.formatSign(4))
    } catch (ex: Throwable) {
        sendSignChange(location, lines.formatSign(3))
    }
    // 注册回调函数
    NMSSignListener.callback[name] = {
        callback(it)
        // 回收牌子
        try {
            sendBlockChange(location, location.block.blockData)
        } catch (t: NoSuchMethodError) {
            sendBlockChange(location, location.block.type, location.block.data)
        }
    }
    NMSSign.instance.openSignEditor(this, location.block)
}

private fun sign(): Material {
    return try {
        Material.valueOf("OAK_WALL_SIGN")
    } catch (ex: Throwable) {
        Material.valueOf("WALL_SIGN")
    }
}

private fun Array<String>.formatSign(line: Int): Array<String> {
    val list = toMutableList()
    while (list.size < line) {
        list.add("")
    }
    while (list.size > line) {
        list.removeAt(list.size - 1)
    }
    return list.toTypedArray()
}

/**
 * TabooLib
 * taboolib.module.nms.NMSSign
 *
 * @author 坏黑
 * @since 2023/5/2 21:57
 */
abstract class NMSSign {

    /**
     * 将网络组件反序列化为文本。
     *
     * @param component 服务端网络组件
     * @return 组件对应的文本
     */
    abstract fun deserialize(component: Any): String

    /**
     * 向玩家打开牌子编辑器。
     *
     * @param player 目标玩家
     * @param block 牌子方块
     */
    abstract fun openSignEditor(player: Player, block: Block)

    companion object {

        /**
         * 当前服务端对应的牌子实现。
         */
        val instance by unsafeLazy {
            nmsProxy<NMSSign>(if (MinecraftVersion.isUnobfuscated) "{name}Impl26" else "{name}Impl")
        }
    }
}
