package taboolib.module.nms.test

import net.minecraft.SystemUtils
import org.tabooproject.reflex.Reflex.Companion.getProperty
import org.tabooproject.reflex.Reflex.Companion.invokeMethod
import taboolib.common.Test
import taboolib.common.Test.Result
import taboolib.common.reflect.ClassHelper
import taboolib.module.nms.*
import taboolib.module.nms.remap.RemapHelper

/**
 * TabooLib
 * taboolib.module.nms.test.TestNMS
 *
 * @author 坏黑
 * @since 2024/7/21 17:27
 */
object TestNMS : Test() {

    override fun check(): List<Result> {
        val result = arrayListOf<Result>()
        result += sandbox("NMS:isBukkitServerRunning") { check(isBukkitServerRunning) }
        result += sandbox("NMS:minecraftServerObject") { check(minecraftServerObject.javaClass.name.startsWith("net.minecraft.")) }
        // 获取 OBC 类
        result += sandbox("NMS:obcClass") { check(obcClass("CraftServer").name.startsWith("org.bukkit.craftbukkit.")) }
        // 获取 NMS 类
        val minecraftServerName = if (MinecraftVersion.isUnobfuscated) "server.MinecraftServer" else "MinecraftServer"
        result += sandbox("NMS:nmsClass") { check(nmsClass(minecraftServerName).name.startsWith("net.minecraft.")) }
        // 测试 ClassUtils
        result += sandbox("NMS:ClassUtils") {
            val className = nmsClass(minecraftServerName).name
            check(ClassHelper.getClass(className, false).name.startsWith("net.minecraft."))
        }
        result += sandbox("NMS:versionComparison") {
            val major = MinecraftVersion.major
            check(MinecraftVersion.isEqual(major))
            check(MinecraftVersion.isHigherOrEqual(major))
            check(MinecraftVersion.isLowerOrEqual(major))
            check(MinecraftVersion.isIn(major..major))
            check(MinecraftVersion.isIn(major, major))
            if (major > MinecraftVersion.V1_8) {
                check(MinecraftVersion.isHigher(major - 1))
            }
            check(MinecraftVersion.isLower(major + 1))
        }
        result += sandbox("NMS:Mapping.spigot") {
            val mapping = Mapping.spigot(
                "net/minecraft/util/Util net/minecraft/SystemUtils\n".byteInputStream(),
                "net/minecraft/SystemUtils a NIL_UUID\nnet/minecraft/SystemUtils b ()J getEpochMillis\n".byteInputStream(),
            )
            check(mapping.classMapSpigotS2F["SystemUtils"] == "net.minecraft.SystemUtils")
            check(mapping.fields.single() == Mapping.Field("net.minecraft.SystemUtils", "a", "NIL_UUID"))
            check(mapping.methods.single() == Mapping.Method("net.minecraft.SystemUtils", "b", "getEpochMillis", "()J"))
        }
        result += sandbox("NMS:Mapping.paperUniqueSimpleName") {
            if (MinecraftVersion.isUniversalCraftBukkit && !MinecraftVersion.isUnobfuscated) {
                val mapping = MinecraftVersion.paperMapping
                val classesBySimpleName = mapping.classMapSpigotToMojang.keys
                    .filter { it.startsWith("net.minecraft.") }
                    .groupBy { it.substringAfterLast('.') }
                mapping.classMapSpigotS2F.forEach { (simpleName, fullName) ->
                    check(classesBySimpleName[simpleName] == listOf(fullName))
                }
            }
        }
        result += sandbox("NMS:RemapHelper.parameterTypes") {
            check(RemapHelper.checkParameterType("(Ljava/lang/String;I)V", "(Ljava/lang/String;I)V"))
            check(!RemapHelper.checkParameterType("(Ljava/lang/String;)V", "(Ljava/util/UUID;)V"))
        }
        // 测试动态转译
        if (MinecraftVersion.isUnobfuscated) {
            result += sandbox("NMS:Translation") { nmsClass("util.Util").invokeMethod<Long>("getEpochMillis", isStatic = true) }
        } else {
            result += sandbox("NMS:Translation") { nmsProxy<TestNMSTranslation>().test(result) }
        }
        // 测试非转译环境下的 Reflex
        if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_17)) {
            val fieldName = if (MinecraftVersion.isUnobfuscated) "NIL_UUID" else "a"
            val epochMethodName = if (MinecraftVersion.isUnobfuscated) "getEpochMillis" else "a"
            val uriMethodName = if (MinecraftVersion.isUnobfuscated) "parseAndValidateUntrustedUri" else "a"
            val systemUtilsName = if (MinecraftVersion.isUnobfuscated) "util.Util" else "SystemUtils"
            result += sandbox("NMS:Reflex(f)") { nmsClass(systemUtilsName).getProperty<Any>(fieldName, isStatic = true) }
            result += sandbox("NMS:Reflex(m)") { nmsClass(systemUtilsName).invokeMethod<Any>(epochMethodName, isStatic = true) }
            result += sandbox("NMS:Reflex(m)") {
                nmsClass(systemUtilsName).invokeMethod<Any>(uriMethodName, "https://example.com", isStatic = true)
            }
        }
        return result
    }
}

abstract class TestNMSTranslation {

    abstract fun test(result: MutableList<Result>)
}

class TestNMSTranslationImpl : TestNMSTranslation() {

    override fun test(result: MutableList<Result>) {
        if (MinecraftVersion.isHigherOrEqual(MinecraftVersion.V1_17)) {
            // 测试转译
            result += Test.sandbox("NMS:Translation:PaperRemap") { SystemUtils.getEpochMillis() }
            // 测试转译环境下的 Reflex
            result += Test.sandbox("NMS:Translation:Reflex(f)") { net.minecraft.SystemUtils::class.java.getProperty<Any>("a", isStatic = true) }
            result += Test.sandbox("NMS:Translation:Reflex(m)") { net.minecraft.SystemUtils::class.java.invokeMethod<Any>("a", isStatic = true) }
            result += Test.sandbox("NMS:Translation:Reflex(m)") {
                net.minecraft.SystemUtils::class.java.invokeMethod<Any>("a", "https://example.com", isStatic = true)
            }
        }
    }
}
