package taboolib.module.incision.weaver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.remap.NoopResolver
import taboolib.module.incision.runtime.AdviceKind

/**
 * 多插件 transformer 串联时的入口幂等回归。
 *
 * 第二个插件拿到第一个插件已经变换过的字节码；它仍要注册自己的 dispatcher，
 * 但不能再向相同方法写入第二个物理 Bridge 入口，否则两个 dispatcher 都会被执行两次。
 */
@DisplayName("Scalpel 跨插件 Bridge 入口幂等")
class ScalpelEntryIdempotencyTest {

    private val owner = "test/incision/SharedTarget"
    private val target = MethodCoordinate(owner, "value", "()I")

    @Test
    fun `second transformer reuses existing lead entry`() {
        val firstPluginWeaver = weaver()
        val secondPluginWeaver = weaver()

        val firstOutput = firstPluginWeaver.weave(targetBytes())
        val secondOutput = secondPluginWeaver.weave(firstOutput)

        assertEquals(1, bridgeDispatchCount(secondOutput))
    }

    /** 两个实例模拟两个隔离插件各自持有的 Scalpel transformer。 */
    private fun weaver(): Scalpel = Scalpel(
        resolver = NoopResolver,
        targetsByOwner = mapOf(
            owner to listOf(Scalpel.AdviceTargetSpec(target, setOf(AdviceKind.LEAD)))
        ),
    )

    private fun targetBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "value", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 20)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun bridgeDispatchCount(bytes: ByteArray): Int {
        val node = ClassNode(Opcodes.ASM9)
        ClassReader(bytes).accept(node, 0)
        return node.methods
            .single { it.name == "value" && it.desc == "()I" }
            .instructions
            .count {
                it is MethodInsnNode &&
                    it.owner == "io/izzel/incision/bridge/IncisionBridge" &&
                    it.name == "dispatch"
            }
    }
}
