package taboolib.module.nms.test

import org.bukkit.Bukkit
import taboolib.common.BinaryCache
import taboolib.common.Test
import taboolib.common.platform.function.info
import taboolib.module.nms.Mapping
import taboolib.module.nms.MinecraftVersion
import taboolib.module.nms.NMSSign
import taboolib.module.nms.inputSign
import taboolib.module.nms.nmsProxy
import taboolib.platform.bukkit.Exchanges
import java.lang.reflect.Constructor

/**
 * TabooLib
 * taboolib.test.nms_util.TestNMSSign
 *
 * @author 坏黑
 * @since 2024/9/8 00:56
 */
object TestNMSSign : Test() {

    override fun check(): List<Result> {
        val player = Bukkit.getOnlinePlayers().firstOrNull()
        val results = arrayListOf<Result>()
        results += sandbox("NMSSign:historicalExchange") {
            val paperId = Exchanges.MAPPING_PAPER
            val keys = listOf(
                "$paperId#classMapSpigotS2F",
                "$paperId#classMapSpigotToMojang",
                "$paperId#classMapMojangS2F",
                "$paperId#classMapMojangToSpigot",
                "$paperId#fields",
                "$paperId#methods",
                paperId,
            )
            val original = keys.associateWith { key ->
                if (key in Exchanges) Exchanges.get<Any>(key) else null
            }
            val originalMapping = MinecraftVersion.paperMapping
            val delegateField = MinecraftVersion::class.java.getDeclaredField("paperMapping\$delegate")
            delegateField.isAccessible = true
            val delegate = delegateField.get(null)
            val valueField = delegate.javaClass.getDeclaredField("_value")
            valueField.isAccessible = true
            try {
                // 非空短名表可能包含其他插件提供的兼容别名，读取 Exchanges 时不得覆盖。
                Exchanges["$paperId#classMapSpigotS2F"] = hashMapOf("CompatibilityAlias" to "net.minecraft.compat.Target")
                val preservedMapping = Mapping.exchange(paperId)
                check(preservedMapping.classMapSpigotS2F["CompatibilityAlias"] == "net.minecraft.compat.Target")

                // 模拟旧版插件只留下完整 Spigot -> Mojang 映射、却留下空短类名索引的状态。
                Exchanges["$paperId#classMapSpigotS2F"] = HashMap<String, String>()
                Exchanges["$paperId#classMapMojangS2F"] = null

                // 服务端启动时已提前初始化映射，这里把恢复结果注入同一委托以重演新版首次读取后的运行状态。
                val mapping = Mapping.exchange(paperId)
                valueField.set(delegate, mapping)
                check(mapping.classMapSpigotToMojang["net.minecraft.core.BlockPosition"] == "net.minecraft.core.BlockPos")
                check(mapping.classMapSpigotS2F["BlockPosition"] == "net.minecraft.core.BlockPosition")

                val implementation = nmsProxy<NMSSign>()
                check(implementation.javaClass.simpleName == "NMSSignImpl")
                val constructor = implementation.javaClass.getMethod("getConstructorPacketOutSignEditor").invoke(implementation) as Constructor<*>
                check(constructor.parameterTypes.contentEquals(arrayOf(Class.forName("net.minecraft.core.BlockPos"), java.lang.Boolean.TYPE)))

                val remapDirectory = BinaryCache.getCacheFile().resolve("binary/remap")
                val generatedClasses = remapDirectory.listFiles()
                    ?.filter { it.name.startsWith("taboolib.module.nms.NMSSignImpl") }
                    ?: emptyList()
                check(generatedClasses.isNotEmpty())
                val staleReference = generatedClasses.firstOrNull { file ->
                    val bytecode = file.readBytes().toString(Charsets.ISO_8859_1)
                    bytecode.contains("net/minecraft/server/v1_12_R1/BlockPosition") ||
                        bytecode.contains("net/minecraft/server/v1_16_R1/BlockPosition")
                }
                check(staleReference == null) {
                    "生成的 NMSSignImpl 字节码仍包含旧 BlockPosition 引用: ${staleReference?.name}"
                }
                info("[E2E] NMSSign 历史 Exchanges 恢复与 BlockPosition 转译探针通过")
            } finally {
                original.forEach { (key, value) -> Exchanges[key] = value }
                valueField.set(delegate, originalMapping)
            }
        }
        if (player != null) {
            results += listOf(
                sandbox("NMSSign:implementation") {
                    val expected = if (MinecraftVersion.isUnobfuscated) "NMSSignImpl26" else "NMSSignImpl"
                    check(NMSSign.instance.javaClass.simpleName == expected)
                },
                sandbox("NMSSign:inputSign()") {
                    player.inputSign(arrayOf("E2E")) {
                        info("输入 ${it.contentToString()}")
                        if (it.firstOrNull() == "E2E") {
                            info("[E2E-PROBE] SIGN_CALLBACK")
                        }
                    }
                },
            )
        }
        return results
    }
}
