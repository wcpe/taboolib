package taboolib.module.incision.dsl

import taboolib.module.incision.annotation.SurgeryDesk
import taboolib.module.incision.api.Suture
import taboolib.module.incision.diagnostic.Forensics
import taboolib.module.incision.diagnostic.Trauma
import taboolib.module.incision.loader.Backend
import taboolib.module.incision.loader.InstrumentationBackend
import taboolib.module.incision.loader.JvmtiBackend
import taboolib.module.incision.runtime.AdviceEntry
import taboolib.module.incision.runtime.SurgeryRegistry
import taboolib.module.incision.runtime.TheatreDispatcher
import taboolib.module.incision.remap.RemapRouter
import taboolib.module.incision.weaver.Scalpel as ScalpelWeaver
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * DSL 顶层入口 —
 *
 * ```
 * @SurgeryDesk
 * object MyPatches {
 *     val tracker: Suture by scalpel {
 *         lead("org.bukkit.entity.Player#kickPlayer(*)") { theatre -> ... }
 *     }
 *
 *     fun onDemand() {
 *         scalpel.transient {
 *             splice("...") { ... }
 *         }.use { runBackup() }
 *     }
 * }
 * ```
 */
object Scalpel {

    /**
     * 运行时拼接可避免 Shadow 把系统属性键当作 taboolib.* 类名重定位。
     *
     * 该键是 JVM 启动协议而不是类坐标，插件重定位后仍必须保持 taboolib.incision.backend 不变。
     */
    private val backendProperty =
        String(charArrayOf('t', 'a', 'b', 'o', 'o', 'l', 'i', 'b')) + ".incision.backend"

    operator fun invoke(block: ScalpelBuilder.() -> Unit): ScalpelProvider =
        ScalpelProvider(block, deferred = false, eagerArm = true)

    /** 惰性切 — 属性首次被访问或目标类加载时物理织入 */
    fun deferred(block: ScalpelBuilder.() -> Unit): ScalpelProvider =
        ScalpelProvider(block, deferred = true, eagerArm = false)

    /** 作用域切 — 在块内生效，块外自动 heal */
    fun scoped(block: ScalpelBuilder.() -> Unit): ScopedHandle {
        verifyCallerIsSurgeryDesk("scoped")
        return ScopedHandle(block)
    }

    /** 线程局部切 — 默认 disable，按线程手动 activate */
    fun threadLocal(block: ScalpelBuilder.() -> Unit): ThreadLocalSuture {
        verifyCallerIsSurgeryDesk("threadLocal")
        return ThreadLocalSuture(block)
    }

    /**
     * 事件驱动 — 监听到 [eventClass] 实例时才 arm advice。
     * 真正的事件订阅由调用方在 SurgeryDesk 内自行接 TabooLib `@SubscribeEvent`，
     * 此处仅返回一个 [ArmTrigger]，由用户主动 [ArmTrigger.arm] / [ArmTrigger.disarm]。
     *
     * 设计上：incision 不强行把 Bukkit 事件耦合进核心，避免 platform 依赖污染。
     */
    fun armOn(eventClass: Class<*>, block: ScalpelBuilder.() -> Unit): ArmTrigger {
        verifyCallerIsSurgeryDesk("armOn")
        return ArmTrigger(eventClass, block, defaultArmed = false)
    }

    fun disarmOn(eventClass: Class<*>, block: ScalpelBuilder.() -> Unit): ArmTrigger {
        verifyCallerIsSurgeryDesk("disarmOn")
        return ArmTrigger(eventClass, block, defaultArmed = true)
    }

    /**
     * 互斥块 — 块内的切术替换同一目标的其他活跃切术；块结束后恢复。
     * 实现：进入时 suspend 同 target 上其他 ARMED suture，退出时 resume。
     */
    fun <R> exclusive(block: ScalpelBuilder.() -> Unit, body: () -> R): R {
        verifyCallerIsSurgeryDesk("exclusive")
        val suture = transient(block)
        val touched = mutableListOf<taboolib.module.incision.api.Suture>()
        try {
            for (t in suture.targets) {
                for (other in SurgeryRegistry.listByTarget(t)) {
                    if (other === suture) continue
                    if (other.state == taboolib.module.incision.api.Suture.State.ARMED) {
                        if (other.suspend()) touched += other
                    }
                }
            }
            return body()
        } finally {
            for (s in touched) s.resume()
            suture.heal()
        }
    }

    /** 复制句柄结构用于 A/B — 用同一 builder 再注册一份独立的临时 suture */
    fun fork(suture: Suture, mark: String = "fork"): Suture? {
        verifyCallerIsSurgeryDesk("fork")
        // fork 仅复制声明，不触发原 suture 的 advice
        Forensics.warn("scalpel.fork(${suture.id}) — 当前实现仅复制 id 标记 '$mark'，请用 transient { } 重写副本")
        return null
    }

    /** A/B 重放 — 等价于把 suture 的 advice 复制到一个临时 suture */
    fun replay(suture: Suture): Suture? = fork(suture, "replay")

    /** 全局查询 */
    fun find(id: String): Suture? = SurgeryRegistry.find(id)

    /** 列出所有切术；支持按 holder 或 target 过滤 */
    fun list(
        holder: kotlin.reflect.KClass<*>? = null,
        target: String? = null,
    ): List<Suture> = SurgeryRegistry.list().filter { s ->
        (holder == null || s.holder == holder) &&
            (target == null || s.targets.any { it.signature.contains(target) })
    }

    /** 按 scope 子串卸载所有匹配的 suture */
    fun healAll(scope: String? = null): Int {
        val targets = SurgeryRegistry.list().filter {
            scope == null || it.id.contains(scope) || it.targets.any { t -> t.signature.contains(scope) }
        }
        var n = 0
        for (s in targets) if (s.heal()) n++
        return n
    }

    /** 压缩 — 移除所有 HEALED 状态的孤儿条目（当前 SurgeryRegistry 已自动清，留为占位） */
    fun compact(): Int {
        // SurgeryRegistry.unregister 已在 heal 时调用，无需额外 compact；预留接口
        return 0
    }

    /** 临时切 — 返回 AutoCloseable，调用点必须在 @SurgeryDesk object 内部 */
    fun transient(block: ScalpelBuilder.() -> Unit): Suture {
        val caller = verifyCallerIsSurgeryDesk("transient")
        val seq = TransientCounter.next()
        val id = "${caller.name}#anon-$seq"
        val builder = ScalpelBuilder().apply(block)
        val holder = try {
            caller.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        } catch (t: Throwable) {
            throw Trauma.Declaration.InvalidHolder(caller.name, "transient 调用点必须在 object 中")
        }
        val (targets, entries) = builder.materialize(id, holder!!)
        for (e in entries) TheatreDispatcher.register(e)
        installWeaver(entries)
        val kclass = caller.kotlin
        val suture = SutureImpl(id, targets, kclass, entries)
        SurgeryRegistry.register(id, suture)
        return suture
    }

    /**
     * 安装字节码 weaver — 把目标 owner 注册到 InstrumentationBackend，
     * 触发一次 retransform，使已加载的目标类被注入 dispatcher 调用。
     *
     * 同一 owner 多次注册会累积所有 entries 的 weaver；retransform 是幂等的。
     */
    /**
     * 每个运行时 owner 的当前有效声明，键必须是 AdviceEntry.id。
     * 只保存折叠后的 AdviceTargetSpec 无法在 heal 时辨认应删除哪条 advice，会把旧织入永久留在类里。
     */
    private val activeEntries = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentHashMap<String, AdviceEntry>>()
    private val activeTokens = java.util.concurrent.ConcurrentHashMap<String, Backend.BackendToken>()

    @Synchronized
    internal fun installWeaver(entries: List<AdviceEntry>) {
        if (entries.isEmpty()) return
        // 物理 JVM 入口由 SurgeonScanner 的 KotlinTarget 解析显式生成；DSL 坐标则保持调用方原意。
        // 后端不得无条件复制 `$Companion`，否则普通方法会多出幽灵计划，maxMatches 也会失真。
        val expanded = entries
        val backend = resolveBackend()
        val byOwner = expanded.groupBy { it.target.owner }
        for ((owner, group) in byOwner) {
            // NMS remap：把用户写的 net/minecraft/server/MinecraftServer
            // 转换为运行时实际类名 net/minecraft/server/v1_12_R1/MinecraftServer
            val resolvedOwner = RemapRouter.resolveOwner(owner)
            val ownerEntries = activeEntries.computeIfAbsent(resolvedOwner) { java.util.concurrent.ConcurrentHashMap() }
            val previousEntries = HashMap(ownerEntries)
            group.forEach { ownerEntries[it.id] = it }
            activeTokens.remove(resolvedOwner)?.remove()
            val targets = buildRuntimeTargets(resolvedOwner, ownerEntries.values.toList())
            val weaver = ScalpelWeaver(
                targetsByOwner = mapOf(resolvedOwner to targets),
                useJvmtiBaseline = backend === JvmtiBackend,
            )
            val installation = backend.install(resolvedOwner) { bytes -> weaver.weave(bytes) }
            val token = installation.token
            if (installation.status !in setOf(Backend.InstallStatus.INSTALLED, Backend.InstallStatus.PENDING_LOAD) || token == null) {
                ownerEntries.clear()
                ownerEntries.putAll(previousEntries)
                if (previousEntries.isNotEmpty()) {
                    val previousTargets = buildRuntimeTargets(resolvedOwner, previousEntries.values.toList())
                    val previousWeaver = ScalpelWeaver(
                        targetsByOwner = mapOf(resolvedOwner to previousTargets),
                        useJvmtiBaseline = backend === JvmtiBackend,
                    )
                    val restored = backend.install(resolvedOwner) { bytes -> previousWeaver.weave(bytes) }
                    restored.token?.takeIf {
                        restored.status == Backend.InstallStatus.INSTALLED || restored.status == Backend.InstallStatus.PENDING_LOAD
                    }?.let { activeTokens[resolvedOwner] = it }
                    Forensics.warn("installWeaver 已尝试恢复前一合法计划: owner=$resolvedOwner status=${restored.status}")
                }
                Forensics.warn("installWeaver 回滚: owner=$owner resolved=$resolvedOwner backend=${backend.name} status=${installation.status} reason=${installation.reason}")
                continue
            }
            activeTokens[resolvedOwner] = token
            syncRuntimeAliases(resolvedOwner, ownerEntries.values.toList())
            Forensics.debug("installWeaver status=${installation.status} owner=$owner resolved=$resolvedOwner advices=${group.size} total=${ownerEntries.size} backend=${backend.name}")
        }
    }

    /**
     * 从 active plan 删除指定 entry，并从 JVM 原始基线按剩余计划重算类定义。
     * 任何 owner 重装失败都会恢复调用前快照，避免 dispatcher 已卸载而字节码仍持有旧回调。
     */
    @Synchronized
    internal fun removeWeaver(entries: List<AdviceEntry>): Boolean {
        if (entries.isEmpty()) return true
        val backend = resolveBackend()
        val snapshots = LinkedHashMap<String, Map<String, AdviceEntry>>()
        val grouped = entries.groupBy { RemapRouter.resolveOwner(it.target.owner) }
        for ((owner, removing) in grouped) {
            val ownerEntries = activeEntries[owner] ?: continue
            snapshots[owner] = HashMap(ownerEntries)
            removing.forEach { ownerEntries.remove(it.id) }
            if (!reinstallOwner(owner, ownerEntries.values.toList(), backend)) {
                snapshots.forEach { (rollbackOwner, snapshot) ->
                    val rollbackEntries = activeEntries.computeIfAbsent(rollbackOwner) { java.util.concurrent.ConcurrentHashMap() }
                    rollbackEntries.clear()
                    rollbackEntries.putAll(snapshot)
                    reinstallOwner(rollbackOwner, snapshot.values.toList(), backend)
                }
                Forensics.warn("removeWeaver 回滚: owner=$owner ids=${removing.map { it.id }}")
                return false
            }
        }
        return true
    }

    /** 单 owner 重装；空计划必须主动 retransform，才能把最后一层织入恢复为 JVM 基线。 */
    private fun reinstallOwner(owner: String, entries: List<AdviceEntry>, backend: Backend): Boolean {
        activeTokens.remove(owner)?.remove()
        if (entries.isEmpty()) {
            activeEntries.remove(owner)
            return backend.isClassLoaded(owner) == false || backend.retransform(owner.replace('/', '.'))
        }
        val targets = buildRuntimeTargets(owner, entries)
        val weaver = ScalpelWeaver(
            targetsByOwner = mapOf(owner to targets),
            useJvmtiBaseline = backend === JvmtiBackend,
        )
        val installation = backend.install(owner) { bytes -> weaver.weave(bytes) }
        val token = installation.token ?: return false
        if (installation.status !in setOf(Backend.InstallStatus.INSTALLED, Backend.InstallStatus.PENDING_LOAD)) return false
        activeTokens[owner] = token
        syncRuntimeAliases(owner, entries)
        return true
    }

    /** 把逻辑声明统一翻译为运行时坐标；宿主与 Site 必须经过同一条映射链。 */
    private fun buildRuntimeTargets(resolvedOwner: String, entries: List<AdviceEntry>): List<ScalpelWeaver.AdviceTargetSpec> {
        return entries.groupBy { it.target }.map { (target, targetEntries) ->
            val runtimeTarget = resolveRuntimeTarget(resolvedOwner, target)
            ScalpelWeaver.AdviceTargetSpec(
                target = runtimeTarget,
                kinds = targetEntries.map { it.kind }.toSet(),
                sites = targetEntries.mapNotNull { it.siteSpec }.map { site ->
                    val logicalOwner = site.ownerPattern
                    if (logicalOwner.isBlank()) {
                        site.copy(target = runtimeTarget, descPattern = RemapRouter.resolveDescriptor(site.descPattern))
                    } else if (site.matchMode == taboolib.module.incision.annotation.MatchMode.GLOB) {
                        // owner/name 含通配符时无法查询成员映射表，只递归转换 descriptor 中的确定类型。
                        site.copy(target = runtimeTarget, descPattern = RemapRouter.resolveDescriptor(site.descPattern))
                    } else {
                        val resolvedSiteOwner = RemapRouter.resolveOwner(logicalOwner)
                        val (siteName, siteDesc) = when (site.anchor) {
                            taboolib.module.incision.api.Anchor.FIELD_GET,
                            taboolib.module.incision.api.Anchor.FIELD_PUT -> RemapRouter.resolveField(logicalOwner, site.namePattern, site.descPattern)
                            taboolib.module.incision.api.Anchor.NEW -> site.namePattern to RemapRouter.resolveDescriptor(site.descPattern)
                            else -> RemapRouter.resolveMethod(logicalOwner, site.namePattern, site.descPattern)
                        }
                        site.copy(
                            target = runtimeTarget,
                            ownerPattern = resolvedSiteOwner,
                            namePattern = siteName,
                            descPattern = siteDesc,
                        )
                    }
                },
            )
        }
    }

    /** 后端确认接受计划后才发布别名，失败安装不得留下一个永远不会被字节码调用的幽灵 chain。 */
    private fun syncRuntimeAliases(resolvedOwner: String, entries: List<AdviceEntry>) {
        entries.groupBy { it.target }.forEach { (logicalTarget, targetEntries) ->
            val runtimeTarget = resolveRuntimeTarget(resolvedOwner, logicalTarget)
            TheatreDispatcher.registerRuntimeAlias(runtimeTarget, targetEntries)
        }
    }

    /** 宿主坐标只允许经过这一条解析链，保证 weave key、Bridge route 与 dispatcher alias 完全一致。 */
    private fun resolveRuntimeTarget(resolvedOwner: String, target: taboolib.module.incision.api.MethodCoordinate): taboolib.module.incision.api.MethodCoordinate {
        val (resolvedName, resolvedDescriptor) = RemapRouter.resolveMethod(target.owner, target.name, target.descriptor)
        return target.copy(owner = resolvedOwner, name = resolvedName, descriptor = resolvedDescriptor)
    }

    /**
     * 插件卸载边界：移除全部 transformer 与累计计划，避免下一 ClassLoader 再次叠加旧织入。
     */
    fun shutdown() {
        val backend = resolveBackend()
        activeTokens.forEach { (owner, token) ->
            runCatching { token.remove() }
            // 移除最后一个聚合 transformer 后重新转换，恢复 JVM 的原始定义基线。
            runCatching { backend.retransform(owner.replace('/', '.')) }
        }
        activeTokens.clear()
        activeEntries.clear()
    }

    private fun resolveBackend(): Backend {
        // backend 不是按 Java 主版本硬编码：同一 JVM 是否允许动态 attach 取决于启动参数和发行版。
        // auto 先选标准 Instrumentation，再选无需 attach 的 JVMTI；测试矩阵可显式强制其中之一。
        when (System.getProperty(backendProperty, "auto").lowercase()) {
            "instrumentation" -> return InstrumentationBackend
            "jvmti" -> return JvmtiBackend
        }
        if (InstrumentationBackend.available()) return InstrumentationBackend
        if (JvmtiBackend.available()) return JvmtiBackend
        Forensics.warn("无可用的 retransform 后端，织入可能失败")
        return InstrumentationBackend
    }

    /**
     * 校验调用方是 @SurgeryDesk object。
     * 返回调用方 Class。
     */
    private fun verifyCallerIsSurgeryDesk(api: String): Class<*> {
        val stack = Thread.currentThread().stackTrace
        var firstExternal: String? = null
        for (i in 2 until stack.size) {
            val name = stack[i].className
            if (name.startsWith("taboolib.module.incision.")) continue
            // skip kotlin lambda/anonymous classes (contain $) and JDK internals
            val baseName = name.substringBefore('$')
            val cls = try {
                Class.forName(baseName, false, Thread.currentThread().contextClassLoader)
            } catch (_: Throwable) {
                try { Class.forName(baseName, false, Scalpel::class.java.classLoader) } catch (_: Throwable) { null }
            } ?: continue
            if (cls.getAnnotation(SurgeryDesk::class.java) != null) return cls
            if (firstExternal == null) firstExternal = name
        }
        throw Trauma.IllegalCallSite(
            "scalpel.$api 只能在 @SurgeryDesk object 内部调用，实际在 ${firstExternal ?: "unknown"}",
            stack.take(10).map { it.toString() }
        )
    }
}

/**
 * provideDelegate 工厂 — 属性委托创建时捕获 thisRef 与 property.name，注册到 SurgeryRegistry。
 */
class ScalpelProvider internal constructor(
    private val block: ScalpelBuilder.() -> Unit,
    private val deferred: Boolean,
    private val eagerArm: Boolean,
) {
    operator fun provideDelegate(thisRef: Any, property: KProperty<*>): ReadOnlyProperty<Any, Suture> {
        val clazz = requireSurgeryDesk(thisRef, property.name)
        val id = "${clazz.name}#${property.name}"
        val builder = ScalpelBuilder().apply(block)
        val (targets, entries) = builder.materialize(id, thisRef)
        val suture = SutureImpl(id, targets, clazz.kotlin, entries)
        SurgeryRegistry.register(id, suture)
        if (!deferred && eagerArm) {
            for (e in entries) TheatreDispatcher.register(e)
            Scalpel.installWeaver(entries)
        }
        Forensics.debug("declared id=$id deferred=$deferred targets=${targets.size}")
        return ReadOnlyProperty { _, _ -> suture }
    }
}

private object TransientCounter {
    private val counter = java.util.concurrent.atomic.AtomicLong(0)
    fun next(): Long = counter.incrementAndGet()
}
