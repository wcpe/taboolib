package taboolib.module.incision.runtime

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import taboolib.module.incision.api.MethodCoordinate
import java.util.concurrent.atomic.AtomicInteger

/** 验证 NMS remap 后字节码使用运行时签名时仍能命中逻辑声明的 advice。 */
@DisplayName("TheatreDispatcher 运行时坐标别名")
class TheatreDispatcherRuntimeAliasTest {

    @Test
    @DisplayName("运行时签名与逻辑签名不同时只执行一次")
    fun dispatchesRemappedRuntimeAlias() {
        CanonicalBridge.bind(IncisionBridge::class.java)
        val calls = AtomicInteger()
        val logical = MethodCoordinate("net/minecraft/Logical", "logicalName", "()V")
        val runtime = MethodCoordinate("net/minecraft/Runtime", "runtimeName", "()V")
        val entry = AdviceEntry(
            id = "runtime-alias-test",
            kind = AdviceKind.LEAD,
            target = logical,
            priority = 0,
            handler = { calls.incrementAndGet(); null },
        )

        try {
            TheatreDispatcher.register(entry)
            TheatreDispatcher.registerRuntimeAlias(runtime, listOf(entry))

            TheatreDispatcher.dispatch("${runtime.signature}@LEAD", null, emptyArray<Any?>())

            assertEquals(1, calls.get())
        } finally {
            TheatreDispatcher.unregister(logical, entry.id)
            TheatreDispatcher.clear()
            IncisionBridge.unregisterLocalDispatcher(TheatreDispatcher::class.java.classLoader)
        }
    }
}
