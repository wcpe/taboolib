package taboolib.module.incision.bridge

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.objectweb.asm.Type

/**
 * canonical Bridge 私有字段访问回归。
 *
 * Side-car body 与宿主不是 nestmate，不能直接访问 private 字段；Bridge 必须在没有 JVMTI
 * native 的环境下同时支持实例和静态字段，供多个隔离插件共享同一个稳定入口。
 */
@DisplayName("IncisionBridge side-car 字段访问")
class IncisionBridgeFieldAccessTest {

    @Test
    fun `reads and writes private fields without native backend`() {
        val fixture = PrivateFieldFixture()
        val owner = PrivateFieldFixture::class.java

        assertEquals(7, IncisionBridge.accessFieldGet(fixture, owner, "value", "I"))
        IncisionBridge.accessFieldSet(fixture, owner, "value", "I", 19)
        assertEquals(19, IncisionBridge.accessFieldGet(fixture, owner, "value", "I"))

        val staticDescriptor = Type.getDescriptor(String::class.java)
        assertEquals("initial", IncisionBridge.accessStaticFieldGet(owner, "shared", staticDescriptor))
        IncisionBridge.accessStaticFieldSet(owner, "shared", staticDescriptor, "changed")
        assertEquals("changed", IncisionBridge.accessStaticFieldGet(owner, "shared", staticDescriptor))
    }

    private class PrivateFieldFixture {
        private var value: Int = 7

        companion object {
            private var shared: String = "initial"
        }
    }
}
