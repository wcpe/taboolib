package taboolib.module.incision.loader

import org.tabooproject.reflex.ClassMethod
import org.tabooproject.reflex.LazyEnum
import org.tabooproject.reflex.ReflexClass
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import taboolib.common.Inject
import taboolib.common.LifeCycle
import taboolib.common.inject.ClassVisitor
import taboolib.common.platform.Awake
import taboolib.module.incision.annotation.Bypass
import taboolib.module.incision.annotation.Excise
import taboolib.module.incision.annotation.Graft
import taboolib.module.incision.annotation.InsnPattern
import taboolib.module.incision.annotation.KotlinTarget
import taboolib.module.incision.annotation.Lead
import taboolib.module.incision.annotation.Op
import taboolib.module.incision.annotation.Operation
import taboolib.module.incision.annotation.Pointcut
import taboolib.module.incision.annotation.PatternMode
import taboolib.module.incision.annotation.Selector
import taboolib.module.incision.annotation.SelectorKind
import taboolib.module.incision.annotation.MatchMode
import taboolib.module.incision.annotation.Site
import taboolib.module.incision.annotation.Splice
import taboolib.module.incision.annotation.Step
import taboolib.module.incision.annotation.Surgeon
import taboolib.module.incision.annotation.Trail
import taboolib.module.incision.annotation.Trim
import taboolib.module.incision.annotation.Version
import taboolib.module.incision.api.Anchor
import taboolib.module.incision.api.DescriptorCodec
import taboolib.module.incision.api.VersionMatchers

import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.api.Theatre
import taboolib.module.incision.diagnostic.Forensics
import taboolib.module.incision.diagnostic.Trauma
import taboolib.module.incision.dsl.SutureImpl
import taboolib.module.incision.dsl.Scalpel
import taboolib.module.incision.pred.AdviceCtx
import taboolib.module.incision.pred.PredCompiler
import taboolib.module.incision.pred.Predicate
import taboolib.module.incision.runtime.AdviceEntry
import taboolib.module.incision.runtime.AdviceKind
import taboolib.module.incision.runtime.SurgeryRegistry
import taboolib.module.incision.runtime.TheatreDispatcher
import taboolib.module.incision.remap.RemapRouter
import taboolib.module.incision.weaver.site.SiteSpec
import taboolib.module.incision.weaver.site.pattern.InsnStep
import taboolib.module.incision.weaver.site.pattern.SitePattern
import taboolib.module.incision.weaver.site.matcher.InsnStepMatcher
import taboolib.module.incision.weaver.site.matcher.OpcodeSeqMatcher

/**
 * @Surgeon 扫描器 —— 在 CONST 阶段扫描所有标注 @Surgeon 的 object，
 * 并且优先级早于 `ClassVisitorAwake(CONST)`，以便尽量覆盖宿主插件自己的 `@Awake(CONST)` 行为。
 * 把注解方法翻译为 advice 并注册到 TheatreDispatcher，同时触发字节码织入。
 */
@Inject
@Awake
class SurgeonScanner : ClassVisitor((-1).toByte()) {

    override fun getLifeCycle(): LifeCycle = LifeCycle.CONST

    override fun visitEnd(clazz: ReflexClass) {
        if (!clazz.hasAnnotation(Surgeon::class.java)) return
        val instance = findInstance(clazz) ?: run {
            Forensics.warn("@Surgeon ${clazz.name} 无法获取实例 (要求标注在 object 上)")
            return
        }
        val surgeon = clazz.getAnnotationIfPresent(Surgeon::class.java)!!
        val defaultPriority = surgeon.property("priority", 0)

        // 每个方法单独组一个 Suture —— id = clazz.name#operationIdSuffix@i
        // aggregate entries for weaver install + 聚合调试
        val aggregateEntries = mutableListOf<AdviceEntry>()
        val holderKClass = Class.forName(clazz.name).kotlin
        val seenSutureIds = HashSet<String>()

        for ((i, m) in clazz.structure.methods.withIndex()) {
            // 版本范围过滤 —— 标 @Version 且当前版本不在区间内的 advice 直接跳过，
            // 既不进 dispatcher 也不安装 weaver。
            if (m.isAnnotationPresent(Version::class.java)) {
                val ver = m.getAnnotation(Version::class.java)
                val start = ver.property("start", "")
                val end = ver.property("end", "")
                val matcherType = ver.properties()["matcher"]
                val definingLoader = Class.forName(clazz.name).classLoader
                val matcher = when (matcherType) {
                    is kotlin.reflect.KClass<*> -> VersionMatchers.resolve(matcherType as kotlin.reflect.KClass<out taboolib.module.incision.api.VersionMatcher>)
                    is Class<*> -> VersionMatchers.resolve(matcherType as Class<out taboolib.module.incision.api.VersionMatcher>)
                    else -> VersionMatchers.resolve(normalizeClassReference(matcherType), definingLoader)
                }
                if (!matcher.matches(start, end)) {
                    Forensics.debug("@Surgeon ${clazz.name}#${m.name} 因 @Version([$start, $end]) 跳过 (current=${matcher.current()})")
                    continue
                }
            }
            val operation = if (m.isAnnotationPresent(Operation::class.java)) m.getAnnotation(Operation::class.java) else null
            val methodPriority = operation?.property("priority", defaultPriority) ?: defaultPriority
            val methodEnabled = operation?.property("enabled", true) ?: true
            val methodIdSuffix = operation?.property("id", "")?.takeIf { it.isNotBlank() } ?: m.name
            // Suture id 形如 `owner#suffix@idx`：保留 @idx 确保同类内多方法共用同名 id 时互不覆盖
            val sutureId = "${clazz.name}#$methodIdSuffix@$i"
            val built = buildEntries(sutureId, methodPriority, methodEnabled, clazz, instance, m)
            if (built.isEmpty()) continue

            for (e in built) TheatreDispatcher.register(e)
            aggregateEntries += built
            val suture = SutureImpl(sutureId, built.map { it.target }.distinct(), holderKClass, built)
            try {
                SurgeryRegistry.register(sutureId, suture)
                seenSutureIds += sutureId
            } catch (t: Trauma.Declaration.DuplicateId) {
                Forensics.warn("@Surgeon 重复注册被跳过: $sutureId")
            }
        }
        if (aggregateEntries.isEmpty()) return
        // weaver 按类粒度一次性安装即可（内部按 owner 分组织入）
        Scalpel.installWeaver(aggregateEntries)
        Forensics.debug("Surgeon 扫描: ${clazz.name} → ${seenSutureIds.size} suture / ${aggregateEntries.size} advice")
    }

    private fun buildEntries(
        id: String,
        priority: Int,
        enabled: Boolean,
        owner: ReflexClass,
        instance: Any,
        m: ClassMethod,
    ): List<AdviceEntry> {
        val declaration = parseAdviceDeclaration(m) ?: return emptyList()
        // min/maxMatches 约束逻辑 pointcut 命中；KotlinTarget 产生的物理入口不应被重复计数。
        if (declaration.targets.size !in declaration.minMatches..declaration.maxMatches) {
            Forensics.warn("@Surgeon pointcut 命中数越界: ${owner.name}#${m.name} actual=${declaration.targets.size} expected=${declaration.minMatches}..${declaration.maxMatches}")
            return emptyList()
        }
        val aliases = declaration.targets.flatMap { expandTargets(it, m) }.distinct()
        val selectedAliases = if (declaration.patternDeclared) {
            aliases.filter { methodContainsPattern(it, declaration.insnSteps, declaration.patternMode) }
        } else aliases
        if (selectedAliases.isEmpty()) {
            Forensics.debug("@Surgeon pattern 未命中，跳过 advice: ${owner.name}#${m.name}")
            return emptyList()
        }
        val handler: (Theatre) -> Any? = buildHandler(owner, instance, m)
        val classLoader = Class.forName(owner.name).classLoader
        val cl = java.lang.ref.WeakReference(classLoader)
        // 若 InsnPattern steps 非空，把 OpcodeSeq 注入 siteSpec.pattern（recording 路径消费）。
        // Pattern 只负责扫描期筛选宿主方法；织入仍沿原 advice/Site 单路径执行，禁止额外生成 site advice。
        val siteSpecWithPattern = declaration.siteSpec
        // predicate 是安全选择边界：声明错误必须拒绝注册，不能降级为无过滤。
        val compiledPred: Predicate? = declaration.where.takeIf { it.isNotBlank() }?.let { src ->
            try {
                PredCompiler.compile(src, AdviceCtx(adviceId = id, classLoader = classLoader))
            } catch (t: Throwable) {
                Forensics.warn("@Surgeon predicate 编译失败并拒绝注册: $src (${owner.name}#${m.name}) - ${t.message}")
                return emptyList()
            }
        }
        return selectedAliases.mapIndexed { index, target ->
            val entryId = if (selectedAliases.size == 1) id else "$id#$index"
            AdviceEntry(
                id = entryId,
                kind = declaration.kind,
                target = target,
                priority = priority,
                handler = handler,
                classLoader = cl,
                explicitResumeRequired = declaration.explicitResumeRequired,
                sourceKind = "annotation",
                aliasGroup = id,
                siteSpec = siteSpecWithPattern?.copy(target = target, adviceId = entryId),
                compiledPredicate = compiledPred,
                predicateSource = declaration.where.ifBlank { null },
                enabled = enabled,
                onThrow = declaration.onThrow,
            )
        }
    }

    private fun parseAdviceDeclaration(m: ClassMethod): AdviceDeclaration? {
        if (m.isAnnotationPresent(Lead::class.java)) {
            val ann = m.getAnnotation(Lead::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            return AdviceDeclaration(
                kind = AdviceKind.LEAD,
                targets = pointcut.targets,
                minMatches = pointcut.minMatches,
                maxMatches = pointcut.maxMatches,
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
            )
        }
        if (m.isAnnotationPresent(Trail::class.java)) {
            val ann = m.getAnnotation(Trail::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            return AdviceDeclaration(
                kind = AdviceKind.TRAIL,
                targets = pointcut.targets, minMatches = pointcut.minMatches, maxMatches = pointcut.maxMatches,
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
                onThrow = ann.property("onThrow", true),
            )
        }
        if (m.isAnnotationPresent(Splice::class.java)) {
            val ann = m.getAnnotation(Splice::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            return AdviceDeclaration(
                kind = AdviceKind.SPLICE,
                targets = pointcut.targets, minMatches = pointcut.minMatches, maxMatches = pointcut.maxMatches,
                explicitResumeRequired = true,
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
            )
        }
        if (m.isAnnotationPresent(Bypass::class.java)) {
            val ann = m.getAnnotation(Bypass::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), supportsMethodAlias = true, allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            val site = toSiteAnnotation(ann.properties()["site"], Site(Anchor.HEAD))
            return AdviceDeclaration(
                kind = AdviceKind.BYPASS,
                targets = pointcut.targets, minMatches = pointcut.minMatches, maxMatches = pointcut.maxMatches,
                siteSpec = if (isWholeMethodBypass(site, ann.properties()["pattern"])) null else toSiteSpec(site, AdviceKind.BYPASS),
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
            )
        }
        if (m.isAnnotationPresent(Excise::class.java)) {
            val ann = m.getAnnotation(Excise::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            return AdviceDeclaration(
                kind = AdviceKind.EXCISE,
                targets = pointcut.targets, minMatches = pointcut.minMatches, maxMatches = pointcut.maxMatches,
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
            )
        }
        if (m.isAnnotationPresent(Graft::class.java)) {
            val ann = m.getAnnotation(Graft::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), supportsMethodAlias = true, allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            val site = toSiteAnnotation(ann.properties()["site"], Site(Anchor.HEAD))
            return AdviceDeclaration(
                kind = AdviceKind.GRAFT,
                targets = pointcut.targets, minMatches = pointcut.minMatches, maxMatches = pointcut.maxMatches,
                siteSpec = toSiteSpec(site, AdviceKind.GRAFT),
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
            )
        }
        if (m.isAnnotationPresent(Trim::class.java)) {
            val ann = m.getAnnotation(Trim::class.java)
            val pointcut = readAdvicePointcut(ann.properties(), supportsMethodAlias = true, allowKotlinPhysicalTarget = m.isAnnotationPresent(KotlinTarget::class.java)) ?: return null
            val trimKind = runCatching { Trim.Kind.valueOf(ann.enumName("kind", Trim.Kind.RETURN.name)) }
                .getOrDefault(Trim.Kind.RETURN)
            // TRIM 默认锚点按 kind 决定：RETURN → Anchor.RETURN（每个 IRETURN/.../ARETURN 之前栈顶就是返回值）；
            // ARG / VAR → Anchor.HEAD（从方法头部开始读 LV slot 替换）。否则 HEAD 上 TRIM RETURN 会在空栈
            // 上 DUP，立刻 "Cannot pop operand off an empty stack"。
            val defaultAnchor = if (trimKind == Trim.Kind.RETURN) Anchor.RETURN else Anchor.HEAD
            val site = toSiteAnnotation(ann.properties()["site"], Site(defaultAnchor))
            val trimIndex = ann.property("index", 0)
            val host = pointcut.targets.first()
            val argDesc = if (trimKind == Trim.Kind.ARG) splitJvmArgDescriptors(host.descriptor.substringAfter('(').substringBefore(')')).getOrNull(trimIndex).orEmpty() else ""
            val baseSpec = toSiteSpec(site, AdviceKind.TRIM)
            val retDesc = if (trimKind == Trim.Kind.RETURN) deriveTrimReturnDesc(baseSpec) else ""
            return AdviceDeclaration(
                kind = AdviceKind.TRIM,
                targets = pointcut.targets, minMatches = pointcut.minMatches, maxMatches = pointcut.maxMatches,
                siteSpec = baseSpec.copy(
                    trimKind = trimKind,
                    trimIndex = trimIndex,
                    trimArgDescriptor = argDesc,
                    trimReturnDescriptor = retDesc,
                ),
                insnSteps = readInsnSteps(ann.properties()["pattern"]), patternMode = readPatternMode(ann.properties()["pattern"]),
                patternDeclared = hasInsnPattern(ann.properties()["pattern"]),
                where = ann.property("predicate", ""),
            )
        }
        return null
    }

    /**
     * 静态推断 TRIM RETURN 模式下栈顶值的 JVM 描述符：
     *  - INVOKE 锚点：取 [SiteSpec.descPattern] 返回段（"...)X" 中的 X）
     *  - FIELD_GET 锚点：[SiteSpec.descPattern] 即字段类型，直接用
     *  - 其它（含 RETURN / HEAD / TAIL）：返回空，由 SiteWeaver.applyPlan 用宿主方法返回类型补齐
     */
    private fun deriveTrimReturnDesc(spec: SiteSpec): String {
        return when (spec.anchor) {
            Anchor.INVOKE -> {
                val desc = spec.descPattern
                val close = desc.indexOf(')')
                if (close in 0 until desc.length - 1) desc.substring(close + 1) else ""
            }
            Anchor.FIELD_GET -> spec.descPattern
            else -> ""
        }
    }

    /**
     * 从 `owner#name(argTypes)retType` 中提取第 [index] 个参数的 JVM 描述符。
     * 解析失败时返回空串（emitter 会保守跳过）。
     */
    private fun extractArgDescriptor(rawMethod: String, index: Int): String {
        val parsed = DescriptorCodec.parseMethod(rawMethod) ?: return ""
        val desc = parsed.descriptor
        val open = desc.indexOf('(')
        val close = desc.indexOf(')')
        if (open < 0 || close < 0 || close <= open) return ""
        val argsDesc = desc.substring(open + 1, close)
        val args = splitJvmArgDescriptors(argsDesc)
        return args.getOrNull(index) ?: ""
    }

    /** 把 `Ljava/lang/String;ILjava/util/List;[I` 拆成 [`Ljava/lang/String;`, `I`, `Ljava/util/List;`, `[I`]。 */
    private fun splitJvmArgDescriptors(args: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < args.length) {
            val start = i
            while (i < args.length && args[i] == '[') i++
            when (val c = args.getOrNull(i)) {
                'L' -> {
                    val semi = args.indexOf(';', i)
                    if (semi < 0) return out
                    out += args.substring(start, semi + 1)
                    i = semi + 1
                }
                'V', 'Z', 'B', 'S', 'I', 'J', 'F', 'D', 'C' -> {
                    out += args.substring(start, i + 1)
                    i++
                }
                null -> return out
                else -> {
                    out += c.toString()
                    i++
                }
            }
        }
        return out
    }

    /**
     * 把 @InsnPattern（注解或运行时 Map 形式）读成 [InsnStep] 列表。
     * 兼容两种 Reflex 返回形态：
     *  - 原生注解 [InsnPattern]（kotlin-reflect 直接取到）
     *  - `Map<String, Any>` 包含 `steps` 键（Reflex 展开形态）
     */
    private fun readInsnSteps(raw: Any?): List<InsnStep> {
        val stepsArr: Any? = when (raw) {
            null -> return emptyList()
            is InsnPattern -> raw.steps
            is Map<*, *> -> raw["steps"]
            else -> return emptyList()
        } ?: return emptyList()
        val list = mutableListOf<InsnStep>()
        when (stepsArr) {
            is Array<*> -> stepsArr.forEach { s -> readOneStep(s)?.let(list::add) }
            is Iterable<*> -> stepsArr.forEach { s -> readOneStep(s)?.let(list::add) }
            else -> Unit
        }
        require(list.all { it.repeat > 0 }) { "InsnPattern repeat 必须大于 0" }
        return list
    }

    private fun hasInsnPattern(raw: Any?): Boolean = when (raw) {
        null -> false
        is InsnPattern -> raw.steps.isNotEmpty()
        is Map<*, *> -> readInsnSteps(raw).isNotEmpty()
        else -> false
    }

    /** Reflex 可能返回真实注解或 Map；两条读取路径必须保持完全相同的默认值。 */
    private fun readPatternMode(raw: Any?): PatternMode = when (raw) {
        is InsnPattern -> raw.mode
        is Map<*, *> -> runCatching { PatternMode.valueOf(enumNameOf(raw["mode"], PatternMode.CONTIGUOUS.name)) }
            .getOrDefault(PatternMode.CONTIGUOUS)
        else -> PatternMode.CONTIGUOUS
    }

    private fun readOneStep(raw: Any?): InsnStep? {
        return when (raw) {
            null -> null
            is Step -> InsnStep(
                opcode = raw.opcode.opcode,
                ownerFilter = raw.owner,
                nameFilter = raw.name,
                descFilter = raw.desc,
                cstFilter = raw.cst,
                repeat = raw.repeat,
            )
            is Map<*, *> -> {
                val opName = enumNameOf(raw["opcode"], Op.ANY.name)
                val op = runCatching { Op.valueOf(opName) }.getOrDefault(Op.ANY)
                InsnStep(
                    opcode = op.opcode,
                    ownerFilter = raw["owner"]?.toString() ?: "",
                    nameFilter = raw["name"]?.toString() ?: "",
                    descFilter = raw["desc"]?.toString() ?: "",
                    cstFilter = raw["cst"]?.toString() ?: "",
                    repeat = (raw["repeat"] as? Number)?.toInt() ?: 1,
                )
            }
            else -> null
        }
    }

    private fun toSiteSpec(site: Site, kind: AdviceKind): SiteSpec {
        require(site.minMatches >= 0 && (site.maxMatches < 0 || site.maxMatches >= site.minMatches)) {
            "非法 Site 命中约束 ${site.minMatches}..${site.maxMatches}"
        }
        val selector = site.target
        val ownerPattern = selector.owner.replace('.', '/')
        val namePattern = selector.name
        val descPattern = selector.descriptor
        val target = MethodCoordinate(ownerPattern, namePattern, descPattern)
        // 保留逻辑 Site 坐标与匹配模式，便于诊断“声明未解析”和“运行时零命中”。
        Forensics.debug("Site selector: anchor=${site.anchor} mode=${selector.matchMode} target=${target.signature} ordinal=${site.ordinal} matches=${site.minMatches}..${site.maxMatches}")
        return SiteSpec(
            anchor = site.anchor,
            ownerPattern = ownerPattern,
            namePattern = namePattern,
            descPattern = descPattern,
            matchMode = selector.matchMode,
            shift = site.shift,
            ordinal = site.ordinal,
            minMatches = site.minMatches,
            maxMatches = site.maxMatches,
            kind = kind,
            target = target,
            offset = site.offset,
        )
    }

    private fun toSiteAnnotation(raw: Any?, fallback: Site): Site {
        return when (raw) {
            null -> fallback
            is Site -> raw
            is Map<*, *> -> {
                val anchorName = enumNameOf(raw["anchor"], fallback.anchor.name)
                val shiftName = enumNameOf(raw["shift"], fallback.shift.name)
                val ordinal = (raw["ordinal"] as? Number)?.toInt() ?: fallback.ordinal
                val offset = (raw["offset"] as? Number)?.toInt() ?: fallback.offset
                Site(
                    anchor = runCatching { Anchor.valueOf(anchorName) }.getOrDefault(fallback.anchor),
                    target = readSelector(raw["target"]) ?: fallback.target,
                    shift = runCatching { taboolib.module.incision.api.Shift.valueOf(shiftName) }.getOrDefault(fallback.shift),
                    ordinal = ordinal,
                    offset = offset,
                    minMatches = (raw["minMatches"] as? Number)?.toInt() ?: fallback.minMatches,
                    maxMatches = (raw["maxMatches"] as? Number)?.toInt() ?: fallback.maxMatches,
                )
            }
            else -> fallback
        }
    }

    /** Reflex 的 enum 注解值是 [LazyEnum]（toString 不返回 enum name）。统一抽 name；非 enum 类型走 toString。 */
    private fun enumNameOf(raw: Any?, default: String): String = when (raw) {
        null -> default
        is LazyEnum -> raw.name
        else -> raw.toString()
    }

    /** Reflex 会把注解中的 Class/KClass 属性表示为 `Lpkg/Type;`，加载前必须还原为 FQCN。 */
    private fun normalizeClassReference(raw: Any?): String {
        val value = raw?.toString().orEmpty().trim()
        return if (value.startsWith('L') && value.endsWith(';')) {
            value.substring(1, value.length - 1).replace('/', '.')
        } else value.replace('/', '.')
    }

    private fun expandTargets(primary: MethodCoordinate, m: ClassMethod): List<MethodCoordinate> {
        if (!m.isAnnotationPresent(KotlinTarget::class.java)) return listOf(primary)
        val kt = m.getAnnotation(KotlinTarget::class.java)
        val companionInstance = kt.property("companionInstance", false)
        val jvmStaticBridge = kt.property("jvmStaticBridge", false)
        val companionSuffix = "$" + "Companion"
        val outerOwner = primary.owner.removeSuffix(companionSuffix)
        val companionOwner = outerOwner + companionSuffix
        val out = linkedSetOf<MethodCoordinate>()
        // 两个开关分别表示要织入的真实 JVM 入口；只有都开启时才同时产生两条物理坐标。
        if (jvmStaticBridge) out += MethodCoordinate(outerOwner, primary.name, primary.descriptor)
        if (companionInstance) out += MethodCoordinate(companionOwner, primary.name, primary.descriptor)
        if (out.isEmpty()) out += primary
        return out.toList()
    }

    /**
     * 构建 handler —— 要求用户方法签名为 `fun(theatre: Theatre): R`。
     */
    private fun buildHandler(owner: ReflexClass, instance: Any, m: ClassMethod): (Theatre) -> Any? {
        val cls = Class.forName(owner.name)
        val jmethod = cls.declaredMethods.firstOrNull { it.name == m.name && it.parameterCount == 1 } ?: run {
            Forensics.warn("@Surgeon 无法定位方法 ${owner.name}#${m.name}，期望签名 fun(Theatre)")
            return { _ -> null }
        }
        jmethod.isAccessible = true
        return { theatre ->
            try {
                jmethod.invoke(instance, theatre)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.cause ?: e
            }
        }
    }

    private fun extractMethodDescriptor(scope: String): String {
        val trimmed = scope.trim()
        if ('&' !in trimmed && '|' !in trimmed && !trimmed.startsWith("!") && !trimmed.startsWith("(")) {
            return trimmed.removePrefix("method:").removePrefix("class:").trim()
        }
        val idx = trimmed.indexOf("method:")
        if (idx < 0) return trimmed
        var i = idx + "method:".length
        var depth = 0
        var stop = false
        while (i < trimmed.length) {
            when (trimmed[i]) {
                '(' -> depth++
                ')' -> if (depth > 0) depth--
                '&', '|' -> if (depth == 0) stop = true
                else -> Unit
            }
            if (stop) break
            i++
        }
        return trimmed.substring(idx + "method:".length, i).trim()
    }

    private fun isWholeMethodBypass(site: Site, rawPattern: Any?): Boolean {
        return site.anchor == Anchor.HEAD &&
            site.target.kind == SelectorKind.NONE &&
            site.shift == taboolib.module.incision.api.Shift.BEFORE &&
            // HEAD 只有一个物理锚点，因此 v2 默认 ordinal=0 与旧式 -1 都表示整方法替换。
            site.ordinal in setOf(-1, 0) &&
            site.offset == 0 &&
            readInsnSteps(rawPattern).isEmpty()
    }

    private data class AdviceDeclaration(
        val kind: AdviceKind,
        val targets: List<MethodCoordinate>,
        val minMatches: Int,
        val maxMatches: Int,
        val explicitResumeRequired: Boolean = false,
        val siteSpec: SiteSpec? = null,
        val insnSteps: List<InsnStep> = emptyList(),
        val patternDeclared: Boolean = false,
        val patternMode: PatternMode = PatternMode.CONTIGUOUS,
        val where: String = "",
        val onThrow: Boolean = true,
    )

    private data class CompiledPointcut(
        val targets: List<MethodCoordinate>,
        val minMatches: Int,
        val maxMatches: Int,
    )

    /**
     * 兼容选择规则固定为 scope > method 别名 > pointcut。
     *
     * 非空 scope 与 pointcut 不做交集或并集：旧 Scope 的命名空间推断和 v2 Selector 的显式命名空间
     * 不可安全混算，明确优先级才能让已有插件升级后保持原行为。
     */
    private fun readAdvicePointcut(
        properties: Map<String, Any?>,
        supportsMethodAlias: Boolean = false,
        allowKotlinPhysicalTarget: Boolean = false,
    ): CompiledPointcut? {
        val scope = properties["scope"]?.toString().orEmpty().trim()
        val method = if (supportsMethodAlias) properties["method"]?.toString().orEmpty().trim() else ""
        val legacy = scope.ifBlank { method }
        if (legacy.isNotBlank()) {
            if (hasDeclaredPointcut(properties["pointcut"])) {
                Forensics.warn("advice 同时声明 scope/method 与 pointcut；按兼容规则使用方案一并忽略 pointcut: $legacy")
            }
            val descriptor = extractMethodDescriptor(legacy)
            val parsed = DescriptorCodec.parseMethod(descriptor)
            if (parsed == null) {
                Forensics.warn("旧 Scope 无法提取宿主方法并拒绝注册: $legacy")
                return null
            }
            return CompiledPointcut(listOf(parsed.toCoordinate()), 1, 1)
        }
        return readPointcut(properties["pointcut"], allowKotlinPhysicalTarget)
    }

    /** 默认空 Pointcut 不算冲突；只有实际包含 selector 时才警告。 */
    private fun hasDeclaredPointcut(raw: Any?): Boolean = when (raw) {
        is Pointcut -> raw.allOf.isNotEmpty() || raw.anyOf.isNotEmpty() || raw.noneOf.isNotEmpty()
        is Map<*, *> -> readSelectorList(raw["allOf"]).isNotEmpty() ||
            readSelectorList(raw["anyOf"]).isNotEmpty() || readSelectorList(raw["noneOf"]).isNotEmpty()
        else -> false
    }

    /**
     * 把结构化 pointcut 编译成唯一的宿主方法集合。
     *
     * CLASS/METHOD 提供有限候选边界，FIELD 只过滤这些候选方法的字段访问；因此 noneOf 永远不会
     * 对整个 JVM 类空间求补集。所有 selector 先转成运行时坐标，再在同一 IR 上执行布尔组合。
     */
    private fun readPointcut(raw: Any?, allowKotlinPhysicalTarget: Boolean): CompiledPointcut? {
        val allOf: List<Selector>
        val anyOf: List<Selector>
        val noneOf: List<Selector>
        val minMatches: Int
        val maxMatches: Int
        when (raw) {
            is Pointcut -> {
                allOf = raw.allOf.toList(); anyOf = raw.anyOf.toList(); noneOf = raw.noneOf.toList()
                minMatches = raw.minMatches; maxMatches = raw.maxMatches
            }
            is Map<*, *> -> {
                allOf = readSelectorList(raw["allOf"]); anyOf = readSelectorList(raw["anyOf"]); noneOf = readSelectorList(raw["noneOf"])
                minMatches = (raw["minMatches"] as? Number)?.toInt() ?: 1
                maxMatches = (raw["maxMatches"] as? Number)?.toInt() ?: 1
            }
            else -> return null
        }
        require(minMatches >= 0 && (maxMatches < 0 || maxMatches >= minMatches)) {
            "非法 pointcut 命中约束 $minMatches..$maxMatches"
        }
        val positives = allOf + anyOf
        val boundaries = positives.filter { it.kind == SelectorKind.CLASS || it.kind == SelectorKind.METHOD }
        require(boundaries.isNotEmpty()) { "pointcut 必须包含正向 CLASS 或 METHOD selector" }
        require((positives + noneOf).none { it.kind == SelectorKind.NONE }) { "pointcut 不允许 NONE selector" }

        val runtimeAll = allOf.map(::resolveSelector)
        val runtimeAny = anyOf.map(::resolveSelector)
        val runtimeNone = noneOf.map(::resolveSelector)
        val runtimeBoundaries = boundaries.map(::resolveSelector)
        // 单一精确 METHOD 已经是完整、可验证的待加载坐标；反射枚举整类既没有增加信息，
        // 还会被该类其他无关方法引用的缺失 NMS 类型拖垮。实际存在性由事务 weave/verify 确认。
        val exactDirect = runtimeBoundaries.singleOrNull()?.takeIf {
            positives.size == 1 && runtimeNone.isEmpty() && it.kind == SelectorKind.METHOD &&
                it.matchMode == MatchMode.EXACT && it.owner.isNotBlank() &&
                it.name.isNotBlank() && it.descriptor.startsWith("(")
        }
        if (exactDirect != null) {
            return CompiledPointcut(
                listOf(MethodCoordinate(exactDirect.owner, exactDirect.name, exactDirect.descriptor)),
                minMatches,
                if (maxMatches < 0) Int.MAX_VALUE else maxMatches,
            )
        }
        val classes = expandBoundaryClasses(runtimeBoundaries)
        val fieldAccesses = HashMap<Class<*>, Map<MethodCoordinate, List<RuntimeSelector>>>()
        val targets = linkedSetOf<MethodCoordinate>()
        for (hostClass in classes) {
            val ownerName = hostClass.name.replace('.', '/')
            // 外部插件的某个无关方法可能引用当前服务端不存在的旧 NMS 类型，导致
            // Class#getDeclaredMethods 整体抛 NoClassDefFoundError。精确 METHOD 仍可按声明坐标安装，
            // 因此这里只放弃枚举，不得让一个坏签名阻断同类其他可织入方法。
            val methods = runCatching {
                hostClass.declaredMethods
                    .filterNot { java.lang.reflect.Modifier.isAbstract(it.modifiers) || java.lang.reflect.Modifier.isNative(it.modifiers) }
                    .map { MethodCoordinate(ownerName, it.name, Type.getMethodDescriptor(it)) }
            }.onFailure {
                Forensics.warn("pointcut 无法枚举 ${hostClass.name} 全部方法，将仅保留精确 METHOD: ${it.javaClass.simpleName}: ${it.message}")
            }.getOrDefault(emptyList())
            for (method in methods) {
                val fieldSelectors = { fieldAccesses.getOrPut(hostClass) { readFieldAccesses(hostClass) }[method].orEmpty() }
                val allHit = runtimeAll.all { selectorMatchesMethod(it, method, fieldSelectors) }
                val anyHit = runtimeAny.isEmpty() || runtimeAny.any { selectorMatchesMethod(it, method, fieldSelectors) }
                val noneHit = runtimeNone.none { selectorMatchesMethod(it, method, fieldSelectors) }
                if (allHit && anyHit && noneHit) targets += method
            }
        }

        // 精确 METHOD 可以安全登记为待加载坐标；CLASS/FIELD/GLOB 必须看到真实字节码才能展开。
        if (targets.isEmpty()) {
            val exactMethods = runtimeBoundaries.filter {
                it.kind == SelectorKind.METHOD && it.matchMode == MatchMode.EXACT &&
                    it.owner.isNotBlank() && it.name.isNotBlank() && it.descriptor.startsWith("(")
            }
            if (exactMethods.size == 1 && positives.size == 1 && noneOf.isEmpty()) {
                // Companion 方法并不存在于声明中的外部类；先保留逻辑坐标，随后由 KotlinTarget
                // 展开真实 JVM 入口，命中约束仍只计算一次逻辑目标。
                exactMethods.forEach { targets += MethodCoordinate(it.owner, it.name, it.descriptor) }
            }
        }
        return CompiledPointcut(targets.toList(), minMatches, if (maxMatches < 0) Int.MAX_VALUE else maxMatches)
    }

    /**
     * 声明坐标统一交给 NMSProxy 同源 resolver 自动识别 Mojang、Spigot、旧版本号包名。
     * 非 NMS 坐标由 resolver 原样返回；这里只翻译坐标，绝不能重映射整份服务端 class。
     */
    private fun resolveSelector(selector: Selector): RuntimeSelector {
        val owner = selector.owner.replace('.', '/')
        if (owner.isBlank() || selector.matchMode == MatchMode.GLOB) {
            // 通配符本身无法查映射表；descriptor 中的确定类型仍可安全递归翻译。
            return RuntimeSelector(selector.kind, owner, selector.name, RemapRouter.resolveDescriptor(selector.descriptor), selector.matchMode)
        }
        val runtimeOwner = RemapRouter.resolveOwner(owner)
        val (runtimeName, runtimeDesc) = when (selector.kind) {
            SelectorKind.METHOD -> RemapRouter.resolveMethod(owner, selector.name, selector.descriptor)
            SelectorKind.FIELD -> RemapRouter.resolveField(owner, selector.name, selector.descriptor)
            else -> selector.name to RemapRouter.resolveDescriptor(selector.descriptor)
        }
        return RuntimeSelector(selector.kind, runtimeOwner, runtimeName, runtimeDesc, selector.matchMode)
    }

    private fun expandBoundaryClasses(boundaries: List<RuntimeSelector>): List<Class<*>> {
        val loaded = InstrumentationBackend.loadedClasses()
        val out = LinkedHashSet<Class<*>>()
        for (selector in boundaries) {
            if (selector.owner.isBlank()) continue
            if (selector.matchMode == MatchMode.GLOB) {
                loaded.filterTo(out) { globMatches(selector.owner, it.name.replace('.', '/')) }
                continue
            }
            val binaryName = selector.owner.replace('/', '.')
            loaded.filterTo(out) { it.name == binaryName }
            if (out.none { it.name == binaryName }) {
                sequenceOf(Thread.currentThread().contextClassLoader, SurgeonScanner::class.java.classLoader, ClassLoader.getSystemClassLoader())
                    .filterNotNull()
                    .mapNotNull { runCatching { Class.forName(binaryName, false, it) }.getOrNull() }
                    .firstOrNull()
                    ?.let(out::add)
            }
        }
        return out.toList()
    }

    private fun selectorMatchesMethod(
        selector: RuntimeSelector,
        method: MethodCoordinate,
        fieldAccesses: () -> List<RuntimeSelector>,
    ): Boolean = when (selector.kind) {
        SelectorKind.CLASS -> matches(selector, method.owner, "", "")
        SelectorKind.METHOD -> matches(selector, method.owner, method.name, method.descriptor)
        SelectorKind.FIELD -> fieldAccesses().any { access ->
            fieldMatches(selector.owner, access.owner, selector.matchMode) &&
                fieldMatches(selector.name, access.name, selector.matchMode) &&
                fieldMatches(selector.descriptor, access.descriptor, selector.matchMode)
        }
        SelectorKind.NONE -> false
    }

    private fun matches(selector: RuntimeSelector, owner: String, name: String, descriptor: String): Boolean =
        fieldMatches(selector.owner, owner, selector.matchMode) &&
            fieldMatches(selector.name, name, selector.matchMode) &&
            fieldMatches(selector.descriptor, descriptor, selector.matchMode)

    /** 空字段表示该维度不参与过滤；GLOB 的星号和问号覆盖整个字符串。 */
    private fun fieldMatches(pattern: String, value: String, mode: MatchMode): Boolean = when {
        pattern.isEmpty() -> true
        mode == MatchMode.EXACT -> pattern == value
        else -> globMatches(pattern, value)
    }

    private fun globMatches(pattern: String, value: String): Boolean {
        val regex = buildString(pattern.length * 2) {
            append('^')
            for (char in pattern) when (char) {
                '*' -> append(".*")
                '?' -> append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> append('\\').append(char)
                else -> append(char)
            }
            append('$')
        }
        return Regex(regex).matches(value)
    }

    private fun readFieldAccesses(hostClass: Class<*>): Map<MethodCoordinate, List<RuntimeSelector>> {
        val resourceName = "/${hostClass.name.replace('.', '/')}.class"
        val bytes = hostClass.getResourceAsStream(resourceName)?.use { it.readBytes() } ?: return emptyMap()
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return node.methods.associate { method ->
            val coordinate = MethodCoordinate(node.name, method.name, method.desc)
            val accesses = method.instructions.iterator().asSequence()
                .filterIsInstance<FieldInsnNode>()
                .map { RuntimeSelector(SelectorKind.FIELD, it.owner, it.name, it.desc, MatchMode.EXACT) }
                .toList()
            coordinate to accesses
        }
    }

    /**
     * 在宿主原始方法上执行一次 pattern 选择。这里不生成 emission，因此 pattern 只能决定 advice 是否注册，
     * 不会像旧实现那样在方法入口和匹配 Site 各执行一次。
     */
    private fun methodContainsPattern(target: MethodCoordinate, steps: List<InsnStep>, mode: PatternMode): Boolean {
        if (steps.isEmpty()) return true
        val binaryName = target.owner.replace('/', '.')
        val hostClass = InstrumentationBackend.loadedClasses().firstOrNull { it.name == binaryName }
            ?: sequenceOf(Thread.currentThread().contextClassLoader, SurgeonScanner::class.java.classLoader)
                .filterNotNull()
                .mapNotNull { runCatching { Class.forName(binaryName, false, it) }.getOrNull() }
                .firstOrNull()
            ?: return false
        val resource = "/${target.owner}.class"
        val bytes = hostClass.getResourceAsStream(resource)?.use { it.readBytes() } ?: return false
        val node = ClassNode()
        ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val method = node.methods.firstOrNull { it.name == target.name && it.desc == target.descriptor } ?: return false
        val views = method.instructions.iterator().asSequence()
            .filter { it.opcode >= 0 }
            .map { insn ->
                when (insn) {
                    is MethodInsnNode -> OpcodeSeqMatcher.InsnView(insn.opcode, insn.owner, insn.name, insn.desc)
                    is FieldInsnNode -> OpcodeSeqMatcher.InsnView(insn.opcode, insn.owner, insn.name, insn.desc)
                    is TypeInsnNode -> OpcodeSeqMatcher.InsnView(insn.opcode, insn.desc)
                    is LdcInsnNode -> OpcodeSeqMatcher.InsnView(insn.opcode, cst = insn.cst)
                    is IntInsnNode -> OpcodeSeqMatcher.InsnView(insn.opcode, cst = insn.operand)
                    else -> OpcodeSeqMatcher.InsnView(insn.opcode)
                }
            }.toList()
        return patternExists(views, steps, mode)
    }

    private fun patternExists(
        events: List<OpcodeSeqMatcher.InsnView>,
        steps: List<InsnStep>,
        mode: PatternMode,
    ): Boolean {
        for (start in events.indices) {
            var cursor = start
            var ok = true
            for (step in steps) {
                repeat(step.repeat) {
                    if (!ok) return@repeat
                    if (mode == PatternMode.CONTIGUOUS) {
                        if (cursor >= events.size || !InsnStepMatcher.matches(step, events[cursor])) ok = false else cursor++
                    } else {
                        while (cursor < events.size && !InsnStepMatcher.matches(step, events[cursor])) cursor++
                        if (cursor >= events.size) ok = false else cursor++
                    }
                }
                if (!ok) break
            }
            if (ok) return true
        }
        return false
    }

    private data class RuntimeSelector(
        val kind: SelectorKind,
        val owner: String,
        val name: String,
        val descriptor: String,
        val matchMode: MatchMode,
    )

    private fun readSelectorList(raw: Any?): List<Selector> = when (raw) {
        is Array<*> -> raw.mapNotNull(::readSelector)
        is Iterable<*> -> raw.mapNotNull(::readSelector)
        else -> emptyList()
    }

    private fun readSelector(raw: Any?): Selector? = when (raw) {
        is Selector -> raw
        is Map<*, *> -> Selector(
            kind = runCatching { SelectorKind.valueOf(enumNameOf(raw["kind"], SelectorKind.NONE.name)) }.getOrDefault(SelectorKind.NONE),
            owner = raw["owner"]?.toString().orEmpty(),
            name = raw["name"]?.toString().orEmpty(),
            descriptor = raw["descriptor"]?.toString().orEmpty(),
            matchMode = runCatching { taboolib.module.incision.annotation.MatchMode.valueOf(enumNameOf(raw["matchMode"], "EXACT")) }.getOrDefault(taboolib.module.incision.annotation.MatchMode.EXACT),
        )
        else -> null
    }
}
