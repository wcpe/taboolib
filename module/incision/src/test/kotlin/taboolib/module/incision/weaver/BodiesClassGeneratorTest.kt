package taboolib.module.incision.weaver

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ALOAD
import org.objectweb.asm.Opcodes.GETFIELD
import org.objectweb.asm.Opcodes.ICONST_0
import org.objectweb.asm.Opcodes.INVOKESPECIAL
import org.objectweb.asm.Opcodes.IRETURN
import org.objectweb.asm.Opcodes.PUTFIELD
import org.objectweb.asm.Opcodes.RETURN
import org.objectweb.asm.Opcodes.V1_8
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * BodiesClassGenerator 回归测试。
 *
 * 覆盖两个曾导致 `XXX$$IncisionBodies` 在 defineClass 阶段抛 ClassFormatError 的缺陷：
 *
 * Bug 1（Illegal class name）：
 *   JvmtiBackend 的内部名曾以 `const val "taboolib/module/incision/loader/JvmtiBackend"`
 *   硬编码。const 会被内联，TabooLib Shadow relocation 把其中的 `taboolib` 段按「包名(点号)」
 *   规则替换为重定位前缀，当宿主插件 group 较长（多段点号）时得到点斜混合的非法内部名
 *   `group.taboolib/module/incision/loader/JvmtiBackend`，被用作 INVOKESTATIC owner 后
 *   defineClass 抛 `Illegal class name`。修复：改为运行期 `JvmtiBackend::class.java.name.replace('.', '/')`。
 *
 * Bug 2（Illegal method name）：
 *   当被 @Splice 的目标方法是构造函数 `<init>` / 静态初始化 `<clinit>` 时，
 *   body 方法名曾直接拼成 `<init>$body`，含非法字符 `<` `>`，defineClass 抛 `Illegal method name`。
 *   修复：跳过 `<init>` / `<clinit>` 的 body 生成（其方法体含 super()/this() INVOKESPECIAL，
 *   本就无法在 static 上下文安全复制，运行期由 IncisionBridge.dispatch 兜底）。
 */
@DisplayName("BodiesClassGenerator 非法名回归")
class BodiesClassGeneratorTest {

    private val ownerInternal = "taboolib/module/incision/weaver/sample/SampleTarget"

    /**
     * 构造一个用于测试的目标类字节码：
     * - 私有字段 `private int value`
     * - 构造函数 `<init>(int)`：super() + this.value = arg（含 INVOKESPECIAL super.<init>）
     * - 实例方法 `getValue()I`：return this.value（含 GETFIELD private 字段）
     * - 静态方法 `staticHelper()I`：return 0
     */
    private fun buildSampleTargetBytes(): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_8, ACC_PUBLIC, ownerInternal, null, "java/lang/Object", null)

        cw.visitField(ACC_PRIVATE, "value", "I", null, null).visitEnd()

        // 构造函数 <init>(I)V
        cw.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitVarInsn(ALOAD, 0)
            visitVarInsn(org.objectweb.asm.Opcodes.ILOAD, 1)
            visitFieldInsn(PUTFIELD, ownerInternal, "value", "I")
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        // 实例方法 getValue()I
        cw.visitMethod(ACC_PUBLIC, "getValue", "()I", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitFieldInsn(GETFIELD, ownerInternal, "value", "I")
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        // 静态方法 staticHelper()I
        cw.visitMethod(ACC_STATIC or ACC_PUBLIC, "staticHelper", "()I", null, null).apply {
            visitCode()
            visitInsn(ICONST_0)
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }

        cw.visitEnd()
        return cw.toByteArray()
    }

    /** 简易类加载器：仅用于把生成的字节定义成 Class，触发 JVM 名称校验 */
    private class ByteClassLoader : ClassLoader(BodiesClassGeneratorTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private fun readNode(bytes: ByteArray): ClassNode {
        val node = ClassNode()
        ClassReader(bytes).accept(node, 0)
        return node
    }

    // ===== Bug 2：构造函数 / 静态初始化 不得生成非法方法名 =====

    @Test
    @DisplayName("目标含 <init> 时跳过 body 生成，绝不产生非法方法名 <init>\$body")
    fun constructorTargetDoesNotProduceIllegalMethodName() {
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("<init>" to "(I)V")
        )
        // 仅有 <init> 一个目标且被跳过 → 没有任何可生成的 body → 返回 null
        assertNull(bytes, "仅构造函数作为目标时应无 body 生成，返回 null")
    }

    @Test
    @DisplayName("<init> 与普通方法混合时，仅普通方法生成 body，且无 <init>\$body")
    fun mixedTargetsSkipConstructorOnly() {
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("<init>" to "(I)V", "getValue" to "()I")
        )
        assertNotNull(bytes, "存在普通方法目标时应生成 bodies 类")

        val node = readNode(bytes!!)
        val methodNames = node.methods.map { it.name }
        // 不允许出现任何含 '<' '>' 的非法方法名
        assertFalse(
            methodNames.any { it.contains('<') || it.contains('>') },
            "生成的 bodies 方法名不得包含 '<' '>'，实际: $methodNames"
        )
        assertTrue(methodNames.contains("getValue\$body"), "普通方法应生成 getValue\$body，实际: $methodNames")
        assertFalse(methodNames.contains("<init>\$body"), "绝不允许生成 <init>\$body")
    }

    @Test
    @DisplayName("生成的 bodies 类可被 defineClass 加载，不抛 ClassFormatError")
    fun generatedBodiesClassIsLoadable() {
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("<init>" to "(I)V", "getValue" to "()I")
        )
        assertNotNull(bytes)
        val node = readNode(bytes!!)
        // defineClass 会触发 JVM 对类名/方法名/常量引用的合法性校验
        assertDoesNotThrow {
            ByteClassLoader().define(node.name.replace('/', '.'), bytes)
        }
    }

    // ===== Bug 1：JvmtiBackend 引用必须为合法全斜杠内部名 =====

    @Test
    @DisplayName("body 中对 JvmtiBackend 的 INVOKESTATIC owner 为合法全斜杠内部名（无点斜混合）")
    fun jvmtiBackendOwnerIsValidInternalName() {
        // getValue 含 GETFIELD private 字段 → 会改写为 JvmtiBackend.nFieldGet 调用
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("getValue" to "()I")
        )
        assertNotNull(bytes)
        val node = readNode(bytes!!)

        val jvmtiInvokes = node.methods
            .flatMap { it.instructions.toArray().toList() }
            .filterIsInstance<MethodInsnNode>()
            .filter { it.owner.endsWith("/JvmtiBackend") || it.owner.endsWith(".JvmtiBackend") }

        assertTrue(jvmtiInvokes.isNotEmpty(), "getValue 改写后应至少有一处对 JvmtiBackend 的调用")

        jvmtiInvokes.forEach { insn ->
            val owner = insn.owner
            // 合法内部名：全斜杠、不含点号、不含点斜混合
            assertFalse(owner.contains('.'), "JvmtiBackend owner 不应含点号（点斜混合非法名）: $owner")
            assertTrue(
                owner.endsWith("incision/loader/JvmtiBackend"),
                "JvmtiBackend owner 应为全斜杠内部名，实际: $owner"
            )
        }
    }

    @Test
    @DisplayName("含 private 字段访问的 body 改写后整体可加载（间接校验 JvmtiBackend 引用合法）")
    fun bodyWithPrivateFieldAccessIsLoadable() {
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("getValue" to "()I")
        )
        assertNotNull(bytes)
        val node = readNode(bytes!!)
        assertDoesNotThrow {
            ByteClassLoader().define(node.name.replace('/', '.'), bytes)
        }
    }

    // ===== 行为基线：静态方法目标被忽略、空目标返回 null =====

    @Test
    @DisplayName("静态方法作为目标被忽略（static 方法无 self，不生成 body）")
    fun staticMethodTargetIsIgnored() {
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("staticHelper" to "()I")
        )
        assertNull(bytes, "静态方法不应生成 body")
    }

    @Test
    @DisplayName("空目标集合返回 null")
    fun emptyTargetsReturnsNull() {
        val bytes = generate(buildSampleTargetBytes(), targetMethods = emptySet())
        assertNull(bytes)
    }

    @Test
    @DisplayName("普通实例方法正常生成 body 且类名为 owner\$\$IncisionBodies")
    fun normalMethodGeneratesBody() {
        val bytes = generate(
            buildSampleTargetBytes(),
            targetMethods = setOf("getValue" to "()I")
        )
        assertNotNull(bytes)
        val node = readNode(bytes!!)
        assertTrue(
            node.name.endsWith("\$\$IncisionBodies"),
            "bodies 类名应以 \$\$IncisionBodies 结尾，实际: ${node.name}"
        )
        val body = node.methods.first { it.name == "getValue\$body" }
        // 统一 body 描述符 (Object, Object[]) -> Object
        assertTrue(
            body.desc == "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;",
            "body 描述符应为统一签名，实际: ${body.desc}"
        )
        assertTrue(body.access and ACC_STATIC != 0, "body 方法应为 static")
    }

    @Test
    @DisplayName("运行时方法名被映射时 body 使用声明方法别名")
    fun remappedRuntimeMethodUsesDeclaredBodyName() {
        val bytes = generate(
            buildAliasedTargetBytes("a"),
            targetMethods = setOf("a" to "()I"),
            bodyMethodNames = mapOf(("a" to "()I") to "drop\$body"),
        )
        assertNotNull(bytes)
        val node = readNode(bytes!!)
        val methodNames = node.methods.map { it.name }

        assertTrue(methodNames.contains("drop\$body"), "运行时方法 a 应生成声明名 drop\$body")
        assertFalse(methodNames.contains("a\$body"), "不得只生成运行时名 a\$body")
    }

    // ===== 工具 =====

    private fun buildAliasedTargetBytes(runtimeMethodName: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(V1_8, ACC_PUBLIC, ownerInternal, null, "java/lang/Object", null)
        cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(ALOAD, 0)
            visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitMethod(ACC_PUBLIC, runtimeMethodName, "()I", null, null).apply {
            visitCode()
            visitInsn(ICONST_0)
            visitInsn(IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun generate(
        originalBytes: ByteArray,
        targetMethods: Set<Pair<String, String>>,
        bodyMethodNames: Map<Pair<String, String>, String> = emptyMap(),
    ): ByteArray? {
        return BodiesClassGenerator.generate(originalBytes, ownerInternal, targetMethods, bodyMethodNames)
    }
}
