package taboolib.module.nms

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.PlayerTeam
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import org.bukkit.Bukkit
import org.bukkit.craftbukkit.util.CraftChatMessage
import org.bukkit.entity.Player
import org.tabooproject.reflex.Reflex.Companion.invokeMethod
import taboolib.common.util.t
import taboolib.module.nms.type.ChatColorFormat
import taboolib.platform.util.hasMeta
import taboolib.platform.util.setMeta
import java.util.Optional

/**
 * Minecraft 26.x 非混淆环境的记分板数据包实现。
 * mapped 实现使用的 Spigot 类名不会进入该类的链接范围。
 *
 * @author sky
 */
class NMSScoreboardImpl26 : NMSScoreboard() {

    private val colorSetter = versionAdaptor<(PlayerTeam, ChatColorFormat) -> Unit>(
        versionStrategy<(PlayerTeam, ChatColorFormat) -> Unit>("26.2+", guard = { MinecraftVersion.isHigherOrEqual(MinecraftVersion.V26_2) }) {
            val teamColorClass = nmsClass("world.scores.TeamColor")
            return@versionStrategy { team: PlayerTeam, color: ChatColorFormat ->
                val teamColor = teamColorClass.enumConstants.firstOrNull { (it as Enum<*>).name == color.name }
                team.invokeMethod<Void>("setColor", Optional.ofNullable(teamColor))
                Unit
            }
        },
        versionStrategy<(PlayerTeam, ChatColorFormat) -> Unit>("26.1") {
            { team: PlayerTeam, color: ChatColorFormat -> team.color = ChatFormatting.valueOf(color.name) }
        },
    )

    override fun setupScoreboard(player: Player, color: Boolean, title: String) {
        player.sendPacket(ClientboundSetObjectivePacket(createObjective(player, title), 0))
        if (color) {
            initTeam(player)
        }
    }

    override fun setDisplayName(player: Player, title: String) {
        player.sendPacket(ClientboundSetObjectivePacket(createObjective(player, title), 2))
    }

    override fun changeContent(player: Player, content: List<String>, lastContent: Map<Int, String>): Boolean {
        val objectiveName = getObjectiveName(player)
        if (content.isEmpty()) {
            player.sendPacket(ClientboundSetObjectivePacket(createObjective(player, "ScoreBoard"), 1))
            return true
        }
        val update = content.size != lastContent.size
        if (update) {
            updateLineCount(player, content.size, lastContent.size)
        }
        content.forEachIndexed { line, value ->
            if (update || value != lastContent[line]) {
                sendTeamPrefix(player, uniqueOwner[content.size - line - 1], value)
            }
        }
        return false
    }

    override fun display(player: Player) {
        player.sendPacket(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, createObjective(player, "")))
    }

    override fun updateTeam(
        player: Player,
        prefix: String,
        suffix: String,
        color: ChatColorFormat,
        createTeam: Boolean,
        target: Player?
    ) {
        val team = PlayerTeam(Scoreboard(), player.name)
        team.playerPrefix = component(prefix)
        team.playerSuffix = component(suffix)
        team.players += player.name
        colorSetter()(team, color)
        val packet = ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, createTeam)
        if (target == null) {
            Bukkit.getOnlinePlayers().forEach { it.sendPacket(packet) }
        } else {
            target.sendPacket(packet)
        }
    }

    private fun createObjective(player: Player, title: String): Objective {
        return Objective(
            Scoreboard(),
            getObjectiveName(player),
            ObjectiveCriteria.AIR,
            component(title),
            ObjectiveCriteria.RenderType.INTEGER,
            true,
            BlankFormat.INSTANCE
        )
    }

    private fun component(text: String): Component {
        return if (text.startsWith("{") && text.endsWith("}")) {
            CraftChatMessage.fromJSON(text)
        } else {
            Component.literal(text)
        }
    }

    private fun initTeam(player: Player) {
        if (player.hasMeta("t_scoreboard_init")) {
            return
        }
        uniqueOwner.forEach { owner ->
            val team = PlayerTeam(Scoreboard(), owner)
            team.players += owner
            player.sendPacket(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true))
        }
        player.setMeta("t_scoreboard_init", true)
    }

    private fun sendTeamPrefix(player: Player, owner: String, content: String) {
        val team = PlayerTeam(Scoreboard(), owner)
        team.playerPrefix = component(content)
        player.sendPacket(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false))
    }

    private fun updateLineCount(player: Player, lineCount: Int, lastLineCount: Int) {
        validateLineCount(lineCount)
        val objectiveName = getObjectiveName(player)
        if (lineCount > lastLineCount) {
            (lastLineCount until lineCount).forEach { index ->
                player.sendPacket(
                    ClientboundSetScorePacket(
                        uniqueOwner[index],
                        objectiveName,
                        index,
                        Optional.empty(),
                        Optional.empty()
                    )
                )
            }
        } else {
            (lineCount until lastLineCount).forEach { index ->
                player.sendPacket(ClientboundResetScorePacket(uniqueOwner[index], objectiveName))
            }
        }
    }

    private fun validateLineCount(lineCount: Int) {
        if (lineCount > uniqueOwner.size) {
            error(
                """
                    行数大于支持的最大行数。
                    Lines size are larger than supported.
                """.t()
            )
        }
    }
}
