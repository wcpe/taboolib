package taboolib.module.incision.bridge

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 覆盖 Leaf 服务端类和第三方插件类均无法用 defining loader 找到切术声明方的场景。
 */
@DisplayName("IncisionBridge 多 lease 目标签名路由")
class IncisionBridgeTargetRoutingTest {

    @Test
    @DisplayName("两个隔离 lease 存在时按目标签名选择 dispatcher")
    fun routesByTargetInsteadOfOwnerLoader() {
        val fixtureName = BridgeFixtureDispatcher::class.java.name
        val fixturePath = fixtureName.replace('.', '/') + ".class"
        val bytes = BridgeFixtureDispatcher::class.java.classLoader
            .getResourceAsStream(fixturePath)!!.use { it.readBytes() }
        val loaderA = FixtureLoader(fixtureName, bytes)
        val loaderB = FixtureLoader(fixtureName, bytes)
        val dispatcherA = Class.forName(fixtureName, true, loaderA)
        val dispatcherB = Class.forName(fixtureName, true, loaderB)
        val targetA = "example/Target.method()Ljava/lang/Object;"
        val targetB = "example/Other.method()Ljava/lang/Object;"

        try {
            dispatcherA.getMethod("configure", String::class.java).invoke(null, targetA)
            dispatcherB.getMethod("configure", String::class.java).invoke(null, targetB)
            IncisionBridge.registerLocalDispatcher(dispatcherA)
            IncisionBridge.registerLocalDispatcher(dispatcherB)
            IncisionBridge.registerLocalTarget(dispatcherA, targetA)
            IncisionBridge.registerLocalTarget(dispatcherB, targetB)

            val result = IncisionBridge.dispatch(dispatcherB, "$targetA@SPLICE", null, emptyArray<Any?>())

            assertSame(loaderA, result)

            // 兼容尚未调用 registerLocalTarget 的旧插件：owner loader 不属于任一 lease 时广播快照。
            val legacyTarget = "legacy/Target.method()Ljava/lang/Object;"
            dispatcherA.getMethod("configure", String::class.java).invoke(null, legacyTarget)
            val legacyResult = IncisionBridge.dispatch(
                IncisionBridgeTargetRoutingTest::class.java,
                "$legacyTarget@SPLICE",
                null,
                emptyArray<Any?>(),
            )
            assertSame(loaderA, legacyResult)

            IncisionBridge.bindSystemHost(FakeHost())
            IncisionBridge.unregisterLocalDispatcher(loaderA)
            assertEquals(1, IncisionBridge.localLeaseCount())
            assertTrue(IncisionBridge.hasSystemHost())
            IncisionBridge.unregisterLocalDispatcher(loaderB)
            assertEquals(0, IncisionBridge.localLeaseCount())
            assertFalse(IncisionBridge.hasSystemHost())
        } finally {
            IncisionBridge.unregisterLocalDispatcher(loaderA)
            IncisionBridge.unregisterLocalDispatcher(loaderB)
        }
    }

    /** Gate 生命周期夹具；最后一个 lease 释放前 host 必须保持可用。 */
    class FakeHost {
        fun dispatch(targetSignature: String, self: Any?, args: Array<Any?>): Any? = null
    }

    /** child-first 仅作用于夹具类，确保测试本身仍共享 JUnit 与 Bridge 类型。 */
    private class FixtureLoader(
        private val fixtureName: String,
        private val fixtureBytes: ByteArray,
    ) : ClassLoader(BridgeFixtureDispatcher::class.java.classLoader) {

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != fixtureName) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                val loaded = findLoadedClass(name) ?: defineClass(name, fixtureBytes, 0, fixtureBytes.size)
                if (resolve) resolveClass(loaded)
                return loaded
            }
        }
    }
}
