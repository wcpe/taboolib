package taboolib.module.nms.test

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import taboolib.common.Test
import taboolib.module.nms.ItemTag
import taboolib.module.nms.ItemTagData
import taboolib.module.nms.ItemTagList
import taboolib.module.nms.ItemTagReader
import taboolib.module.nms.ItemTagType
import taboolib.module.nms.NMSItemTag
import taboolib.module.nms.getItemTag
import taboolib.module.nms.hasItemCanBreak
import taboolib.module.nms.hasItemCanPlaceOn
import taboolib.module.nms.removeItemCanBreak
import taboolib.module.nms.removeItemCanPlaceOn
import taboolib.module.nms.setItemCanBreak
import taboolib.module.nms.setItemCanPlaceOn
import taboolib.module.nms.setItemTag
import taboolib.module.nms.toMinecraftJson
import taboolib.platform.util.modifyMeta
import java.util.UUID

/**
 * TabooLib
 * taboolib.module.nms.test.TestNMS
 *
 * @author 坏黑
 * @since 2024/7/21 17:27
 */
object TestNMSTag : Test() {

    override fun check(): List<Result> {
        return listOf(
            sandbox("ItemTag:primitiveRoundTrip") {
                val tag = ItemTag()
                tag.put("byte", 1.toByte())
                tag.put("short", 2.toShort())
                tag.put("int", 3)
                tag.put("long", 4L)
                tag.put("float", 5.5f)
                tag.put("double", 6.5)
                tag.put("string", "round-trip")
                tag.put("bytes", byteArrayOf(7, 8))
                tag.put("ints", intArrayOf(9, 10))
                tag.put("longs", longArrayOf(11L, 12L))

                check(tag["byte"]?.type == ItemTagType.BYTE)
                check(tag["byte"]?.asByte() == 1.toByte())
                check(tag["short"]?.type == ItemTagType.SHORT)
                check(tag["short"]?.asShort() == 2.toShort())
                check(tag["int"]?.type == ItemTagType.INT)
                check(tag["int"]?.asInt() == 3)
                check(tag["long"]?.type == ItemTagType.LONG)
                check(tag["long"]?.asLong() == 4L)
                check(tag["float"]?.type == ItemTagType.FLOAT)
                check(tag["float"]?.asFloat() == 5.5f)
                check(tag["double"]?.type == ItemTagType.DOUBLE)
                check(tag["double"]?.asDouble() == 6.5)
                check(tag["string"]?.type == ItemTagType.STRING)
                check(tag["string"]?.asString() == "round-trip")
                check(tag["bytes"]?.type == ItemTagType.BYTE_ARRAY)
                check(tag["bytes"]?.asByteArray()?.contentEquals(byteArrayOf(7, 8)) == true)
                check(tag["ints"]?.type == ItemTagType.INT_ARRAY)
                check(tag["ints"]?.asIntArray()?.contentEquals(intArrayOf(9, 10)) == true)
                check(tag["longs"]?.type == ItemTagType.LONG_ARRAY)
                check(tag["longs"]?.asLongArray()?.contentEquals(longArrayOf(11L, 12L)) == true)
            },
            sandbox("ItemTag:compoundAndList") {
                val tag = ItemTag()
                val compound = ItemTag()
                compound["name"] = "nested"
                compound["level"] = 2
                val list = ItemTagList()
                list.add(ItemTagData("first"))
                list.add(ItemTagData(2))
                tag.put("compound", compound)
                tag.put("list", list)

                check(tag["compound"]?.type == ItemTagType.COMPOUND)
                check(tag["compound"]?.asCompound()?.get("name")?.asString() == "nested")
                check(tag["compound"]?.asCompound()?.get("level")?.asInt() == 2)
                check(tag["list"]?.type == ItemTagType.LIST)
                check(tag["list"]?.asList()?.size == 2)
                check(tag["list"]?.asList()?.get(0)?.asString() == "first")
                check(tag["list"]?.asList()?.get(1)?.asInt() == 2)
            },
            sandbox("ItemTag:deepOperations") {
                val tag = ItemTag()
                check(tag.putDeep("player.stats.kills", 10) == null)
                check(tag.putDeep("player.stats.deaths", 2) == null)
                check(tag.getDeep("player.stats.kills")?.asInt() == 10)
                check(tag.getDeep("player.stats.deaths")?.asInt() == 2)
                check(tag.putDeep("player.stats.kills", 11)?.asInt() == 10)
                check(tag.getDeep("player.stats.kills")?.asInt() == 11)
                check(tag.removeDeep("player.stats.kills")?.asInt() == 11)
                check(tag.getDeep("player.stats.kills") == null)
                check(tag.getDeep("player.stats.deaths")?.asInt() == 2)
                check(tag.removeDeep("player.stats.missing") == null)
            },
            sandbox("ItemTag:mapAndListMutation") {
                val tag = ItemTag()
                tag.put("map", mapOf("count" to 1, "name" to "before"))
                val map = tag["map"]!!.asCompound()
                map["count"] = 2
                map.remove("name")
                check(tag["map"]?.asCompound()?.get("count")?.asInt() == 2)
                check(tag["map"]?.asCompound()?.get("name") == null)

                tag.put("list", listOf("first", 2))
                val list = tag["list"]!!.asList()
                list[0] = ItemTagData("changed")
                list.add(ItemTagData("third"))
                list.removeAt(1)
                check(tag["list"]?.asList()?.size == 2)
                check(tag["list"]?.asList()?.get(0)?.asString() == "changed")
                check(tag["list"]?.asList()?.get(1)?.asString() == "third")
            },
            sandbox("ItemTag:cloneIndependence") {
                val original = ItemTag()
                original.putDeep("nested.value", 1)
                original.put("list", ItemTagList.of(ItemTagData(1), ItemTagData(2)))
                original.put("bytes", byteArrayOf(3, 4))

                val cloned = original.clone().asCompound()
                cloned.putDeep("nested.value", 2)
                cloned["list"]!!.asList()[0] = ItemTagData(9)
                cloned["bytes"]!!.asByteArray()[0] = 8

                check(original.getDeep("nested.value")?.asInt() == 1)
                check(cloned.getDeep("nested.value")?.asInt() == 2)
                check(original["list"]!!.asList()[0].asInt() == 1)
                check(cloned["list"]!!.asList()[0].asInt() == 9)
                check(original["bytes"]!!.asByteArray().contentEquals(byteArrayOf(3, 4)))
                check(cloned["bytes"]!!.asByteArray().contentEquals(byteArrayOf(8, 4)))
            },
            sandbox("ItemTag:jsonRoundTrip") {
                val source = ItemTag()
                source["text"] = "json-round-trip"
                source["number"] = 42
                source["bytes"] = byteArrayOf(1, 2, 3)
                source.putDeep("nested.value", 1.25)
                source["items"] = ItemTagList.of(ItemTagData("entry"), ItemTagData(7))

                val json = source.toJson()
                val restored = ItemTag.fromJson(json)
                // JSON 对象字段无序，因此按字段验证反序列化后的业务数据。
                check(restored.keys == source.keys)
                check(restored["text"]?.type == ItemTagType.STRING)
                check(restored["text"]?.asString() == "json-round-trip")
                check(restored["number"]?.type == ItemTagType.INT)
                check(restored["number"]?.asInt() == 42)
                check(restored["bytes"]?.type == ItemTagType.BYTE_ARRAY)
                check(restored["bytes"]?.asByteArray()?.contentEquals(byteArrayOf(1, 2, 3)) == true)
                check(restored.getDeep("nested.value")?.type == ItemTagType.DOUBLE)
                check(restored.getDeep("nested.value")?.asDouble() == 1.25)
                val items = restored["items"]!!.asList()
                check(items.size == 2)
                check(items[0].type == ItemTagType.STRING)
                check(items[0].asString() == "entry")
                check(items[1].type == ItemTagType.INT)
                check(items[1].asInt() == 7)
            },
            sandbox("ItemTagReader:typedAccessAndMutation") {
                val uuid = UUID.fromString("12345678-1234-5678-9abc-def012345678")
                val reader = ItemTagReader(ItemTag())
                reader["profile.name"] = "E2E"
                reader["profile.count"] = 3
                reader["profile.ratio"] = 1.5
                reader["profile.enabled"] = true
                reader["profile.uuid"] = uuid
                reader["profile.strings"] = listOf("a", "b")
                reader["profile.doubles"] = listOf(1.25, 2.5)
                reader.putAll(mapOf("long" to 4L, "float" to 5.5f, "byte" to 6.toByte()))

                check(reader.getString("profile.name") == "E2E")
                check(reader.getString("profile.missing", "fallback") == "fallback")
                check(reader.getInt("profile.count") == 3)
                check(reader.getDouble("profile.ratio") == 1.5)
                check(reader.getBoolean("profile.enabled"))
                check(reader.getUUID("profile.uuid") == uuid)
                check(reader.getStringList("profile.strings") == listOf("a", "b"))
                check(reader.getDoubleList("profile.doubles") == listOf(1.25, 2.5))
                check(reader.getLong("long") == 4L)
                check(reader.getFloat("float") == 5.5f)
                check(reader.getByte("byte") == 6.toByte())
                check(reader.getKeys("profile").containsAll(setOf("name", "count", "ratio", "enabled", "uuid", "strings", "doubles")))

                val restored = ItemTagReader(ItemTag())
                restored.loadFormJson(reader.toJson())
                check(restored.getString("profile.name") == "E2E")
                check(restored.formatJson().contains("profile"))
                restored.remove("profile.name")
                check(restored.getString("profile.name") == null)

                val target = item()
                restored.write(target)
                check(target.getItemTag().getDeep("profile.count")?.asInt() == 3)
                check(restored.close() === restored.itemTag)
            },
            sandbox("NMSItemTag:customRoundTrip") {
                val source = item()
                val tag = ItemTag()
                tag.put("e2e", "round-trip")
                val result = source.setItemTag(tag)
                check(result.getItemTag()["e2e"]?.asString() == "round-trip")
                check(source.getItemTag()["e2e"] == null)
            },
            sandbox("NMSItemTag:fullRoundTrip") {
                val source = item()
                val result = source.setItemTag(source.getItemTag(onlyCustom = false), onlyCustom = false)
                check(result.type == source.type)
                check(result.itemMeta?.displayName == source.itemMeta?.displayName)
                check(result.itemMeta?.lore == source.itemMeta?.lore)
            },
            sandbox("NMSItemTag:minecraftJsonRoundTrip") {
                val source = item()
                val result = NMSItemTag.instance.fromMinecraftJson(source.toMinecraftJson())
                check(result != null)
                check(result.type == source.type)
                check(result.itemMeta?.displayName == source.itemMeta?.displayName)
            },
            sandbox("NMSItemTag:canBreak") {
                val result = item().setItemCanBreak(listOf("minecraft:stone", "invalid key"))
                check(result.hasItemCanBreak())
                check(!result.removeItemCanBreak().hasItemCanBreak())
            },
            sandbox("NMSItemTag:canPlaceOn") {
                val result = item().setItemCanPlaceOn(listOf("minecraft:stone", "invalid key"))
                check(result.hasItemCanPlaceOn())
                check(!result.removeItemCanPlaceOn().hasItemCanPlaceOn())
            },
        )
    }

    /**
     * 创建带名称和 lore 的测试物品。
     *
     * @return 测试物品
     */
    fun item(): ItemStack {
        return ItemStack(Material.STONE).modifyMeta<ItemMeta> {
            setDisplayName("1")
            lore = listOf("2")
        }
    }
}
