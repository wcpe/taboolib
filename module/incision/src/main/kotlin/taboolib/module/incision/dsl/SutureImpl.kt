package taboolib.module.incision.dsl

import taboolib.module.incision.api.MethodCoordinate
import taboolib.module.incision.api.Suture
import taboolib.module.incision.runtime.AdviceEntry
import taboolib.module.incision.runtime.AdviceKind
import taboolib.module.incision.runtime.SurgeryRegistry
import taboolib.module.incision.runtime.TheatreDispatcher
import kotlin.reflect.KClass

/**
 * Suture 默认实现 — 持有一组 advice 条目；heal 必须先恢复物理织入，再移除 dispatcher。
 */
internal class SutureImpl(
    override val id: String,
    override val targets: List<MethodCoordinate>,
    override val holder: KClass<*>,
    private val entries: List<AdviceEntry>,
) : Suture {

    @Volatile
    override var state: Suture.State = Suture.State.ARMED

    override fun heal(): Boolean {
        if (state == Suture.State.HEALED) return false
        // 先重算字节码再移除 handler；若物理回滚失败，保留当前 suture，避免留下必然 dispatch 失败的半卸载状态。
        if (!Scalpel.removeWeaver(entries)) return false
        for (e in entries) {
            TheatreDispatcher.unregister(e.target, e.id)
        }
        SurgeryRegistry.unregister(id)
        state = Suture.State.HEALED
        return true
    }

    override fun suspend(): Boolean {
        if (state == Suture.State.HEALED) return false
        for (e in entries) e.enabled = false
        state = Suture.State.SUSPENDED
        return true
    }

    override fun resume(): Boolean {
        if (state == Suture.State.HEALED) return false
        for (e in entries) e.enabled = true
        state = Suture.State.ARMED
        return true
    }
}
