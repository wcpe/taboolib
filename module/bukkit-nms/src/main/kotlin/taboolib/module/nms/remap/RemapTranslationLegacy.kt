package taboolib.module.nms.remap

import org.objectweb.asm.signature.SignatureReader
import org.objectweb.asm.signature.SignatureWriter
import taboolib.module.nms.MinecraftVersion
import java.util.concurrent.ConcurrentHashMap

/**
 * 旧版本转译器
 *
 * TabooLib
 * taboolib.module.nms.remap.MinecraftRemapper
 *
 * @author sky
 * @since 2021/7/17 2:02 上午
 */
open class RemapTranslationLegacy(
    private val sourceName: String? = null,
    private val sourceParents: List<String> = emptyList(),
) : RemapTranslation() {

    /**
     * 缓存类的父类和接口
     */
    val parentsCacheMap = ConcurrentHashMap<String, List<String>>()

    /**
     * 在 1.17 版本下进行字段转换
     *
     * $owner.$name
     * net/minecraft/server/level/EntityPlayer.connection -> b
     */
    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
        if (MinecraftVersion.isUniversal) {
            // 当前运行时的 Owner 名称
            val runningOwner = translate(owner).replace('/', '.')
            // 追溯父类和接口
            val findPath = findMappingPath(runningOwner)
            // 这里肯定是非 Universal CraftBukkit 环境
            // 先尝试当作 Mojang Deobf 转为 Mojang obf，否则当作 Spigot Deobf 转为 Mojang obf
            // 这里有个好处是：Spigot 方法和字段的映射在 1.18+ 才提供，但是 Mojang Deobf 从 1.17 开始就提供了，可以直接使用
            var mojangName = MinecraftVersion.paperMapping.fields.find { it.translateName == name && it.path in findPath }?.mojangName
            if (mojangName == null) {
                mojangName = MinecraftVersion.spigotMapping.fields.find { it.translateName == name && it.path in findPath }?.mojangName
            }
            return mojangName ?: name
        }
        return name
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        // 1.17 起 Mojang 映射已包含方法名，Spigot 方法映射仍从 1.18 起作为后备来源。
        if (MinecraftVersion.isUniversal) {
            // 为什么这么做？
            // 以 send(Packet) 函数为例，除了 send 需要转译之外，Packet 也需要。
            val signatureWriter = object : SignatureWriter() {
                override fun visitClassType(name: String) {
                    super.visitClassType(translate(name))
                }
            }
            SignatureReader(descriptor).accept(signatureWriter)
            val desc = signatureWriter.toString()
            // 当前运行时的 Owner 名称
            val runningOwner = translate(owner).replace('/', '.')
            // 追溯父类和接口
            val findPath = findMappingPath(runningOwner)
            // 这里肯定是非 Universal CraftBukkit 环境
            // 先尝试当作 Mojang Deobf 转为 Mojang obf，否则当作 Spigot Deobf 转为 Mojang obf
            // 这里有个好处是：Spigot 方法和字段的映射在 1.18+ 才提供，但是 Mojang Deobf 从 1.17 开始就提供了，可以直接使用
            var mojangName = MinecraftVersion.paperMapping.methods.find {
                // 根据复杂程度依次对比
                it.translateName == name && it.path in findPath && runCatching { RemapHelper.checkParameterType(desc, it.descriptor) }.getOrDefault(false)
            }?.mojangName
            if (mojangName == null) {
                // 1.18
                mojangName = MinecraftVersion.spigotMapping.methods.find {
                    // 根据复杂程度依次对比
                    it.translateName == name && it.path in findPath && runCatching { RemapHelper.checkParameterType(desc, it.descriptor) }.getOrDefault(false)
                }?.mojangName
            }
            return mojangName ?: name
        }
        return name
    }

    /**
     * 获取成员映射的类层次，被转译类尚未定义时从原始字节码声明的父类开始追溯。
     */
    private fun findMappingPath(runningOwner: String): List<String> {
        return parentsCacheMap.getOrPut(runningOwner) {
            val roots = if (runningOwner == sourceName && sourceParents.isNotEmpty()) {
                sourceParents.map { translate(it).replace('/', '.') }
            } else {
                listOf(runningOwner)
            }
            // Paper 成员表以 Mojang owner 索引，运行时 Spigot owner 及其父类都要补入对应名称。
            roots.flatMap { findParents(it) }
                .flatMap { path -> listOfNotNull(path, MinecraftVersion.paperMapping.classMapSpigotToMojang[path]) }
                .distinct()
                .toMutableList()
                .apply { reverse() }
        }
    }
}
