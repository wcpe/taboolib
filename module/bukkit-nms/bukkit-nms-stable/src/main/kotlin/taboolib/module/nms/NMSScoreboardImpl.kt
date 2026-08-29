package taboolib.module.nms

import com.google.gson.JsonObject
import net.minecraft.EnumChatFormat
import net.minecraft.network.chat.IChatBaseComponent
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.PacketPlayOutScoreboardDisplayObjective
import net.minecraft.network.protocol.game.PacketPlayOutScoreboardObjective
import net.minecraft.network.protocol.game.PacketPlayOutScoreboardScore
import net.minecraft.network.protocol.game.PacketPlayOutScoreboardTeam
import net.minecraft.server.v1_12_R1.EnumChatFormat as LegacyChatFormat
import net.minecraft.server.v1_12_R1.ScoreboardScore
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.ScoreboardObjective
import net.minecraft.world.scores.ScoreboardTeam
import net.minecraft.world.scores.criteria.IScoreboardCriteria
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.tabooproject.reflex.Reflex.Companion.invokeConstructor
import org.tabooproject.reflex.Reflex.Companion.invokeMethod
import org.tabooproject.reflex.Reflex.Companion.setProperty
import taboolib.common.util.t
import taboolib.module.nms.type.ChatColorFormat
import taboolib.platform.util.hasMeta
import taboolib.platform.util.onlinePlayers
import taboolib.platform.util.removeMeta
import taboolib.platform.util.setMeta
import java.util.Optional

// region NMSScoreboardImpl
/**
 * Minecraft 1.8 至 1.21.11 的映射 NMS 记分板实现。
 *
 * @author sky
 */
@Suppress("unused", "DuplicatedCode")
class NMSScoreboardImpl : NMSScoreboard() {

    val version = MinecraftVersion.versionId

    override fun setupScoreboard(player: Player, color: Boolean, title: String) {
        val objectiveName = getObjectiveName(player)
        val score = if (MinecraftVersion.isUniversal) {
            if (version >= 12003) {
                ScoreboardObjective(
                    Scoreboard(),
                    objectiveName,
                    IScoreboardCriteria.AIR,
                    component(title) as IChatBaseComponent,
                    IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER,
                    true,
                    BlankFormat.INSTANCE
                )
            } else {
                // 1.20.1 及更早版本使用反射调用 5 参构造
                ScoreboardObjective::class.java.invokeConstructor(
                    Scoreboard(),
                    objectiveName,
                    IScoreboardCriteria.AIR,
                    component(title),
                    IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                )
            }
        } else {
            if (version >= 11300) {
                ScoreboardObjective::class.java.invokeConstructor(
                    net.minecraft.server.v1_16_R3.Scoreboard(),
                    objectiveName,
                    net.minecraft.server.v1_16_R3.IScoreboardCriteria.AIR,
                    component(title),
                    net.minecraft.server.v1_16_R3.IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                )
            } else {
                ScoreboardObjective::class.java.invokeConstructor(
                    net.minecraft.server.v1_12_R1.Scoreboard(),
                    objectiveName,
                    net.minecraft.server.v1_12_R1.IScoreboardCriteria.i
                ).apply { setProperty("e", title) }
            }
        }
        player.sendPacket(PacketPlayOutScoreboardObjective(score as ScoreboardObjective, 0))
        // 初始化颜色
        if (color) initTeam(player)
    }

    /**
     *     public static final int METHOD_ADD = 0;
     *     public static final int METHOD_REMOVE = 1;
     *     public static final int METHOD_CHANGE = 2;
     */
    override fun changeContent(player: Player, content: List<String>, lastContent: Map<Int, String>): Boolean {
        val objectiveName = getObjectiveName(player)
        if (content.isEmpty()) {
            val score = if (MinecraftVersion.isUniversal) {
                if (version >= 12003) {
                    ScoreboardObjective(
                        Scoreboard(),
                        objectiveName,
                        IScoreboardCriteria.AIR,
                        component("ScoreBoard") as IChatBaseComponent,
                        IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER,
                        true,
                        BlankFormat.INSTANCE
                    )
                } else {
                    // 1.20.1 及更早使用反射调用 5 参构造
                    ScoreboardObjective::class.java.invokeConstructor(
                        Scoreboard(),
                        objectiveName,
                        IScoreboardCriteria.AIR,
                        component("ScoreBoard") as IChatBaseComponent,
                        IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                    )
                }
            }
            // region Legacy Version
            else {
                if (version >= 11300) {
                    ScoreboardObjective::class.java.invokeConstructor(
                        net.minecraft.server.v1_16_R3.Scoreboard(),
                        objectiveName,
                        net.minecraft.server.v1_16_R3.IScoreboardCriteria.AIR,
                        component("ScoreBoard"),
                        net.minecraft.server.v1_16_R3.IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                    )
                } else {
                    ScoreboardObjective::class.java.invokeConstructor(
                        net.minecraft.server.v1_12_R1.Scoreboard(),
                        objectiveName,
                        net.minecraft.server.v1_12_R1.IScoreboardCriteria.i
                    ).apply { setProperty("e", "ScoreBoard") }
                }
            }
            // endregion
            player.sendPacket(PacketPlayOutScoreboardObjective(score as ScoreboardObjective, 1))
            return true
        }
        val update = content.size != lastContent.size
        if (update) {
            updateLineCount(player, content.size, lastContent.size)
        }
        content.forEachIndexed { line, ct ->
            if (update || ct != lastContent[line]) {
                sendTeamPrefixSuffix(player, uniqueOwner[content.size - line - 1], ct)
            }
        }
        return false
    }

    override fun display(player: Player) {
        val objectiveName = getObjectiveName(player)
        val packet = if (MinecraftVersion.isUniversal) {
            if (version >= 12003) {
                PacketPlayOutScoreboardDisplayObjective(
                    DisplaySlot.SIDEBAR, ScoreboardObjective(
                        Scoreboard(),
                        objectiveName,
                        IScoreboardCriteria.AIR,
                        IChatBaseComponent.empty(),
                        IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER,
                        true,
                        BlankFormat.INSTANCE
                    )
                )
            } else {
                PacketPlayOutScoreboardDisplayObjective::class.java.invokeConstructor(
                    if (version >= 12002) DisplaySlot.SIDEBAR else 1, ScoreboardObjective::class.java.invokeConstructor(
                        Scoreboard(),
                        objectiveName,
                        IScoreboardCriteria.AIR,
                        component(""),
                        IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                    )
                )
            }
        }
        // region Legacy Version
        else {
            if (version >= 11300) {
                PacketPlayOutScoreboardDisplayObjective::class.java.invokeConstructor(
                    1, net.minecraft.server.v1_16_R3.ScoreboardObjective(
                        net.minecraft.server.v1_16_R3.Scoreboard(),
                        objectiveName,
                        net.minecraft.server.v1_16_R3.IScoreboardCriteria.AIR,
                        net.minecraft.server.v1_16_R3.ChatComponentText(""),
                        net.minecraft.server.v1_16_R3.IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                    )
                )
            } else {
                PacketPlayOutScoreboardDisplayObjective::class.java.invokeConstructor(
                    1, ScoreboardObjective::class.java.invokeConstructor(
                        net.minecraft.server.v1_12_R1.Scoreboard(),
                        objectiveName,
                        net.minecraft.server.v1_12_R1.IScoreboardCriteria.i
                    )
                )
            }
        }
        // endregion
        player.sendPacket(packet)
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    override fun setDisplayName(player: Player, title: String) {
        val objectiveName = getObjectiveName(player)
        val score = if (MinecraftVersion.isUniversal) {
            if (version >= 12003) {
                ScoreboardObjective(
                    Scoreboard(),
                    objectiveName,
                    IScoreboardCriteria.AIR,
                    component(title) as IChatBaseComponent,
                    IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER,
                    true,
                    BlankFormat.INSTANCE
                )
            } else {
                // 1.20.1 及更早版本使用反射调用 5 参构造
                ScoreboardObjective::class.java.invokeConstructor(
                    Scoreboard(),
                    objectiveName,
                    IScoreboardCriteria.AIR,
                    component(title) as IChatBaseComponent,
                    IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                )
            }
        }
        // region Legacy Version
        else {
            if (version >= 11300) {
                ScoreboardObjective::class.java.invokeConstructor(
                    net.minecraft.server.v1_16_R3.Scoreboard(),
                    objectiveName,
                    net.minecraft.server.v1_16_R3.IScoreboardCriteria.AIR,
                    component(title),
                    net.minecraft.server.v1_16_R3.IScoreboardCriteria.EnumScoreboardHealthDisplay.INTEGER
                )
            } else {
                net.minecraft.server.v1_12_R1.ScoreboardObjective::class.java.invokeConstructor(
                    Scoreboard(), objectiveName, net.minecraft.server.v1_12_R1.IScoreboardCriteria.i
                ).also { it.setDisplayName(title) } as ScoreboardObjective
            }
        }
        // endregion
        player.sendPacket(PacketPlayOutScoreboardObjective(score as ScoreboardObjective, 2))
    }

    /**
     * player -> 需要设置前缀或后缀的玩家
     * target -> 向该玩家发包,如果为Null则为全体发包
     *
     *     private static final int METHOD_ADD = 0;
     *     private static final int METHOD_REMOVE = 1;
     *     private static final int METHOD_CHANGE = 2;
     *     private static final int METHOD_JOIN = 3;
     *     private static final int METHOD_LEAVE = 4;
     */
    override fun updateTeam(
        player: Player,
        prefix: String,
        suffix: String,
        color: ChatColorFormat,
        createTeam: Boolean,
        target: Player?
    ) {
        if (createTeam) {
            createTeam(player, target)
        }
        if (MinecraftVersion.isUniversal) {
            val team = ScoreboardTeam(Scoreboard(), player.displayName)
            // 队伍参数
            team.playerPrefix = component(prefix) as IChatBaseComponent
            team.playerSuffix = component(suffix) as IChatBaseComponent
            team.color = EnumChatFormat.valueOf(color.name)
            val packet = PacketPlayOutScoreboardTeam::class.java.invokeConstructor(
                player.displayName, 2, Optional.of(PacketPlayOutScoreboardTeam.b(team)), listOf<String>()
            )
            if (target == null) {
                Bukkit.getServer().onlinePlayers.forEach { it.sendPacket(packet) }
            } else {
                target.sendPacket(packet)
            }
            return
        }
        // region Legacy Version
        val team = net.minecraft.server.v1_12_R1.ScoreboardTeam(net.minecraft.server.v1_12_R1.Scoreboard(), player.displayName)
        if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_13)) {
            team.invokeMethod<Void>("setPrefix", component(prefix), remap = false)
            team.invokeMethod<Void>("setSuffix", component(suffix), remap = false)
        } else {
            team.prefix = prefix
            team.suffix = suffix
        }
        team.color = LegacyChatFormat.valueOf(color.name)
        val packet = net.minecraft.server.v1_12_R1.PacketPlayOutScoreboardTeam(team, 2)
        if (target == null) {
            onlinePlayers.forEach { pp -> pp.sendPacket(packet) }
        } else target.sendPacket(packet)
        // endregion
    }

    // 版本适配：JSON 文本组件的反序列化策略
    // 每个策略 lambda 先用空 JSON 测试可用性，成功后返回函数引用
    private val jsonComponentImpl = versionAdaptor<(String) -> Any>(
        {
            NMSMessage.instance.fromJson("{\"text\":\"\"}")
            val parser: (String) -> Any = { text -> NMSMessage.instance.fromJson(text) }
            parser
        },
        {
            org.bukkit.craftbukkit.v1_21_R3.util.CraftChatMessage.fromJSON("{\"text\":\"\"}")
            val parser: (String) -> Any = { text -> org.bukkit.craftbukkit.v1_21_R3.util.CraftChatMessage.fromJSON(text) }
            parser
        }
    )

    private fun component(text: String): Any {
        val json = if (text.startsWith("{") && text.endsWith("}")) {
            text
        } else {
            JsonObject().apply { addProperty("text", text) }.toString()
        }
        return jsonComponentImpl()(json)
    }

    /**
     * a -> Team Name
     * b -> Team Display Name
     * c -> Team Prefix
     * d -> Team Suffix
     * e -> Name Tag Visibility
     * f -> Color
     * g -> Players, Player Count
     * h -> Mode
     *
     *  If 0 then the team is created.
     *  If 1 then the team is removed.
     *  If 2 the team information is updated.
     *  If 3 then new players are added to the team.
     *  If 4 then players are removed from the team.
     *
     * i -> Friendly Fire
     *
     * @see EnumChatFormat
     * @see PacketPlayOutScoreboardTeam
     */
    private fun initTeam(player: Player) {
        if (player.hasMeta("t_scoreboard_init")) {
            return
        }
        uniqueOwner.forEach { color ->
            if (MinecraftVersion.isUniversal) {
                // 队伍参数
                val team = ScoreboardTeam(Scoreboard(), color)
                player.sendPacket(
                    PacketPlayOutScoreboardTeam::class.java.invokeConstructor(
                        color, 0, Optional.of(PacketPlayOutScoreboardTeam.b(team)), listOf(color)
                    )
                )
                return@forEach
            }
            // region Legacy Version
            val team = net.minecraft.server.v1_12_R1.ScoreboardTeam(net.minecraft.server.v1_12_R1.Scoreboard(), color)
            val packet = net.minecraft.server.v1_12_R1.PacketPlayOutScoreboardTeam(team, 0)
            packet.setProperty("h", listOf(color))
            // endregion
            player.sendPacket(packet)
        }
        player.setMeta("t_scoreboard_init", true)
    }

    private fun createTeam(player: Player, target: Player?) {
        if (MinecraftVersion.isUniversal) {
            // 队伍参数
            val packet = PacketPlayOutScoreboardTeam::class.java.invokeConstructor(
                player.displayName,
                0,
                Optional.of(PacketPlayOutScoreboardTeam.b(ScoreboardTeam(Scoreboard(), player.displayName))),
                listOf(player.name)
            )
            if (target == null) {
                Bukkit.getServer().onlinePlayers.forEach { it.sendPacket(packet) }
            } else {
                target.sendPacket(packet)
            }
            return
        }
        // region Legacy Version
        val team =
            net.minecraft.server.v1_12_R1.ScoreboardTeam(net.minecraft.server.v1_12_R1.Scoreboard(), player.displayName)
        team.setCanSeeFriendlyInvisibles(false)
        val packet = net.minecraft.server.v1_12_R1.PacketPlayOutScoreboardTeam(team, 0)
        packet.setProperty("h", listOf(player.displayName))
        if (target == null) {
            onlinePlayers.forEach { p -> p.sendPacket(packet) }
        } else {
            target.sendPacket(packet)
        }
        // endregion
    }

    private fun validateLineCount(line: Int): Int {
        if (uniqueOwner.size < line) error(
            """
                行数大于支持的最大行数。
                Lines size are larger than supported.
            """.t()
        )
        return line
    }

    /**
     * @param team 为\[content.size - line - 1\]
     */
    private fun sendTeamPrefixSuffix(player: Player, team: String, content: String) {
        // 1.17+
        if (MinecraftVersion.major >= 9) {
            val t = ScoreboardTeam(Scoreboard(), team)
            t.playerPrefix = component(content) as IChatBaseComponent
            player.sendPacket(
                PacketPlayOutScoreboardTeam::class.java.invokeConstructor(
                    team,
                    2,
                    Optional.of(PacketPlayOutScoreboardTeam.b(t)),
                    listOf(team)
                )
            )
            return
        }
        if (version >= 11300) {
            val t = net.minecraft.server.v1_16_R3.ScoreboardTeam(net.minecraft.server.v1_16_R3.Scoreboard(), team)
            t.prefix = component(content) as net.minecraft.server.v1_16_R3.IChatBaseComponent
            player.sendPacket(net.minecraft.server.v1_16_R3.PacketPlayOutScoreboardTeam(t, 2).apply {
                setProperty("h", listOf(team))
            })
            return
        }
        // region Legacy Version
        val t = net.minecraft.server.v1_12_R1.ScoreboardTeam(net.minecraft.server.v1_12_R1.Scoreboard(), team)
        var prefix = content
        var suffix = ""
        if (content.length > 16) {
            prefix = content.substring(0 until 16)
            if (prefix.endsWith("§")) {
                prefix = prefix.removeSuffix("§")
                suffix = "§" + content.substring(16 until content.length)
            } else {
                val color = ChatColor.getLastColors(prefix)
                suffix = color + content.substring(16 until content.length)
            }
            if (suffix.length > 16) {
                suffix = suffix.take(16)
            }
        }
        t.prefix = prefix
        t.suffix = suffix
        val packet = net.minecraft.server.v1_12_R1.PacketPlayOutScoreboardTeam(t, 2)
        packet.setProperty("h", listOf(team))
        player.sendPacket(packet)
        // endregion
    }

    private fun updateLineCount(player: Player, line: Int, lastLineCount: Int) {
        val objectiveName = getObjectiveName(player)
        // 行数变多了，新增行
        if (validateLineCount(line) > lastLineCount) {
            (lastLineCount until line).forEach { i ->
                // 1.20.5 后两个参数改为 Optional
                // String owner, String objectiveName, int score, Optional<IChatBaseComponent> display, Optional<NumberFormat> numberFormat
                if (MinecraftVersion.versionId >= 12005) {
                    player.sendPacket(
                        PacketPlayOutScoreboardScore::class.java.invokeConstructor(
                            uniqueOwner[i], objectiveName, i, Optional.empty<Any>(), Optional.empty<Any>()
                        )
                    )
                    return@forEach
                }
                // region Legacy Version
                // 1.20.4 改为 Record
                // String owner, String objectiveName, int score, @Nullable IChatBaseComponent display, @Nullable NumberFormat numberFormat
                if (MinecraftVersion.majorLegacy > 12002) {
                    player.sendPacket(
                        PacketPlayOutScoreboardScore::class.java.invokeConstructor(
                            uniqueOwner[i],
                            objectiveName,
                            i,
                            null,
                            null
                        )
                    )
                    return@forEach
                }
                // 1.13+ 直接实例化
                if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_13)) {
                    player.sendPacket(
                        net.minecraft.server.v1_16_R3.PacketPlayOutScoreboardScore(
                            net.minecraft.server.v1_16_R3.ScoreboardServer.Action.CHANGE,
                            objectiveName,
                            uniqueOwner[i],
                            i
                        )
                    )
                    return@forEach
                }
                // 1.12 反射处理
                val score = ScoreboardScore(
                    net.minecraft.server.v1_12_R1.Scoreboard(), net.minecraft.server.v1_12_R1.ScoreboardObjective(
                        net.minecraft.server.v1_12_R1.Scoreboard(),
                        objectiveName,
                        net.minecraft.server.v1_12_R1.IScoreboardCriteria.i
                    ), uniqueOwner[i]
                )
                score.score = i
                val packet = net.minecraft.server.v1_12_R1.PacketPlayOutScoreboardScore(score)
                player.sendPacket(packet)
                // endregion
            }
        }
        // 变少了，减少行
        else {
            (line until lastLineCount).forEach { i ->
                // 1.20.3+ 使用独立的分数移除数据包
                if (MinecraftVersion.versionId >= 12003) {
                    player.sendPacket(ClientboundResetScorePacket(uniqueOwner[i], objectiveName))
                    return@forEach
                }
                // region Legacy Version
                // 1.13+
                if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_13)) {
                    player.sendPacket(
                        net.minecraft.server.v1_16_R3.PacketPlayOutScoreboardScore(
                            net.minecraft.server.v1_16_R3.ScoreboardServer.Action.REMOVE,
                            uniqueOwner[i],
                            objectiveName,
                            i
                        )
                    )
                    return@forEach
                }
                player.sendPacket(net.minecraft.server.v1_12_R1.PacketPlayOutScoreboardScore(objectiveName))
                // endregion
            }
        }
    }

}
// endregion
