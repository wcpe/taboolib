package taboolib.module.nms.test

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import taboolib.common.Test
import taboolib.module.nms.NMSItemTag
import taboolib.platform.util.toNMSKeyAndItemData

/**
 * TabooLib
 * taboolib.test.nms_util.TestNMSSign
 *
 * @author 坏黑
 * @since 2024/9/8 00:56
 */
object TestTellrawJson : Test() {

    override fun check(): List<Result> {
        return listOf(sandbox("TellrawJson:toNMSKeyAndItemData()") {
            val (key, data) = ItemStack(Material.STONE).toNMSKeyAndItemData()
            check(key == "minecraft:stone")
            check(NMSItemTag.instance.fromMinecraftJson(data)?.type == Material.STONE)
        })
    }
}
