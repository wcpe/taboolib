package taboolib.module.nms

import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import taboolib.common.util.unsafeLazy

/**
 * 将 Json 信息设置到 [BossBar] 的标题栏
 */
fun BossBar.setRawTitle(title: String) {
    NMSMessage.instance.setRawTitle(this, title)
}

/**
 * 发送 Json 信息到玩家的标题栏
 */
fun Player.sendRawTitle(title: String?, subtitle: String?, fadein: Int = 0, stay: Int = 20, fadeout: Int = 0) {
    NMSMessage.instance.sendRawTitle(this, title, subtitle, fadein, stay, fadeout)
}

/**
 * 发送 Json 信息到玩家的动作栏
 */
fun Player.sendRawActionBar(message: String) {
    NMSMessage.instance.sendRawActionBar(this, message)
}

/**
 * TabooLib
 * taboolib.module.nms.NMSMessage
 *
 * @author 坏黑
 * @since 2023/8/5 03:47
 */
abstract class NMSMessage {

    /**
     * 将 Json 信息转换为当前服务端的聊天组件。
     *
     * @param json Json 信息
     * @return 服务端聊天组件
     */
    abstract fun fromJson(json: String): Any

    /**
     * 将 Json 信息设置到 BossBar 的标题栏。
     *
     * @param bossBar BossBar
     * @param title Json 标题
     */
    abstract fun setRawTitle(bossBar: BossBar, title: String)

    /**
     * 发送 Json 信息到玩家的标题栏。
     *
     * @param player 目标玩家
     * @param title 主标题
     * @param subtitle 副标题
     * @param fadein 淡入时长
     * @param stay 停留时长
     * @param fadeout 淡出时长
     */
    abstract fun sendRawTitle(player: Player, title: String?, subtitle: String?, fadein: Int, stay: Int, fadeout: Int)

    /**
     * 发送 Json 信息到玩家的动作栏。
     *
     * @param player 目标玩家
     * @param action Json 信息
     */
    abstract fun sendRawActionBar(player: Player, action: String)

    companion object {

        /**
         * 当前服务端对应的消息实现。
         */
        val instance by unsafeLazy { nmsProxy<NMSMessage>(bind = if (MinecraftVersion.isUnobfuscated) "{name}Impl26" else "{name}Impl") }
    }
}
