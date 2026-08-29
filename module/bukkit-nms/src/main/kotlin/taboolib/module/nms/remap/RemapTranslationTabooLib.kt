package taboolib.module.nms.remap

import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.remap.RemapHelper.checkParameterType

/**
 * TabooLib 内部类转译器
 *
 * TabooLib
 * taboolib.module.nms.remap.RemapTranslationTabooLib
 *
 * 只有 Paper 1.20.6+ 才会启用该类用于转译 TabooLib 类。
 * 与 RemapTranslation 不同的是，此实现不会进行父类检索。
 *
 * @author 坏黑
 * @since 2024/7/21 04:13
 */
class RemapTranslationTabooLib : RemapTranslation() {

    val descriptorCache = HashMap<String, String>()

    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
        val ownerName = resolveSpigotName(owner) ?: owner.replace('/', '.')
        // 从 Spigot Mapping 中检索
        for (spigotField in MinecraftVersion.spigotMapping.fields) {
            // 类名符合
            if (spigotField.path == ownerName) {
                // 获取用于在 Mojang Mapping 中检索的名字（已还原为 Mojang Obf）
                val obf = if (spigotField.translateName == name || spigotField.mojangName == name) {
                    spigotField.mojangName
                } else {
                    continue // 什么情况会这样？同类但不同字段
                }
                // 将类名转换为 Mojang Deobf
                val mojangName = translate(owner).replace('/', '.')
                // 从 Mojang Mapping 中检索
                for (mojangField in MinecraftVersion.paperMapping.fields) {
                    if (mojangField.mojangName == obf && mojangField.path == mojangName) {
                        // 最终返回 Mojang Deobf 名
                        return mojangField.translateName
                    }
                }
            }
        }
        return name
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        val ownerName = resolveSpigotName(owner) ?: owner.replace('/', '.')
        // 从 Spigot Mapping 中检索
        for (spigotMethod in MinecraftVersion.spigotMapping.methods) {
            // 类名符合
            if (spigotMethod.path == ownerName) {
                // 获取用于在 Mojang Mapping 中检索的名字（已还原为 Mojang Obf）
                val obf = if (spigotMethod.translateName == name || spigotMethod.mojangName == name) {
                    // 与字段不同的是，方法需要额外判断描述符
                    if (parametersMatch(descriptor, spigotMethod.descriptor)) spigotMethod.mojangName
                    else continue
                } else {
                    continue
                }
                // 将类名转换为 Mojang Deobf
                val mojangName = translate(owner).replace('/', '.')
                // 从 Mojang Mapping 中检索
                for (mojangMethod in MinecraftVersion.paperMapping.methods) {
                    if (mojangMethod.mojangName == obf && mojangMethod.path == mojangName && parametersMatch(descriptor, mojangMethod.descriptor)) {
                        // 最终返回 Mojang Deobf 名
                        return mojangMethod.translateName
                    }
                }
            }
        }
        return name
    }

    /**
     * 包名转换方法
     */
    override fun translate(key: String): String {
        // obc
        if (key.startsWith("org/bukkit/craftbukkit")) {
            return key.replace(obc1, obc3)
        }
        // 将低版本包名替换为高版本包名
        // net/minecraft/server/v1_17_R1/EntityPlayer -> net/minecraft/server/level/EntityPlayer
        val paperMapping = MinecraftVersion.paperMapping
        val exactName = paperMapping.classMapSpigotToMojang[key.replace('/', '.')]
        if (exactName != null) {
            return exactName.replace('.', '/')
        }
        if (!key.startsWith("net/minecraft/")) {
            return key
        }
        // Spigot 会在版本间移动类的包路径，按当前映射中的类名重新定位后再转为 Mojang 路径。
        // 先转为 Spigot.FullName
        var spigotName = resolveSpigotName(key) ?: return key
        // 在转为 Mojang.FullName
        spigotName = paperMapping.classMapSpigotToMojang[spigotName] ?: spigotName
        return spigotName.replace('.', '/')
    }

    /**
     * 将历史 Spigot 类路径解析为当前版本的 Spigot 完整类名。
     */
    private fun resolveSpigotName(key: String): String? {
        val className = key.replace('/', '.')
        val paperMapping = MinecraftVersion.paperMapping
        if (className in paperMapping.classMapSpigotToMojang) {
            return className
        }
        val spigotName = paperMapping.classMapMojangToSpigot[className]
        if (spigotName != null) {
            return spigotName
        }
        val simpleName = key.substringAfterLast('/')
        return paperMapping.classMapSpigotS2F[simpleName]
    }

    /**
     * 比较方法参数，无法加载的历史版本类型视为当前候选不匹配。
     */
    private fun parametersMatch(descriptor: String, mappingDescriptor: String): Boolean {
        return try {
            checkParameterType(descriptor, mappingDescriptor)
        } catch (_: Throwable) {
            false
        }
    }
}
