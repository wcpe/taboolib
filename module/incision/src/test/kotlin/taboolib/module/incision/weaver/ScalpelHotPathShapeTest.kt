package taboolib.module.incision.weaver

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LineNumberNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.objectweb.asm.util.Printer
import taboolib.module.incision.api.Anchor
import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.remap.NoopResolver
import taboolib.module.incision.runtime.AdviceKind
import taboolib.module.incision.weaver.site.SiteSpec
import taboolib.module.incision.weaver.site.emitter.DispatcherEmitter

/**
 * Scalpel 热路径**结构**验收 —— 纯 ASM 静态断言，不含任何计时，因此零 flaky。
 *
 * 它回答"织入会不会造成性能影响"里与机器无关的那一半（另一半是计时型验收，
 * 见 `taboolib.module.incision.perf.WovenInvocationOverheadTest`，默认不跑）：
 *
 * 1. **注入量与方法体规模无关** —— 方法体 10 条指令与 10000 条指令的目标，织入后 Bridge 入口都恰好 1 个、
 *    新增指令数完全相同。这是"织入不是逐指令插桩"的直接证据；一旦退化成按指令插桩，大方法的开销会线性爆炸。
 * 2. **每个目标方法恰好 1 个物理 Bridge 入口，且重复 weave 不叠加** —— Instrumentation 会把多个插件
 *    串联执行，后一个插件拿到前一个插件的输出；若幂等失效，单次调用会触发 N 次 dispatch。
 * 3. **非目标方法逐指令不变** —— 织入不得改动无关代码（否则热路径会因为重排指令而变慢）。
 * 4. **实参数组形状与描述符严格一致** —— 长度常量 == 参数个数、基本类型恰好一次装箱、slot 布局正确。
 *    三者任一出错都会让每次调用多付分配/装箱成本。
 * 5. **只有需要 side-car 方法体的目标才生成 `$$IncisionBodies`** —— 纯 LEAD 目标不得多定义一个类。
 *
 * 注意：[Scalpel.weave] 在 `useJvmtiBaseline = false` 时是纯字节进/字节出，不发回 JVMTI；
 * 但 [BridgeClassLoader] 是全局单例，故各用例使用**唯一 owner**，避免 bodies 判断被其它用例污染。
 */
@DisplayName("Scalpel 热路径结构验收")
class ScalpelHotPathShapeTest {

    // ===== 1. 注入量与方法体规模无关 =====

    @Test
    @DisplayName("方法体 10 条 vs 10000 条指令：各只注入 1 个 Bridge 入口，新增指令数为同一常数")
    fun injectionVolumeIsIndependentOfBodySize() {
        val smallOwner = "taboolib/module/incision/hotpath/SizedSmall"
        val largeOwner = "taboolib/module/incision/hotpath/SizedLarge"
        val smallBodySize = 10
        val largeBodySize = 10_000

        val small = buildSizedTargetBytes(smallOwner, smallBodySize)
        val large = buildSizedTargetBytes(largeOwner, largeBodySize)
        val wovenSmall = weave(smallOwner, small, WORK_DESC)
        val wovenLarge = weave(largeOwner, large, WORK_DESC)

        // 前提校验：两个类只差"方法体长度"这一个变量
        assertEquals(smallBodySize, realInsnCount(small, "work", WORK_DESC), "小方法体指令数应为 $smallBodySize")
        assertEquals(largeBodySize, realInsnCount(large, "work", WORK_DESC), "大方法体指令数应为 $largeBodySize")

        assertEquals(1, dispatchCallCount(wovenSmall, "work", WORK_DESC), "10 条指令的方法体必须恰好 1 个 Bridge 入口")
        assertEquals(1, dispatchCallCount(wovenLarge, "work", WORK_DESC), "10000 条指令的方法体必须恰好 1 个 Bridge 入口")

        val injectedSmall = realInsnCount(wovenSmall, "work", WORK_DESC) - realInsnCount(small, "work", WORK_DESC)
        val injectedLarge = realInsnCount(wovenLarge, "work", WORK_DESC) - realInsnCount(large, "work", WORK_DESC)

        assertTrue(injectedSmall > 0, "织入必须真的注入了指令，实际新增 $injectedSmall 条")
        assertTrue(
            injectedLarge <= MAX_ENTRY_INJECTION_INSNS,
            "入口注入必须是小常数（≤ $MAX_ENTRY_INJECTION_INSNS 条），" +
                "实际 10000 条方法体新增 $injectedLarge 条 —— 说明注入量随方法体规模增长",
        )
        assertEquals(
            injectedSmall, injectedLarge,
            "注入量不得随方法体规模变化：10 条方法体新增 $injectedSmall 条，10000 条方法体新增 $injectedLarge 条",
        )
        // 报告行：便于人工直接读注入量，断言本身不依赖它的打印
        println("入口注入指令数：方法体 $smallBodySize 条 → 新增 $injectedSmall 条；方法体 $largeBodySize 条 → 新增 $injectedLarge 条")
        assertLoadable(wovenLarge)
    }

    // ===== 2. 每目标方法一个入口 + 幂等 =====

    @Test
    @DisplayName("每个目标方法恰好 1 个 Bridge 入口，多次 weave 不叠加（跨插件串联幂等）")
    fun eachTargetMethodHasExactlyOneBridgeEntry() {
        val owner = "taboolib/module/incision/hotpath/EntryIdempotent"
        val targets = listOf(alphaTarget(owner), betaTarget(owner))

        val first = weave(owner, buildTwoTargetBytes(owner), WORK_DESC, targets = targets)
        assertEntryShape(first, owner, "第一次 weave")

        // 模拟第二个插件拿到前一个插件的输出：仍要注册自己的 dispatcher，但不得再写第二个物理入口
        val second = weave(owner, first, WORK_DESC, targets = targets)
        assertEntryShape(second, owner, "第二次 weave")

        val third = weave(owner, second, WORK_DESC, targets = targets)
        assertEntryShape(third, owner, "第三次 weave")

        assertLoadable(third)
    }

    private fun assertEntryShape(bytes: ByteArray, owner: String, stage: String) {
        assertEquals(1, dispatchCallCount(bytes, "alpha", "(I)I"), "$stage 后 alpha 的 Bridge 入口必须恰好 1 个")
        assertEquals(1, dispatchCallCount(bytes, "beta", "(J)J"), "$stage 后 beta 的 Bridge 入口必须恰好 1 个")
        assertEquals(0, dispatchCallCount(bytes, "helper", "(I)I"), "$stage 后非目标方法 helper 不得有 Bridge 入口")
        assertEquals(0, dispatchCallCount(bytes, "<init>", "()V"), "$stage 后构造函数不得有 Bridge 入口")
        // 每个入口必然带一份 dispatch key LDC：key 唯一即入口唯一
        assertEquals(1, ldcCount(bytes, "alpha", "(I)I", "$owner.alpha(I)I@LEAD"), "$stage 后 alpha 的 dispatch key 必须唯一")
        assertEquals(1, ldcCount(bytes, "beta", "(J)J", "$owner.beta(J)J@LEAD"), "$stage 后 beta 的 dispatch key 必须唯一")
    }

    // ===== 3. 非目标方法逐指令不变 =====

    @Test
    @DisplayName("非目标方法指令序列逐条不变，目标方法确实被改写")
    fun nonTargetMethodBodyIsUnchanged() {
        val owner = "taboolib/module/incision/hotpath/UntouchedSibling"
        val original = buildMixedBytes(owner)
        val woven = weave(owner, original, "()I")

        assertEquals(
            instructionSequence(original, "other", "(I)I"),
            instructionSequence(woven, "other", "(I)I"),
            "非目标方法 other 的指令序列必须逐条不变（含跳转/字段/调用指令）",
        )
        assertEquals(
            instructionSequence(original, "<init>", "()V"),
            instructionSequence(woven, "<init>", "()V"),
            "构造函数指令序列必须逐条不变",
        )
        // 反向校验：上面的相等断言不能是"永远相等"的空转
        assertNotEquals(
            instructionSequence(original, "work", "()I"),
            instructionSequence(woven, "work", "()I"),
            "目标方法 work 的指令序列必须发生变化，否则上述相等断言无意义",
        )
        assertEquals(1, dispatchCallCount(woven, "work", "()I"), "目标方法 work 必须恰好 1 个 Bridge 入口")
    }

    // ===== 4. 实参数组形状 =====

    @Test
    @DisplayName("DispatcherEmitter 发射的实参数组：长度常量等于参数个数，每基本类型恰好一次装箱")
    fun emitterArgsArrayShapeMatchesDescriptor() {
        val owner = "taboolib/module/incision/hotpath/ArgsShape"
        val desc = ARGS_DESC
        val site = SiteSpec(
            anchor = Anchor.HEAD,
            kind = AdviceKind.GRAFT,
            target = MethodCoordinate(owner, "work", desc),
            hostMethodDescriptor = desc,
            hostIsStatic = false,
        )
        val insns = DispatcherEmitter.buildDispatch(site).toArray().toList()

        // 固定 5 步骨架：LDC ownerClass · LDC sig · self · args[] · INVOKESTATIC dispatch
        assertEquals(Type.getObjectType(owner), (insns[0] as LdcInsnNode).cst, "第 1 条必须是宿主 Class 常量")
        assertEquals("$owner.work$desc", (insns[1] as LdcInsnNode).cst, "第 2 条必须是 dispatch key")
        assertEquals(Opcodes.ALOAD, insns[2].opcode, "实例方法的 self 必须是 ALOAD 0")
        assertEquals(0, (insns[2] as VarInsnNode).`var`, "self 必须读 slot 0")
        val last = insns.last()
        assertTrue(
            last is MethodInsnNode && last.opcode == Opcodes.INVOKESTATIC &&
                last.owner == BRIDGE_OWNER && last.name == "dispatch" && last.desc == BRIDGE_DISPATCH_DESC,
            "最后一条必须是 IncisionBridge.dispatch 的 INVOKESTATIC，实际: $last",
        )

        assertArgArrayShape(insns, desc, "DispatcherEmitter")
    }

    @Test
    @DisplayName("织入字节码里的实参数组形状与 DispatcherEmitter 一致（长度常量、装箱、slot 布局）")
    fun wovenArgsArrayShapeMatchesDescriptor() {
        val owner = "taboolib/module/incision/hotpath/ArgsShapeWoven"
        val desc = ARGS_DESC
        val woven = weave(owner, buildVoidTargetBytes(owner, desc), desc)
        val insns = methodNode(woven, "work", desc).instructions.toArray().toList()

        assertEquals(1, dispatchCallCount(woven, "work", desc), "目标方法必须恰好 1 个 Bridge 入口")
        assertArgArrayShape(insns, desc, "织入字节码")
        assertLoadable(woven)
    }

    @Test
    @DisplayName("两条发射路径的装箱形态不一致：入口路径 NEW 装箱，site 路径 valueOf 装箱")
    fun boxingStrategyDiffersBetweenEntryAndSitePaths() {
        val owner = "taboolib/module/incision/hotpath/BoxingStrategy"
        val desc = ARGS_DESC
        val site = SiteSpec(
            anchor = Anchor.HEAD,
            kind = AdviceKind.GRAFT,
            target = MethodCoordinate(owner, "work", desc),
            hostMethodDescriptor = desc,
            hostIsStatic = false,
        )
        val emitterStyles = argSections(DispatcherEmitter.buildDispatch(site).toArray().toList()).map { it.boxStyle }
        val woven = weave(owner, buildVoidTargetBytes(owner, desc), desc)
        val wovenStyles = argSections(methodNode(woven, "work", desc).instructions.toArray().toList()).map { it.boxStyle }

        // site 路径走 DispatcherEmitter.boxPrimitive → INVOKESTATIC Xxx.valueOf(...)
        assertEquals(
            listOf(BoxStyle.VALUE_OF, BoxStyle.VALUE_OF, BoxStyle.NONE, BoxStyle.VALUE_OF),
            emitterStyles,
            "site 路径应使用 Xxx.valueOf 装箱（可命中缓存、便于标量替换）",
        )
        // 入口路径走 ASM AdviceAdapter.loadArgArray → GeneratorAdapter.box → NEW Xxx + <init>
        //
        // 这是**现状锚点**，不是"要求保持 NEW 装箱"：入口路径是每次方法调用都要付的成本，
        // 用 NEW 装箱意味着每个基本类型实参每次调用都必然分配一个包装对象（拿不到 Integer 缓存），
        // 统一到 valueOf 是低风险优化。一旦统一，本断言会失败并提示同步更新这份说明。
        assertEquals(
            listOf(BoxStyle.NEW_INSTANCE, BoxStyle.NEW_INSTANCE, BoxStyle.NONE, BoxStyle.NEW_INSTANCE),
            wovenStyles,
            "入口路径当前使用 NEW Xxx + <init> 装箱；此处不一致应被明确记录而不是静默接受",
        )
    }

    /**
     * 实参数组形状断言 —— "发射器产物"与"真实织入产物"共用同一套判据。
     *
     * 期望形状（实参 `(IJLjava/lang/String;D)`、实例方法）：
     * `ICONST_4 · ANEWARRAY Object · [DUP · index · load · box? · AASTORE] × 4 · INVOKESTATIC`
     *
     * 装箱形态不在此断言（两条路径目前不一致，见 [boxingStrategyDiffersBetweenEntryAndSitePaths]）；
     * 这里守的是"每个基本类型恰好装箱一次、引用类型不装箱、slot 与下标顺序正确"。
     */
    private fun assertArgArrayShape(insns: List<AbstractInsnNode>, desc: String, stage: String) {
        val args = Type.getArgumentTypes(desc)
        val arrayInsn = insns.firstOrNull { it is TypeInsnNode && it.opcode == Opcodes.ANEWARRAY }
            ?: throw AssertionError("$stage：未找到 ANEWARRAY")

        assertEquals("java/lang/Object", (arrayInsn as TypeInsnNode).desc, "$stage：实参数组元素类型必须是 Object")
        assertEquals(
            args.size, intConstantOf(arrayInsn.previous),
            "$stage：ANEWARRAY 前的长度常量必须等于描述符参数个数 ${args.size}",
        )

        val sections = argSections(insns).map { it.shape() }
        assertEquals(
            listOf(
                Triple(0, Opcodes.ILOAD, 1) to "java/lang/Integer",
                Triple(1, Opcodes.LLOAD, 2) to "java/lang/Long",
                Triple(2, Opcodes.ALOAD, 4) to null,
                Triple(3, Opcodes.DLOAD, 5) to "java/lang/Double",
            ),
            sections,
            "$stage：每个参数必须按下标顺序读正确 slot，且基本类型恰好一次装箱、引用类型不装箱",
        )
        val primitiveCount = args.count { it.sort != Type.OBJECT && it.sort != Type.ARRAY }
        assertEquals(
            primitiveCount, argSections(insns).count { it.boxOwner != null },
            "$stage：$primitiveCount 个基本类型参数必须各有 1 次装箱",
        )
    }

    // ===== 5. bodies side-car 只在需要时产出 =====

    @Test
    @DisplayName("纯 LEAD 目标不产出 \$\$IncisionBodies，SPLICE 目标必须产出")
    fun onlyBodyKindsProduceBodiesClass() {
        val leadOwner = "taboolib/module/incision/hotpath/BodiesLead"
        val spliceOwner = "taboolib/module/incision/hotpath/BodiesSplice"

        weave(leadOwner, buildSizedTargetBytes(leadOwner, 6), WORK_DESC)
        assertFalse(
            BridgeClassLoader.INSTANCE.hasBodies(BridgeClassLoader.bodiesClassName(leadOwner)),
            "纯 LEAD 目标只需要入口调度，不得多定义一个 \$\$IncisionBodies 类",
        )

        weave(spliceOwner, buildSizedTargetBytes(spliceOwner, 6), WORK_DESC, kinds = setOf(AdviceKind.SPLICE))
        assertTrue(
            BridgeClassLoader.INSTANCE.hasBodies(BridgeClassLoader.bodiesClassName(spliceOwner)),
            "SPLICE 目标需要 side-car 方法体，必须产出 \$\$IncisionBodies",
        )
    }

    @Test
    @DisplayName("带 site 的 GRAFT 目标不产出 \$\$IncisionBodies，且命中处恰好 1 个 Bridge 入口")
    fun siteTargetWithoutBodyKindsDoesNotProduceBodies() {
        val owner = "taboolib/module/incision/hotpath/BodiesGraftSite"
        val coordinate = MethodCoordinate(owner, "read", "()I")
        val site = SiteSpec(
            anchor = Anchor.FIELD_GET,
            ownerPattern = owner,
            namePattern = "value",
            descPattern = "I",
            kind = AdviceKind.GRAFT,
            target = coordinate,
        )
        val woven = weave(
            owner,
            buildFieldReaderBytes(owner),
            "()I",
            targets = listOf(Scalpel.AdviceTargetSpec(coordinate, setOf(AdviceKind.GRAFT), listOf(site))),
        )

        assertEquals(1, dispatchCallCount(woven, "read", "()I"), "site 命中 1 次应产生恰好 1 个 Bridge 入口")
        assertEquals(0, dispatchCallCount(woven, "work", "()I"), "同一类里未被声明的 work 不得被注入")
        assertFalse(
            BridgeClassLoader.INSTANCE.hasBodies(BridgeClassLoader.bodiesClassName(owner)),
            "GRAFT 不属于 bodies 生成集合，不得产出 \$\$IncisionBodies",
        )
        assertLoadable(woven)
    }

    // ===== 目标类构造 =====

    /**
     * `public int work(int)`：方法体真实指令数恰为 [insnCount]（含末尾的 ILOAD + IRETURN）。
     *
     * 填充块 `ILOAD 1 / ICONST_1 / IADD / ISTORE 1` 栈中性，可无限重复；余数用 NOP 补齐。
     */
    private fun buildSizedTargetBytes(owner: String, insnCount: Int): ByteArray {
        require(insnCount >= 6) { "方法体至少要有 6 条指令" }
        val writer = newWriter(owner)
        writeDefaultCtor(writer)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "work", WORK_DESC, null, null).apply {
            visitCode()
            val filler = insnCount - 2
            repeat(filler / 4) {
                visitVarInsn(Opcodes.ILOAD, 1)
                visitInsn(Opcodes.ICONST_1)
                visitInsn(Opcodes.IADD)
                visitVarInsn(Opcodes.ISTORE, 1)
            }
            repeat(filler % 4) { visitInsn(Opcodes.NOP) }
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 两个目标方法（`alpha(I)I` / `beta(J)J`）+ 一个非目标方法（`helper(I)I`）。 */
    private fun buildTwoTargetBytes(owner: String): ByteArray {
        val writer = newWriter(owner)
        writeDefaultCtor(writer)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "alpha", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.IADD)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "beta", "(J)J", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.LLOAD, 2)
            visitInsn(Opcodes.LRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "helper", "(I)I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /**
     * 目标方法 `work()I` + 非目标方法 `other(I)I`（含字段访问、静态调用、条件跳转）+ `value` 字段。
     *
     * `other` 的指令序列要足够"有形状"，才能让"逐指令不变"的断言有判别力。
     */
    private fun buildMixedBytes(owner: String): ByteArray {
        val writer = newWriter(owner)
        writeDefaultCtor(writer)
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "work", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "other", "(I)I", null, null).apply {
            visitCode()
            val elseLabel = Label()
            val endLabel = Label()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, owner, "value", "I")
            visitVarInsn(Opcodes.ILOAD, 1)
            visitInsn(Opcodes.IADD)
            visitVarInsn(Opcodes.ISTORE, 2)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitJumpInsn(Opcodes.IFLE, elseLabel)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitMethodInsn(Opcodes.INVOKESTATIC, owner, "helper", "()I", false)
            visitInsn(Opcodes.IADD)
            visitVarInsn(Opcodes.ISTORE, 2)
            visitJumpInsn(Opcodes.GOTO, endLabel)
            visitLabel(elseLabel)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInsn(Opcodes.ICONST_2)
            visitInsn(Opcodes.IMUL)
            visitVarInsn(Opcodes.ISTORE, 2)
            visitLabel(endLabel)
            visitVarInsn(Opcodes.ILOAD, 2)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC, "helper", "()I", null, null).apply {
            visitCode()
            visitIntInsn(Opcodes.BIPUSH, 7)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** `public void work(...)`：无返回值 + 自定义描述符，用于观察实参数组形状。 */
    private fun buildVoidTargetBytes(owner: String, desc: String): ByteArray {
        val writer = newWriter(owner)
        writeDefaultCtor(writer)
        writer.visitMethod(Opcodes.ACC_PUBLIC, "work", desc, null, null).apply {
            visitCode()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    /** 读取自身 `value:I` 字段的 `read()I`（FIELD_GET site 的宿主方法）+ 无关的 `work()I`。 */
    private fun buildFieldReaderBytes(owner: String): ByteArray {
        val writer = newWriter(owner)
        writeDefaultCtor(writer)
        writer.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd()
        writer.visitMethod(Opcodes.ACC_PUBLIC, "read", "()I", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitFieldInsn(Opcodes.GETFIELD, owner, "value", "I")
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "work", "()I", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_0)
            visitInsn(Opcodes.IRETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun newWriter(owner: String): ClassWriter {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null)
        return writer
    }

    private fun writeDefaultCtor(writer: ClassWriter) {
        writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun alphaTarget(owner: String) =
        Scalpel.AdviceTargetSpec(MethodCoordinate(owner, "alpha", "(I)I"), setOf(AdviceKind.LEAD))

    private fun betaTarget(owner: String) =
        Scalpel.AdviceTargetSpec(MethodCoordinate(owner, "beta", "(J)J"), setOf(AdviceKind.LEAD))

    // ===== 织入与解码 =====

    private fun weave(
        owner: String,
        bytes: ByteArray,
        targetDesc: String,
        kinds: Set<AdviceKind> = setOf(AdviceKind.LEAD),
        targets: List<Scalpel.AdviceTargetSpec>? = null,
    ): ByteArray = Scalpel(
        resolver = NoopResolver,
        targetsByOwner = mapOf(
            owner to (targets ?: listOf(Scalpel.AdviceTargetSpec(MethodCoordinate(owner, "work", targetDesc), kinds))),
        ),
    ).weave(bytes)

    private fun readNode(bytes: ByteArray): ClassNode {
        val node = ClassNode()
        // 跳过 StackMapTable：FrameNode 不是真实指令，混进来会污染"指令序列"比较与逐指令计数
        ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES)
        return node
    }

    private fun methodNode(bytes: ByteArray, name: String, desc: String): MethodNode =
        readNode(bytes).methods.first { it.name == name && it.desc == desc }

    /** 方法体内的**真实指令**条数（opcode >= 0；排除 Label / LineNumber / Frame 等伪指令）。 */
    private fun realInsnCount(bytes: ByteArray, name: String, desc: String): Int =
        methodNode(bytes, name, desc).instructions.toArray().count { it.opcode >= 0 }

    private fun dispatchCallCount(bytes: ByteArray, name: String, desc: String): Int =
        methodNode(bytes, name, desc).instructions.toArray().filterIsInstance<MethodInsnNode>().count {
            it.owner == BRIDGE_OWNER && it.name == "dispatch"
        }

    private fun ldcCount(bytes: ByteArray, name: String, desc: String, constant: String): Int =
        methodNode(bytes, name, desc).instructions.toArray().filterIsInstance<LdcInsnNode>().count { it.cst == constant }

    /**
     * 把方法体规范化为"指令序列"：真实指令按助记符 + 操作数展开，跳转目标与标签按出现顺序统一编号，
     * 得到与字节偏移无关、可逐条比对的形态。
     */
    private fun instructionSequence(bytes: ByteArray, name: String, desc: String): List<String> {
        val labelIds = HashMap<LabelNode, Int>()
        fun labelId(node: LabelNode): Int = labelIds.getOrPut(node) { labelIds.size }
        return methodNode(bytes, name, desc).instructions.toArray().mapNotNull { insn ->
            when (insn) {
                is LabelNode -> "L${labelId(insn)}"
                // 行号属于调试信息，与热路径无关，比较时忽略
                is LineNumberNode -> null
                is VarInsnNode -> "${mnemonic(insn.opcode)} v${insn.`var`}"
                is MethodInsnNode -> "${mnemonic(insn.opcode)} ${insn.owner}.${insn.name}${insn.desc}"
                is FieldInsnNode -> "${mnemonic(insn.opcode)} ${insn.owner}.${insn.name}:${insn.desc}"
                is TypeInsnNode -> "${mnemonic(insn.opcode)} ${insn.desc}"
                is LdcInsnNode -> "${mnemonic(insn.opcode)} ${insn.cst}"
                is IntInsnNode -> "${mnemonic(insn.opcode)} ${insn.operand}"
                is JumpInsnNode -> "${mnemonic(insn.opcode)} L${labelId(insn.label)}"
                is IincInsnNode -> "${mnemonic(insn.opcode)} v${insn.`var`} ${insn.incr}"
                is MultiANewArrayInsnNode -> "${mnemonic(insn.opcode)} ${insn.desc} ${insn.dims}"
                else -> mnemonic(insn.opcode)
            }
        }
    }

    private fun mnemonic(opcode: Int): String =
        if (opcode in Printer.OPCODES.indices) Printer.OPCODES[opcode] else "PSEUDO($opcode)"

    /** 基本类型的装箱形态 —— 两条发射路径目前**不一致**，故显式区分而不是当成同一件事。 */
    private enum class BoxStyle {
        /** 引用类型：不装箱。 */
        NONE,

        /** `INVOKESTATIC Xxx.valueOf(x)` —— 走缓存、可被标量替换。 */
        VALUE_OF,

        /** `NEW Xxx` + `INVOKESPECIAL <init>` —— 每次调用都新建对象。 */
        NEW_INSTANCE,
    }

    /** 一个参数在实参数组里的读写形态。 */
    private data class ArgSection(
        val index: Int,
        val loadOpcode: Int,
        val loadSlot: Int,
        val boxOwner: String?,
        val boxStyle: BoxStyle,
    ) {
        /**
         * 形状投影：**装箱形态**（valueOf / new）不参与通用形状比较 —— 它由
         * [boxingStrategyDiffersBetweenEntryAndSitePaths] 单独锚定。
         */
        fun shape(): Pair<Triple<Int, Int, Int>, String?> = Triple(index, loadOpcode, loadSlot) to boxOwner
    }

    /** 跳过 Label / LineNumber / Frame 等伪指令的顺序读取光标（opcode < 0 即伪指令）。 */
    private class InsnCursor(private val insns: List<AbstractInsnNode>, start: Int) {

        private var index = start

        fun peek(): AbstractInsnNode? {
            while (index < insns.size && insns[index].opcode < 0) index++
            return insns.getOrNull(index)
        }

        fun take(): AbstractInsnNode? = peek()?.also { index++ }
    }

    /**
     * 按 `DUP · index · load · 装箱? · AASTORE` 段落解码实参数组填充代码。
     *
     * 装箱形态识别两种：
     *  - `INVOKESTATIC Xxx.valueOf(...)`（[DispatcherEmitter] 发射的 site 路径）
     *  - `NEW Xxx` + `<init>`（ASM `GeneratorAdapter.box` 发射的入口路径）
     */
    private fun argSections(insns: List<AbstractInsnNode>): List<ArgSection> {
        val result = mutableListOf<ArgSection>()
        val arrayIndex = insns.indexOfFirst { it is TypeInsnNode && it.opcode == Opcodes.ANEWARRAY }
        if (arrayIndex < 0) return result
        val cursor = InsnCursor(insns, arrayIndex + 1)
        while (true) {
            val dup = cursor.peek() ?: break
            if (dup.opcode != Opcodes.DUP) break
            cursor.take()
            val index = intConstantOf(cursor.take()) ?: break
            val load = cursor.take() as? VarInsnNode ?: break
            var boxOwner: String? = null
            var boxStyle = BoxStyle.NONE
            while (true) {
                val node = cursor.peek() ?: return result
                if (node.opcode == Opcodes.AASTORE) {
                    cursor.take()
                    break
                }
                cursor.take()
                when {
                    node is MethodInsnNode && node.opcode == Opcodes.INVOKESTATIC && node.name == "valueOf" -> {
                        boxOwner = node.owner
                        boxStyle = BoxStyle.VALUE_OF
                    }
                    node is TypeInsnNode && node.opcode == Opcodes.NEW -> {
                        boxOwner = node.desc
                        boxStyle = BoxStyle.NEW_INSTANCE
                    }
                }
            }
            result += ArgSection(index, load.opcode, load.`var`, boxOwner, boxStyle)
        }
        return result
    }

    /** 取"压入小整数"指令的字面量（ICONST_* / BIPUSH / SIPUSH / LDC）。 */
    private fun intConstantOf(insn: AbstractInsnNode?): Int? = when (insn) {
        is InsnNode -> when (insn.opcode) {
            Opcodes.ICONST_M1 -> -1
            Opcodes.ICONST_0 -> 0
            Opcodes.ICONST_1 -> 1
            Opcodes.ICONST_2 -> 2
            Opcodes.ICONST_3 -> 3
            Opcodes.ICONST_4 -> 4
            Opcodes.ICONST_5 -> 5
            else -> null
        }
        is IntInsnNode -> insn.operand
        is LdcInsnNode -> insn.cst as? Int
        else -> null
    }

    private fun assertLoadable(bytes: ByteArray) {
        assertDoesNotThrow { ByteClassLoader().define(readNode(bytes).name.replace('/', '.'), bytes) }
    }

    private companion object {
        const val BRIDGE_OWNER = "io/izzel/incision/bridge/IncisionBridge"
        const val BRIDGE_DISPATCH_DESC =
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"

        /** 单参数实例方法 `work(I)I` 的描述符。 */
        const val WORK_DESC = "(I)I"

        /** 混合基本类型与引用类型的实参描述符，用于校验实参数组形状。 */
        const val ARGS_DESC = "(IJLjava/lang/String;D)V"

        /**
         * 入口注入的指令数上限哨兵。
         *
         * 实际形态固定为
         * `LDC · LDC · self · [长度常量 · ANEWARRAY · 每参数(DUP·下标·load·[box]·AASTORE)] · INVOKESTATIC · POP`，
         * 单参数方法为 12 条。上限只用于在"退化成逐指令插桩"时立刻报警，不参与其它判断。
         */
        const val MAX_ENTRY_INJECTION_INSNS = 16
    }

    private class ByteClassLoader : ClassLoader(ScalpelHotPathShapeTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }
}
