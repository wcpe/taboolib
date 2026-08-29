package taboolib.module.nms

import org.bukkit.inventory.meta.ItemMeta
import org.tabooproject.reflex.Reflex.Companion.invokeMethod
import org.tabooproject.reflex.Reflex.Companion.setProperty
import taboolib.common.UnsupportedVersionException
import taboolib.module.chat.Source

/**
 * 将 [Source] 写入物品的显示名称
 */
fun ItemMeta.setDisplayNameComponent(source: Source): ItemMeta {
    if (MinecraftVersion.isLower(MinecraftVersion.V1_17)) {
        throw UnsupportedVersionException()
    }
    try {
        // public void setDisplayNameComponent(BaseComponent[] component)
        invokeMethod<Any>("setDisplayNameComponent", arrayOf(source.toSpigotObject()))
    } catch (ex: NoSuchMethodException) {
        if (MinecraftVersion.versionId < 12005) {
            // Spigot 1.20.4 及更早版本以 JSON 字符串保存组件。
            setProperty("displayName", source.toRawMessage())
        } else {
            // private IChatBaseComponent displayName;
            setProperty("displayName", NMSMessage.instance.fromJson(source.toRawMessage()))
        }
    }
    return this
}

/**
 * 将 [Source] 写入物品的描述
 */
fun ItemMeta.setLoreComponents(source: List<Source>): ItemMeta {
    if (MinecraftVersion.isLower(MinecraftVersion.V1_17)) {
        throw UnsupportedVersionException()
    }
    try {
        // public void setLoreComponents(List<BaseComponent[]> lore)
        invokeMethod<Any>("setLoreComponents", source.map { arrayOf(it.toSpigotObject()) })
    } catch (_: NoSuchMethodException) {
        if (MinecraftVersion.versionId < 12005) {
            // Spigot 1.20.4 及更早版本以 JSON 字符串保存组件。
            setProperty("lore", source.map { it.toRawMessage() })
        } else {
            // private List<IChatBaseComponent> lore;
            setProperty("lore", source.map { NMSMessage.instance.fromJson(it.toRawMessage()) })
        }
    }
    return this
}
