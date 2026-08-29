@file:Inject

package taboolib.module.nms

import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.Inject
import taboolib.common.platform.Ghost
import taboolib.common.platform.event.EventPriority
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.util.unsafeLazy
import taboolib.module.nms.type.ChatColorFormat
import taboolib.module.nms.type.PlayerScoreboard
import taboolib.platform.util.PlayerSessionMap
import taboolib.platform.util.getMetaFirstOrNull
import taboolib.platform.util.removeMeta
import taboolib.platform.util.setMeta
import java.util.UUID

/**
 * 玩家记分板缓存
 */
private val playerScoreboardMap = PlayerSessionMap<PlayerScoreboard>()

/**
 * 发送记分板数据包
 * @param content 记分板内容（设置为空时注销记分板）
 */
fun Player.sendScoreboard(vararg content: String) {
    val scoreboard = playerScoreboardMap.getOrCreate(uniqueId) { PlayerScoreboard(this) } ?: return
    if (content.isEmpty()) {
        scoreboard.sendContent(emptyList())
    } else {
        scoreboard.sendTitle(content.firstOrNull().toString())
        scoreboard.sendContent(content.filterIndexed { index, _ -> index > 0 })
    }
}

/**
 * 发送记分板数据包
 * @param prefix 前缀,传入""时为清除前缀
 * @param player 发包给的玩家,传入Null时为给全体发送
 */
fun Player.setPrefix(prefix: String, player: Player?) {
    val scoreboard = playerScoreboardMap.getOrCreate(uniqueId) { PlayerScoreboard(this) } ?: return
    if (prefix.isNotEmpty()) {
        scoreboard.setPrefix(prefix, player)
    } else {
        scoreboard.clearPrefix(player)
    }
}

/**
 * 修改后缀
 * @param suffix 后缀,传入""时为清除后缀
 *  * @param player 发包给的玩家,传入Null时为给全体发送
 */
fun Player.setSuffix(suffix: String, player: Player?) {
    val scoreboard = playerScoreboardMap.getOrCreate(uniqueId) { PlayerScoreboard(this) } ?: return
    if (suffix.isNotEmpty()) {
        scoreboard.setSuffix(suffix, player)
    } else {
        scoreboard.clearSuffix(player)
    }
}

/**
 * 修改颜色
 * @param color 颜色
 * @param target 数据包接收单位, 传入 null 时为给全体发送
 */
fun Player.setTeamColor(color: ChatColorFormat, target: Player? = null) {
    playerScoreboardMap.getOrCreate(uniqueId) { PlayerScoreboard(this) }?.setColor(color, target)
}

/**
 * 进入游戏时移除记分板标记
 */
@Ghost
@SubscribeEvent(priority = EventPriority.LOWEST)
private fun onJoin(e: PlayerJoinEvent) {
    e.player.setMeta("t_scoreboard_objective_name", UUID.randomUUID().toString().substring(0..7))
    e.player.removeMeta("t_scoreboard_init")
}

/**
 * 离开游戏时释放记分板缓存
 */
@Ghost
@SubscribeEvent
private fun onQuit(e: PlayerQuitEvent) {
    // 移除记分板缓存
    playerScoreboardMap.remove(e.player.uniqueId)
}

/**
 * NMS 记分板操作接口
 */
abstract class NMSScoreboard {

    /**
     * 每行分数使用的唯一持有者。
     */
    val uniqueOwner = listOf(
        "§黒",
        "§黓",
        "§黔",
        "§黕",
        "§黖",
        "§黗",
        "§默",
        "§黙",
        "§黚",
        "§黛",
        "§黜",
        "§黝",
        "§點",
        "§黟",
        "§黠",
        "§黡",
        "§黢",
        "§黣",
        "§黤",
        "§黥",
        "§黦"
    )

    /**
     * 获取玩家当前使用的记分板目标名称。
     *
     * @param player 玩家
     * @return 记分板目标名称
     */
    fun getObjectiveName(player: Player): String {
        return player.getMetaFirstOrNull("t_scoreboard_objective_name")?.asString() ?: player.uniqueId.toString().substring(0..7)
    }

    /**
     * 初始化记分板
     * @param player 玩家
     * @param color 是否启用颜色
     * @param title 记分板标题
     */
    abstract fun setupScoreboard(player: Player, color: Boolean, title: String = "ScoreBoard")

    /** 设置记分板标题 */
    abstract fun setDisplayName(player: Player, title: String)

    /**
     * 修改记分板内容
     * @param content 记分板内容
     * @param lastContent 上一次的记分板内容（用于比对是否需要更新）
     */
    abstract fun changeContent(player: Player, content: List<String>, lastContent: Map<Int, String>): Boolean

    /** 显示记分板 */
    abstract fun display(player: Player)

    /**
     * 更新玩家队伍
     * @param player 需要设置前缀或后缀的玩家
     * @param prefix 前缀
     * @param suffix 后缀
     * @param color 颜色
     * @param createTeam 是否需要创建队伍
     * @param target 向该玩家发包, 如果为空则为全体发包
     */
    abstract fun updateTeam(
        player: Player,
        prefix: String,
        suffix: String,
        color: ChatColorFormat,
        createTeam: Boolean,
        target: Player?
    )

    companion object {

        /**
         * 当前服务端对应的记分板实现。
         */
        val instance by unsafeLazy {
            if (MinecraftVersion.isUnobfuscated) {
                nmsProxy<NMSScoreboard>("{name}Impl26")
            } else {
                nmsProxy<NMSScoreboard>()
            }
        }
    }
}
