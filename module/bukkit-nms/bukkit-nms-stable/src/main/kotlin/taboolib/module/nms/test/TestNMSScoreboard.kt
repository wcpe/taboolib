package taboolib.module.nms.test

import taboolib.common.Test
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.NMSScoreboard
import taboolib.module.nms.sendScoreboard
import taboolib.module.nms.setPrefix
import taboolib.module.nms.setSuffix
import taboolib.module.nms.setTeamColor
import taboolib.module.nms.type.ChatColorFormat
import taboolib.platform.util.onlinePlayers

/**
 * TabooLib
 * taboolib.test.nms_util.TestNMSScoreboard
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
object TestNMSScoreboard : Test() {

    override fun check(): List<Result> {
        val player = onlinePlayers.firstOrNull()
        return if (player != null) {
            listOf(
                sandbox("NMSScoreboard:implementation") {
                    val expected = if (MinecraftVersion.isUnobfuscated) "NMSScoreboardImpl26" else "NMSScoreboardImpl"
                    check(NMSScoreboard.instance.javaClass.simpleName == expected)
                },
                sandbox("NMSScoreboard:addLines") { player.sendScoreboard("TEST", "123", "456") },
                sandbox("NMSScoreboard:removeLine") { player.sendScoreboard("TEST", "123") },
                sandbox("NMSScoreboard:updateAndAddLines") { player.sendScoreboard("UPDATED", "abc", "def", "ghi") },
                sandbox("NMSScoreboard:teamPrefixSuffixColor") {
                    player.setPrefix("[E2E]", player)
                    player.setSuffix("!", player)
                    player.setTeamColor(ChatColorFormat.RED, player)
                },
                sandbox("NMSScoreboard:remove") { player.sendScoreboard() },
            )
        } else {
            emptyList()
        }
    }
}
