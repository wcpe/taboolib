package taboolib.module.nms.test

import org.bukkit.Bukkit
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import taboolib.common.Test
import taboolib.common.UnsupportedVersionException
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.NMSMessage
import taboolib.module.nms.sendRawActionBar
import taboolib.module.nms.sendRawTitle
import taboolib.module.nms.setRawTitle

/**
 * TabooLib
 * taboolib.test.nms_util.TestNMSMessage
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
object TestNMSMessage : Test() {

    override fun check(): List<Result> {
        val player = Bukkit.getOnlinePlayers().firstOrNull()
        return if (player != null) {
            listOf(
                sandbox("NMSMessage:implementation") {
                    val expected = if (MinecraftVersion.isUnobfuscated) "NMSMessageImpl26" else "NMSMessageImpl"
                    check(NMSMessage.instance.javaClass.simpleName == expected)
                    check(NMSMessage.instance.fromJson("{\"text\":\"E2E\"}").javaClass.name.startsWith("net.minecraft."))
                },
                sandbox("NMSMessage:setRawTitle()") {
                    try {
                        val bossBar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID)
                        bossBar.setRawTitle("{\"text\":\"E2E_BOSS\"}")
                        check(bossBar.title == "E2E_BOSS")
                    } catch (ex: NoClassDefFoundError) {
                        throw UnsupportedVersionException()
                    }
                },
                sandbox("NMSMessage:sendRawTitle()") { player.sendRawTitle("{\"text\":\"E2E_TITLE\"}", "{\"text\":\"E2E_SUBTITLE\"}") },
                sandbox("NMSMessage:sendRawActionBar()") { player.sendRawActionBar("{\"text\":\"E2E_ACTION\"}") },
            )
        } else {
            emptyList()
        }
    }
}
