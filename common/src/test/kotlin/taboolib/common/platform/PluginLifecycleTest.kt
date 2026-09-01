package taboolib.common.platform

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.tabooproject.reflex.Reflex
import org.tabooproject.reflex.ReflexClass

class PluginLifecycleTest {

    @AfterEach
    fun clearCaches() {
        Reflex.clearCaches()
    }

    @Test
    fun `覆盖卸载回调时仍清理反射缓存`() {
        var disabled = false
        cacheTestClass()

        PluginLifecycle.disable(object : Plugin() {
            override fun onDisable() {
                disabled = true
            }
        })

        assertTrue(disabled)
        assertEquals(0, ReflexClass.reflexClassCacheMap.size)
    }

    @Test
    fun `卸载回调抛出异常时仍清理反射缓存`() {
        cacheTestClass()
        val failure = IllegalStateException("测试异常")

        val actual = assertThrows(IllegalStateException::class.java) {
            PluginLifecycle.disable(object : Plugin() {
                override fun onDisable() {
                    throw failure
                }
            })
        }

        assertSame(failure, actual)
        assertEquals(0, ReflexClass.reflexClassCacheMap.size)
    }

    private fun cacheTestClass() {
        ReflexClass.of(PluginLifecycleTest::class.java)
        assertTrue(ReflexClass.reflexClassCacheMap.size > 0)
    }
}
