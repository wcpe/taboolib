package taboolib.module.nms.test

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Villager
import taboolib.common.Test
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.getLanguageKey
import taboolib.module.nms.spawnEntity

/**
 * TabooLib
 * taboolib.test.nms_util.TestNMSEntity
 *
 * @author 坏黑
 * @since 2023/8/5 00:56
 */
object TestNMSEntity : Test() {

    override fun check(): List<Result> {
        val worlds = Bukkit.getWorlds()
        if (worlds.isEmpty()) {
            return listOf(Failure.of("AI:NO_WORLD"))
        }
        val world = worlds[0]
        val loc = Location(world, world.spawnLocation.x, world.spawnLocation.y, world.spawnLocation.z)
        return listOf(
            sandbox("NMSEntity:spawnEntity()") {
                var prepared = false
                val villager = loc.spawnEntity(Villager::class.java) {
                    prepared = true
                    it.customName = "E2E"
                }
                try {
                    check(prepared)
                    check(villager.customName == "E2E")
                    val languageKey = villager.getLanguageKey().path
                    if (MinecraftVersion.isLowerOrEqual(MinecraftVersion.V1_12)) {
                        // 旧版村民节点以后缀表达随机职业，具体职业由服务端生成时决定。
                        check(languageKey.startsWith("entity.Villager.")) { "Unexpected villager language key: $languageKey" }
                    } else if (MinecraftVersion.isEqual(MinecraftVersion.V1_13)) {
                        check(languageKey.startsWith("entity.minecraft.villager.")) { "Unexpected villager language key: $languageKey" }
                    } else {
                        val villagerLanguageKey = "entity.minecraft.villager.none"
                        check(languageKey == villagerLanguageKey) { "Unexpected villager language key: $languageKey" }
                    }
                } finally {
                    villager.remove()
                }
            },
        )
    }
}
