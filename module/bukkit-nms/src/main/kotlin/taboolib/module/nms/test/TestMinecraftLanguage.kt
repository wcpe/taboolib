package taboolib.module.nms.test

import org.bukkit.Bukkit
import taboolib.common.Test
import taboolib.module.nms.MinecraftLanguage
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.getMinecraftLanguageFile

/**
 * TabooLib
 * taboolib.module.nms.test.TestLocaleI18n
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
object TestMinecraftLanguage : Test() {

    override fun check(): List<Result> {
        return listOf(
            sandbox("NMS:MinecraftLanguage") {
                val support = MinecraftLanguage.supportedLanguage
                val size = MinecraftLanguage.files.size
                if (size != support.size) error("$size (lose: ${MinecraftLanguage.supportedLanguage.filter { MinecraftLanguage.files[it] == null }})")
                val missing = support.filter { MinecraftLanguage.getLanguageFile(it) == null }
                check(missing.isEmpty()) { "Missing language files: $missing" }
                val defaultLanguage = MinecraftLanguage.getDefaultLanguageFile() ?: error("Default language is unavailable")
                // 1.12 及更早版本使用旧的 properties 键名，1.13 起才改为 block.minecraft 命名空间。
                val stoneKey = if (MinecraftVersion.isHigher(MinecraftVersion.V1_12)) "block.minecraft.stone" else "tile.stone.stone.name"
                check(!defaultLanguage[stoneKey].isNullOrBlank()) { "Missing default language key: $stoneKey" }
                val player = Bukkit.getOnlinePlayers().firstOrNull()
                if (player != null) {
                    check(player.getMinecraftLanguageFile() != null) { "Player language file is unavailable" }
                }
            }
        )
    }
}
