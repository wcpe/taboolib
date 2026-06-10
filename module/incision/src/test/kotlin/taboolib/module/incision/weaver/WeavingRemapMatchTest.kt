package taboolib.module.incision.weaver

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.V1_8
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.remap.NameResolver
import taboolib.module.incision.remap.NoopResolver
import taboolib.module.incision.runtime.AdviceKind

/**
 * 方法级织入的「混淆环境名称匹配」回归测试。
 *
 * 复刻线上根因场景：用户在 @Surgeon 里按 **Mojang 源码名** 声明目标（如
 * `BlockBehaviour$BlockStateBase#getCollisionShape(BlockGetter, BlockPos, CollisionContext)VoxelShape`），
 * 而 1.20.1 Spigot 运行期实际类是 **Spigot 名** `BlockBase$BlockData`、方法名是 **Mojang 混淆名** `a`、
 * 描述符里的类型也是 Spigot 名（`IBlockAccess` / `BlockPosition`）。
 *
 * 修复前：[Scalpel.WeavingClassVisitor] 用 `target.name == 访问名` 直接比对，Mojang 名 `getCollisionShape`
 * 永不等于运行期混淆名 `a`，一个 dispatch 都注入不进去（静默落空）。
 * 修复后：visitor 用前向 resolver 把 Mojang 目标预解析成运行期名/描述符，比对时同时接受两套，
 * 于是混淆环境也能命中并注入 `IncisionBridge.dispatch`。
 *
 * 用 [AdviceKind.LEAD]（方法入口注入 + pop，不触发 bodies 生成）作为注入信号，匹配路径与其它 kind 完全一致。
 *
 * 注意：[Scalpel.weave] 会调用 [taboolib.module.incision.loader.JvmtiBackend] 按 **owner 名** 缓存原始字节，
 * 若本机加载了 JVMTI native 库则该缓存跨用例生效。故每个用例使用 **唯一 owner**，避免相互污染。
 */
@DisplayName("WeavingClassVisitor 混淆环境名称/描述符匹配")
class WeavingRemapMatchTest {

    /** 每个用例独立的 Mojang/运行期 owner 对 + 映射环境，避免 JvmtiBackend 原始字节缓存跨用例串扰。 */
    private class Fixture(suffix: String) {
        val ownerMojang = "net/minecraft/world/level/block/state/BlockBehaviour\$BlockStateBase$suffix"
        val ownerRuntime = "net/minecraft/world/level/block/state/BlockBase\$BlockData$suffix"

        /** 参数 / 返回类型：Mojang → Spigot（CollisionContext / VoxelShape 同名）。 */
        val classMap = mapOf(
            ownerMojang to ownerRuntime,
            "net/minecraft/world/level/BlockGetter" to "net/minecraft/world/level/IBlockAccess",
            "net/minecraft/core/BlockPos" to "net/minecraft/core/BlockPosition",
            "net/minecraft/world/phys/shapes/CollisionContext" to "net/minecraft/world/phys/shapes/CollisionContext",
            "net/minecraft/world/phys/shapes/VoxelShape" to "net/minecraft/world/phys/shapes/VoxelShape",
        )

        /** 方法名：(运行期 owner, Mojang 名) → 运行期混淆名。 */
        val methodMap = mapOf((ownerRuntime to "getCollisionShape") to "a")

        /** Mojang 源码名书写的目标描述符（用户在 @Surgeon 里写的形态）。 */
        val targetDescMojang =
            "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"

        /** 运行期(Spigot)实际类里方法 a 的描述符（类型为 Spigot 名）。 */
        val runtimeDescSpigot =
            "(Lnet/minecraft/world/level/IBlockAccess;Lnet/minecraft/core/BlockPosition;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"

        /** 模拟 NMS 的前向 resolver（Mojang → 运行期），仅覆盖本用例需要的映射。 */
        val resolver = object : NameResolver {
            override fun resolveOwner(internalName: String): String = classMap[internalName] ?: internalName
            override fun resolveMethod(owner: String, name: String, desc: String): Pair<String, String> {
                val runtimeOwner = resolveOwner(owner)
                return (methodMap[runtimeOwner to name] ?: name) to desc
            }
            override fun resolveField(owner: String, name: String, desc: String): Pair<String, String> = name to desc
            override fun isRemappableTarget(owner: String): Boolean = owner.startsWith("net/minecraft/")
            override fun envSignature(): String = "fake-legacy"
            override fun name(): String = "FakeLegacyResolver"
        }

        /** 目标：按 Mojang 源码名书写。 */
        fun targetSpec() = Scalpel.AdviceTargetSpec(
            target = MethodCoordinate(ownerMojang, "getCollisionShape", targetDescMojang),
            kinds = setOf(AdviceKind.LEAD),
        )

        /** 构造一个运行期目标类：类名/方法名/描述符按入参，方法体 `return null`。 */
        fun buildClass(methodName: String, methodDesc: String): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            cw.visit(V1_8, ACC_PUBLIC, ownerRuntime, null, "java/lang/Object", null)
            cw.visitMethod(ACC_PUBLIC, methodName, methodDesc, null, null).apply {
                visitCode()
                visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                visitInsn(ARETURN)
                visitMaxs(0, 0)
                visitEnd()
            }
            cw.visitEnd()
            return cw.toByteArray()
        }

        /** 构造含若干 (方法名, 描述符) 的运行期类；用于重载/多方法场景。返回 void 发 RETURN，否则发 `return null`。 */
        fun buildClassWithMethods(vararg methods: Pair<String, String>): ByteArray {
            val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
            cw.visit(V1_8, ACC_PUBLIC, ownerRuntime, null, "java/lang/Object", null)
            for ((mName, mDesc) in methods) {
                cw.visitMethod(ACC_PUBLIC, mName, mDesc, null, null).apply {
                    visitCode()
                    if (mDesc.endsWith(")V")) {
                        visitInsn(org.objectweb.asm.Opcodes.RETURN)
                    } else {
                        visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL)
                        visitInsn(ARETURN)
                    }
                    visitMaxs(0, 0)
                    visitEnd()
                }
            }
            cw.visitEnd()
            return cw.toByteArray()
        }
    }

    /** 统计某方法体内对 IncisionBridge.dispatch 的 INVOKESTATIC 次数。 */
    private fun dispatchCallCount(bytes: ByteArray, methodName: String): Int {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val m = node.methods.firstOrNull { it.name == methodName } ?: return 0
        return m.instructions.toArray().filterIsInstance<MethodInsnNode>().count {
            it.owner == "io/izzel/incision/bridge/IncisionBridge" && it.name == "dispatch"
        }
    }

    /** 描述符敏感版：按 (方法名, 描述符) 精确定位方法后再统计，用于重载消歧。 */
    private fun dispatchCallCount(bytes: ByteArray, methodName: String, methodDesc: String): Int {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        val m = node.methods.firstOrNull { it.name == methodName && it.desc == methodDesc } ?: return 0
        return m.instructions.toArray().filterIsInstance<MethodInsnNode>().count {
            it.owner == "io/izzel/incision/bridge/IncisionBridge" && it.name == "dispatch"
        }
    }

    private fun weaveWith(fx: Fixture, resolver: NameResolver, classBytes: ByteArray): ByteArray =
        Scalpel(resolver = resolver, targetsByOwner = mapOf(fx.ownerRuntime to listOf(fx.targetSpec()))).weave(classBytes)

    // ===== 核心：混淆环境下 Mojang 目标应命中运行期混淆方法并注入 =====

    @Test
    @DisplayName("Mojang 目标在混淆运行期类(方法名 a + Spigot 描述符)上命中并注入 dispatch")
    fun mojangTargetMatchesObfuscatedRuntimeMethod() {
        val fx = Fixture("Obf")
        val out = weaveWith(fx, fx.resolver, fx.buildClass("a", fx.runtimeDescSpigot))
        assertTrue(
            dispatchCallCount(out, "a") >= 1,
            "混淆运行期方法 a 应被注入至少一次 IncisionBridge.dispatch（修复前为 0）",
        )
    }

    // ===== 负向基线：Noop resolver 下 Mojang 目标不应误命中混淆方法 =====

    @Test
    @DisplayName("NoopResolver 下 Mojang 目标不命中混淆方法 a（证明匹配确由 resolver 前向解析驱动）")
    fun noopResolverDoesNotMatchObfuscatedMethod() {
        val fx = Fixture("Noop")
        val out = weaveWith(fx, NoopResolver, fx.buildClass("a", fx.runtimeDescSpigot))
        assertTrue(
            dispatchCallCount(out, "a") == 0,
            "Noop 环境无法把 getCollisionShape 解析为 a，不应误注入",
        )
    }

    // ===== 兼容基线：Mojang-mapping 环境(方法名本身就是 Mojang 名)仍能直接命中 =====

    @Test
    @DisplayName("Mojang-mapping 环境(方法名即 getCollisionShape)走原名直配，仍注入")
    fun mojangMappingEnvironmentStillMatchesByRawName() {
        val fx = Fixture("Moj")
        // 运行期方法名 = Mojang 名、描述符 = Mojang 名（等价 Paper 1.20.6+ / 非混淆服务端视角）。
        // 即使 resolver 为 Noop（无操作），原名直配也应命中。
        val out = weaveWith(fx, NoopResolver, fx.buildClass("getCollisionShape", fx.targetDescMojang))
        assertTrue(
            dispatchCallCount(out, "getCollisionShape") >= 1,
            "Mojang 名直配应命中并注入",
        )
    }

    // ===== 防御：完全无关的方法不被注入 =====

    @Test
    @DisplayName("无关方法不被注入")
    fun unrelatedMethodNotInjected() {
        val fx = Fixture("Unrel")
        val out = weaveWith(fx, fx.resolver, fx.buildClass("unrelatedMethod", "()V"))
        assertFalse(
            dispatchCallCount(out, "unrelatedMethod") >= 1,
            "无关方法不应被注入",
        )
    }

    // ===== 补齐：描述符消歧 / 通配 / site-body 路径的混淆匹配 =====

    @Test
    @DisplayName("重载消歧：只命中描述符匹配的重载 a，不误注入同名不同签名的 a")
    fun overloadedRuntimeMethodDisambiguatedByDescriptor() {
        val fx = Fixture("Overload")
        // 同一混淆名 a 的两个重载：一个是目标的运行期描述符，一个是无关的 ()V
        val out = weaveWith(
            fx, fx.resolver,
            fx.buildClassWithMethods("a" to fx.runtimeDescSpigot, "a" to "()V"),
        )
        assertTrue(
            dispatchCallCount(out, "a", fx.runtimeDescSpigot) >= 1,
            "描述符匹配的重载 a 应被注入",
        )
        assertTrue(
            dispatchCallCount(out, "a", "()V") == 0,
            "签名不匹配的重载 a()V 不应被注入",
        )
    }

    @Test
    @DisplayName("通配描述符 (*) 目标命中混淆方法 a")
    fun wildcardDescriptorMatchesObfuscatedMethod() {
        val fx = Fixture("Wild")
        val spec = Scalpel.AdviceTargetSpec(
            target = MethodCoordinate(fx.ownerMojang, "getCollisionShape", "(*)"),
            kinds = setOf(AdviceKind.LEAD),
        )
        val out = Scalpel(resolver = fx.resolver, targetsByOwner = mapOf(fx.ownerRuntime to listOf(spec)))
            .weave(fx.buildClass("a", fx.runtimeDescSpigot))
        assertTrue(
            dispatchCallCount(out, "a") >= 1,
            "通配描述符目标应命中混淆方法 a 并注入",
        )
    }

    @Test
    @DisplayName("混淆环境 SPLICE 目标按运行期描述符生成 bodies（守护 site/body 路径的描述符 remap）")
    fun spliceTargetGeneratesBodiesInObfuscatedEnv() {
        val fx = Fixture("Splice")
        val bodiesName = BridgeClassLoader.bodiesClassName(fx.ownerRuntime)
        val spec = Scalpel.AdviceTargetSpec(
            target = MethodCoordinate(fx.ownerMojang, "getCollisionShape", fx.targetDescMojang),
            kinds = setOf(AdviceKind.SPLICE),
        )
        Scalpel(resolver = fx.resolver, targetsByOwner = mapOf(fx.ownerRuntime to listOf(spec)))
            .weave(fx.buildClass("a", fx.runtimeDescSpigot))
        assertTrue(
            BridgeClassLoader.INSTANCE.hasBodies(bodiesName),
            "SPLICE 目标在混淆环境应按运行期描述符命中并生成 bodies；matchTarget.descriptor 未 remap 会找不到方法（即问题③）",
        )
    }
}
