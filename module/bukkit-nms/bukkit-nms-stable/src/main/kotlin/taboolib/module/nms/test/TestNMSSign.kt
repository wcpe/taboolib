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
                // 26.1 起 Minecraft 不再混淆，Spigot → Mojang 的映射机制不适用，跳过恢复结果断言。
                if (!MinecraftVersion.isUnobfuscated) {
                    check(mapping.classMapSpigotToMojang["net.minecraft.core.BlockPosition"] == "net.minecraft.core.BlockPos")
                    check(mapping.classMapSpigotS2F["BlockPosition"] == "net.minecraft.core.BlockPosition")
                }

                val implementation = nmsProxy<NMSSign>()
                check(implementation.javaClass.simpleName == "NMSSignImpl")
                // 构造器签名按版本而变：1.20 起带「是否正面」布尔形参，更早版本只有坐标单参
                // （与 NMSSignImpl.openSignEditor 的分流同源）。此处必须跟着分流，否则 1.20 以下必然抛 NoSuchMethodException。
                if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_20)) {
                    // 1.20 起才有带「是否正面」布尔形参的构造器。更早版本只有坐标单参，且类名随版本而变
                    // （1.17+ 位于 network.protocol.game，更早为 net.minecraft.server.v<版本>），
                    // 故旧区间不做构造器断言——该区间的转译正确性由下方 remap 产物断言覆盖。
                    val constructor = implementation.javaClass.getMethod("getConstructorPacketOutSignEditor").invoke(implementation) as Constructor<*>
                    // 经实现类 ClassLoader 解析 NMS 类型，避免 IsolatedClassLoader 下 Class.forName 找不到；
                    // 1.20 - 1.20.4 的服务端仍是 Spigot 映射，类名为 BlockPosition
                    val blockPosClass = runCatching {
                        Class.forName("net.minecraft.core.BlockPos", false, implementation.javaClass.classLoader)
                    }.getOrElse {
                        Class.forName("net.minecraft.core.BlockPosition", false, implementation.javaClass.classLoader)
                    }
                    check(constructor.parameterTypes.contentEquals(arrayOf(blockPosClass, java.lang.Boolean.TYPE)))
                }

                val remapDirectory = BinaryCache.getCacheFile().resolve("binary/remap")
                val generatedClasses = remapDirectory.listFiles()
                    ?.filter { file -> file.name.startsWith("taboolib.module.nms.NMSSignImpl") }
                    ?: emptyList()
                check(generatedClasses.isNotEmpty())
                // 运行期方块坐标类的内部名：1.17 起为 net/minecraft/core/BlockPosition，更早是版本化包
                // net/minecraft/server/v<CraftBukkit 版本>/BlockPosition——后者本身就是正确名字，
                // 不能一律把版本化引用判为「陈旧」（1.12.2 / 1.16.5 等旧版本会误报）。
                val craftBukkitVersion = Bukkit.getServer().javaClass.name
                    .substringAfter("craftbukkit.", "")
                    .substringBefore('.')
                val runtimeBlockPosition = "net/minecraft/server/$craftBukkitVersion/BlockPosition"
                val legacyAliases = listOf(
                    "net/minecraft/server/v1_12_R1/BlockPosition",
                    "net/minecraft/server/v1_16_R1/BlockPosition",
                )
                val staleReference = generatedClasses
                    // 1.20 起才会访问 constructorPacketOutSignEditor，其 lazy 内部类在更早版本是死代码——
                    // remap 不为「当前版本不存在的类」生成映射，故旧版本只检查主类，避免误报。
                    .filter { file ->
                        MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_20) || !file.name.contains('$')
                    }
                    .firstOrNull { file ->
                        val bytecode = file.readBytes().toString(Charsets.ISO_8859_1)
                        legacyAliases.any { alias -> alias != runtimeBlockPosition && bytecode.contains(alias) }
                    }
                check(staleReference == null) {
                    "生成的 NMSSignImpl 字节码仍包含旧 BlockPosition 引用: ${staleReference?.name}"
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
