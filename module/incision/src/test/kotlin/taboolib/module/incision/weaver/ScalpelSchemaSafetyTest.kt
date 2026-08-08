package taboolib.module.incision.weaver

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACONST_NULL
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.ARETURN
import org.objectweb.asm.Opcodes.BIPUSH
import org.objectweb.asm.Opcodes.DUP
import org.objectweb.asm.Opcodes.GETFIELD
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.NEW
import org.objectweb.asm.Opcodes.PUTFIELD
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_8
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import taboolib.module.incision.api.Anchor
import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.loader.JvmtiBackend
import taboolib.module.incision.remap.NameResolver
import taboolib.module.incision.runtime.AdviceKind
import taboolib.module.incision.weaver.site.SiteSpec

/**
 * Scalpel retransform schema 安全回归测试。
 *
 * 复现旧实现风险：目标类命中 NMS remap 时，ClassRemapper 会把整类已有方法一起改名，
 * JVMTI retransform 会因此判定方法 schema 被删除/改名并拒绝更新。
 */
@DisplayName("Scalpel retransform schema 安全")
class ScalpelSchemaSafetyTest {

    private val ownerInternal = "net/minecraft/server/level/FakeEntityPlayer"
    private val target = MethodCoordinate(ownerInternal, "drop", "()Ljava/lang/Object;")

    @Test
    @DisplayName("remappable NMS 类织入时不得改名非目标方法")
    fun remappableTargetKeepsNonTargetMethodSchema() {
        JvmtiBackend.purgeCache(ownerInternal)
        val originalBytes = buildTargetBytes()
        val weaved = Scalpel(
            resolver = SchemaBreakingResolver(ownerInternal),
            targetsByOwner = mapOf(
                ownerInternal to listOf(
                    Scalpel.AdviceTargetSpec(target, setOf(AdviceKind.LEAD))
                )
            )
        ).weave(originalBytes)

        val node = readNode(weaved)
        val methodNames = node.methods.map { it.name to it.desc }

        assertTrue("nextContainerCounter" to "()I" in methodNames, "非目标方法名必须保持不变")
        assertFalse("gp" to "()I" in methodNames, "非目标方法不得被 resolver 改名")
        // retransform schema 安全核心断言：织入只改方法体，方法表（名+描述符集合）必须与原始字节码完全一致
        assertMethodSchemaUnchanged(originalBytes, weaved)
        assertDispatcherInjected(node, target)
        assertLoadable(node, weaved)
    }

    @Test
    @DisplayName("目标原名存在时优先原名且不误用 resolver 名称")
    fun targetMethodPrefersOriginalNameWhenPresent() {
        JvmtiBackend.purgeCache(ownerInternal)
        val weaved = Scalpel(
            resolver = RuntimeNameResolver(ownerInternal),
            targetsByOwner = mapOf(
                ownerInternal to listOf(
                    Scalpel.AdviceTargetSpec(target, setOf(AdviceKind.LEAD))
                )
            )
        ).weave(buildTargetBytes(extraObjectMethodName = "a"))

        val node = readNode(weaved)
        assertDispatcherInjected(node, target, methodName = "drop")
        assertNoDispatcherInjected(node, methodName = "a")
        assertLoadable(node, weaved)
    }

    @Test
    @DisplayName("目标方法原名不存在时可用 resolver 名称匹配")
    fun targetMethodCanMatchResolverNameWithoutChangingDispatchKey() {
        JvmtiBackend.purgeCache(ownerInternal)
        val originalBytes = buildTargetBytes(methodName = "a")
        val weaved = Scalpel(
            resolver = RuntimeNameResolver(ownerInternal),
            targetsByOwner = mapOf(
                ownerInternal to listOf(
                    Scalpel.AdviceTargetSpec(target, setOf(AdviceKind.LEAD))
                )
            )
        ).weave(originalBytes)

        val node = readNode(weaved)
        val methodNames = node.methods.map { it.name to it.desc }

        assertTrue("a" to "()Ljava/lang/Object;" in methodNames, "运行时方法名必须保持不变")
        assertFalse("drop" to "()Ljava/lang/Object;" in methodNames, "不得为了匹配恢复声明方法名")
        // 声明名 drop 映射到运行时名 a：织入命中后方法表仍须与原始一致，不得新增 drop(...) 或改名
        assertMethodSchemaUnchanged(originalBytes, weaved)
        assertDispatcherInjected(node, target, methodName = "a")
        assertLoadable(node, weaved)
    }

    @Test
    @DisplayName("字段读取 site 可用 resolver 名称匹配且保持原始 dispatch key")
    fun fieldGetSiteCanMatchResolverNameWithoutChangingDispatchKey() {
        val site = SiteSpec(
            anchor = Anchor.FIELD_GET,
            ownerPattern = ownerInternal,
            namePattern = "declaredOwner",
            descPattern = "Ljava/lang/String;",
            kind = AdviceKind.GRAFT,
            target = target,
        )
        val node = weaveSite(site, buildTargetBytes(fieldName = "runtimeOwner", methodReadsField = true))

        assertFieldAccessKept(node, "runtimeOwner", GETFIELD)
        assertSiteDispatcherInjected(node, target)
    }

    @Test
    @DisplayName("字段写入 site 可用 resolver 名称匹配且保持原始 dispatch key")
    fun fieldPutSiteCanMatchResolverNameWithoutChangingDispatchKey() {
        val site = SiteSpec(
            anchor = Anchor.FIELD_PUT,
            ownerPattern = ownerInternal,
            namePattern = "declaredOwner",
            descPattern = "Ljava/lang/String;",
            kind = AdviceKind.GRAFT,
            target = target,
        )
        val node = weaveSite(site, buildTargetBytes(fieldName = "runtimeOwner", methodWritesField = true))

        assertFieldAccessKept(node, "runtimeOwner", PUTFIELD)
        assertSiteDispatcherInjected(node, target)
    }

    @Test
    @DisplayName("方法调用 site 可用 resolver 名称匹配且保持原始 dispatch key")
    fun invokeSiteCanMatchResolverNameWithoutChangingDispatchKey() {
        val site = SiteSpec(
            anchor = Anchor.INVOKE,
            ownerPattern = ownerInternal,
            namePattern = "declaredHelper",
            descPattern = "()Ljava/lang/Object;",
            kind = AdviceKind.GRAFT,
            target = target,
        )
        val node = weaveSite(site, buildTargetBytes(methodInvokesHelper = true))

        assertInvokeKept(node, "runtimeHelper")
        assertSiteDispatcherInjected(node, target)
    }

    @Test
    @DisplayName("对象创建 site 可用 resolver 类型匹配且保持运行时类型")
    fun newSiteCanMatchResolverNameWithoutChangingClassSchema() {
        val site = SiteSpec(
            anchor = Anchor.NEW,
            ownerPattern = "declared/Widget",
            kind = AdviceKind.GRAFT,
            target = target,
        )
        val node = weaveSite(site, buildTargetBytes(methodCreatesObject = true))

        assertNewKept(node, "java/lang/Object")
        assertSiteDispatcherInjected(node, target)
    }

    private fun weaveSite(site: SiteSpec, bytes: ByteArray): ClassNode {
        JvmtiBackend.purgeCache(ownerInternal)
        val weaved = Scalpel(
            resolver = RuntimeNameResolver(ownerInternal),
            targetsByOwner = mapOf(
                ownerInternal to listOf(
                    Scalpel.AdviceTargetSpec(target, setOf(site.kind), listOf(site))
                )
            )
        ).weave(bytes)
        val node = readNode(weaved)
        assertLoadable(node, weaved)
        return node
    }

    private fun assertDispatcherInjected(node: ClassNode, target: MethodCoordinate, methodName: String = "drop") {
        val method = node.methods.first { it.name == methodName && it.desc == "()Ljava/lang/Object;" }
        val instructions = method.instructions.toArray().toList()
        val hasDispatchCall = instructions.filterIsInstance<MethodInsnNode>().any {
            it.owner == "io/izzel/incision/bridge/IncisionBridge" &&
                it.name == "dispatch" &&
                it.desc == "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
        }
        val hasOriginalDispatchKey = instructions.filterIsInstance<LdcInsnNode>().any {
            it.cst == "${target.signature}@LEAD"
        }

        assertTrue(hasDispatchCall, "目标方法应注入 IncisionBridge.dispatch")
        assertTrue(hasOriginalDispatchKey, "dispatcher key 必须保持原始 target.signature")
    }

    private fun assertNoDispatcherInjected(node: ClassNode, methodName: String) {
        val method = node.methods.first { it.name == methodName && it.desc == "()Ljava/lang/Object;" }
        val hasDispatchCall = method.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
            it.owner == "io/izzel/incision/bridge/IncisionBridge" && it.name == "dispatch"
        }
        assertFalse(hasDispatchCall, "非目标方法不得注入 IncisionBridge.dispatch")
    }

    /**
     * retransform schema 安全核心断言：织入只允许修改方法体，方法表必须保持不变。
     *
     * 对比织入前后类的方法签名集合（name + descriptor）。HotSpot 的 RetransformClasses
     * 不允许新增/删除/改名方法，否则返回 JVMTI_ERROR_UNSUPPORTED_REDEFINITION_METHOD_ADDED(error=63)。
     * 若旧实现对命中 NMS resolver 的目标类套整类 ClassRemapper，会把非目标方法一并改名，
     * 等价于"删旧方法 + 增新方法"，此断言即可在源码层拦截该回归。
     */
    private fun assertMethodSchemaUnchanged(originalBytes: ByteArray, weavedBytes: ByteArray) {
        val before = readNode(originalBytes).methods.map { it.name to it.desc }.toSortedSet(methodComparator)
        val after = readNode(weavedBytes).methods.map { it.name to it.desc }.toSortedSet(methodComparator)
        val added = after - before
        val removed = before - after
        assertTrue(added.isEmpty(), "织入不得新增方法（schema 变化将触发 retransform error=63）：新增=$added")
        assertTrue(removed.isEmpty(), "织入不得删除/改名方法（schema 变化将触发 retransform error=63）：删除=$removed")
        assertEquals(before, after, "织入前后方法签名集合必须完全一致")
    }

    private val methodComparator: Comparator<Pair<String, String>> =
        compareBy({ it.first }, { it.second })

    private fun assertFieldAccessKept(node: ClassNode, fieldName: String, opcode: Int) {
        val method = node.methods.first { it.name == "drop" && it.desc == "()Ljava/lang/Object;" }
        val hasRuntimeField = method.instructions.toArray().filterIsInstance<FieldInsnNode>().any {
            it.opcode == opcode && it.owner == ownerInternal && it.name == fieldName && it.desc == "Ljava/lang/String;"
        }
        assertTrue(hasRuntimeField, "字段访问本体必须保持运行时字段名")
    }

    private fun assertInvokeKept(node: ClassNode, methodName: String) {
        val method = node.methods.first { it.name == "drop" && it.desc == "()Ljava/lang/Object;" }
        val hasRuntimeInvoke = method.instructions.toArray().filterIsInstance<MethodInsnNode>().any {
            it.owner == ownerInternal && it.name == methodName && it.desc == "()Ljava/lang/Object;"
        }
        assertTrue(hasRuntimeInvoke, "方法调用本体必须保持运行时方法名")
    }

    private fun assertNewKept(node: ClassNode, typeName: String) {
        val method = node.methods.first { it.name == "drop" && it.desc == "()Ljava/lang/Object;" }
        val hasRuntimeNew = method.instructions.toArray().filterIsInstance<TypeInsnNode>().any {
            it.opcode == NEW && it.desc == typeName
        }
        assertTrue(hasRuntimeNew, "对象创建本体必须保持运行时类型名")
    }

    private fun assertSiteDispatcherInjected(node: ClassNode, target: MethodCoordinate) {
        val method = node.methods.first { it.name == "drop" && it.desc == "()Ljava/lang/Object;" }
        val instructions = method.instructions.toArray().toList()
        val hasDispatchCall = instructions.filterIsInstance<MethodInsnNode>().any {
            it.owner == "io/izzel/incision/bridge/IncisionBridge" &&
                it.name == "dispatch" &&
                it.desc == "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"
        }
        val hasOriginalDispatchKey = instructions.filterIsInstance<LdcInsnNode>().any {
            it.cst == target.signature
        }

        assertTrue(hasDispatchCall, "site 应注入 IncisionBridge.dispatch")
        assertTrue(hasOriginalDispatchKey, "site dispatcher key 必须保持原始 target.signature")
    }

    private fun buildTargetBytes(
        methodName: String = "drop",
        extraObjectMethodName: String? = null,
        fieldName: String? = null,
        methodReadsField: Boolean = false,
        methodWritesField: Boolean = false,
        methodInvokesHelper: Boolean = false,
        methodCreatesObject: Boolean = false,
    ): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_8, ACC_PUBLIC, ownerInternal, null, "java/lang/Object", null)
        writeConstructor(cw)
        if (fieldName != null) writeField(cw, fieldName)
        writeObjectMethod(
            cw,
            methodName,
            fieldName,
            methodReadsField,
            methodWritesField,
            methodInvokesHelper,
            methodCreatesObject,
        )
        if (methodInvokesHelper) {
            writeObjectMethod(cw, "runtimeHelper")
        }
        if (extraObjectMethodName != null) {
            writeObjectMethod(cw, extraObjectMethodName)
        }
        writeNextContainerCounter(cw)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeConstructor(cw: ClassWriter) {
        cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun writeField(cw: ClassWriter, fieldName: String) {
        cw.visitField(ACC_PUBLIC, fieldName, "Ljava/lang/String;", null, null).visitEnd()
    }

    private fun writeObjectMethod(
        cw: ClassWriter,
        methodName: String,
        fieldName: String? = null,
        methodReadsField: Boolean = false,
        methodWritesField: Boolean = false,
        methodInvokesHelper: Boolean = false,
        methodCreatesObject: Boolean = false,
    ) {
        cw.visitMethod(ACC_PUBLIC, methodName, "()Ljava/lang/Object;", null, null).apply {
            visitCode()
            when {
                methodReadsField && fieldName != null -> {
                    visitVarInsn(ALOAD, 0)
                    visitFieldInsn(GETFIELD, ownerInternal, fieldName, "Ljava/lang/String;")
                }
                methodWritesField && fieldName != null -> {
                    visitVarInsn(ALOAD, 0)
                    visitInsn(ACONST_NULL)
                    visitFieldInsn(PUTFIELD, ownerInternal, fieldName, "Ljava/lang/String;")
                    visitInsn(ACONST_NULL)
                }
                methodInvokesHelper -> {
                    visitVarInsn(ALOAD, 0)
                    visitMethodInsn(INVOKEVIRTUAL, ownerInternal, "runtimeHelper", "()Ljava/lang/Object;", false)
                }
                methodCreatesObject -> {
                    visitTypeInsn(NEW, "java/lang/Object")
                    visitInsn(DUP)
                    visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                }
                else -> visitInsn(ACONST_NULL)
            }
            visitInsn(ARETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun writeNextContainerCounter(cw: ClassWriter) {
        cw.visitMethod(ACC_PUBLIC, "nextContainerCounter", "()I", null, null).apply {
            visitCode()
            visitIntInsn(BIPUSH, 7)
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun readNode(bytes: ByteArray): ClassNode {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        return node
    }

    private fun assertLoadable(node: ClassNode, bytes: ByteArray) {
        assertDoesNotThrow {
            ByteClassLoader().define(node.name.replace('/', '.'), bytes)
        }
    }

    private class SchemaBreakingResolver(private val remappableOwner: String) : NameResolver {

        override fun resolveOwner(internalName: String): String = internalName

        override fun resolveMethod(owner: String, name: String, desc: String): Pair<String, String> {
            if (owner == remappableOwner && name == "nextContainerCounter" && desc == "()I") {
                return "gp" to desc
            }
            return name to desc
        }

        override fun resolveField(owner: String, name: String, desc: String): Pair<String, String> = name to desc

        override fun isRemappableTarget(owner: String): Boolean = owner == remappableOwner

        override fun envSignature(): String = "schema-safety-test"
    }

    private class RuntimeNameResolver(private val remappableOwner: String) : NameResolver {

        override fun resolveOwner(internalName: String): String {
            if (internalName == "declared/Widget") {
                return "java/lang/Object"
            }
            return internalName
        }

        override fun resolveMethod(owner: String, name: String, desc: String): Pair<String, String> {
            if (owner == remappableOwner && name == "drop" && desc == "()Ljava/lang/Object;") {
                return "a" to desc
            }
            if (owner == remappableOwner && name == "declaredHelper" && desc == "()Ljava/lang/Object;") {
                return "runtimeHelper" to desc
            }
            return name to desc
        }

        override fun resolveField(owner: String, name: String, desc: String): Pair<String, String> {
            if (owner == remappableOwner && name == "declaredOwner" && desc == "Ljava/lang/String;") {
                return "runtimeOwner" to desc
            }
            return name to desc
        }

        override fun isRemappableTarget(owner: String): Boolean = owner == remappableOwner

        override fun envSignature(): String = "runtime-name-test"
    }

    private class ByteClassLoader : ClassLoader(ScalpelSchemaSafetyTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }
}
