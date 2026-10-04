package taboolib.module.incision.bridge

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.invoke.MethodHandles

/**
 * Bridge 路由调用语义验收 —— 每次被织入方法调用都要走这条路径，因此"换个更快的调用方式"不得
 * 改变任何对外可观察行为。
 *
 * 覆盖两件事：
 *
 * 1. **异常语义一致**：反射路径下 `Method.invoke` 会把 handler 抛出的任何 Throwable 包成
 *    `InvocationTargetException`；`MethodHandle.invoke` 会原样抛出。Bridge 必须补回同样的包装，
 *    否则日志文案与上层捕获类型会变。
 * 2. **反射兜底仍在**：`unreflect` 受访问控制限制，拿不到句柄时（跨 ClassLoader、声明方非公开等）
 *    必须回落到优化前就存在的 `Method.invoke`，且错误信息与直接反射调用逐字一致。
 *
 * 两个夹具都由 child-first ClassLoader 定义副本，路由键（ClassLoader）与其他用例互不干扰。
 */
@DisplayName("IncisionBridge 路由调用语义")
class IncisionBridgeDispatchSemanticsTest {

    private val signature = "example/SemanticsTarget.run()V"

    @Test
    @DisplayName("句柄路由：正常返回值穿透，handler 异常仍包装为 InvocationTargetException")
    fun handleRouteKeepsReflectionExceptionSemantics() {
        val fixtureName = "taboolib.module.incision.bridge.BridgeHandleRouteDispatcher"
        val cls = defineInFreshLoader(fixtureName)
        val dispatchMethod = dispatchMethodOf(cls)

        // 前提校验：本夹具确实可 unreflect —— 否则这条用例测的就不是句柄路径
        assertDoesNotThrow(
            { MethodHandles.lookup().unreflect(dispatchMethod) },
            "公开类 + 公开静态 dispatch 必须能 unreflect，用例前提不成立",
        )

        try {
            IncisionBridge.registerLocalDispatcher(cls)

            // 正常路径：dispatcher 的返回值必须原样穿透
            assertEquals(
                "handle-route:$signature",
                IncisionBridge.dispatch(cls, signature, null, emptyArray<Any?>()),
                "句柄路由的返回值必须与反射路由逐字一致",
            )

            cls.getMethod("setThrowing", Boolean::class.javaPrimitiveType).invoke(null, true)
            val stderr = captureErr { IncisionBridge.dispatch(cls, signature, null, emptyArray<Any?>()) }

            assertTrue(
                stderr.contains("local dispatch failed: java.lang.reflect.InvocationTargetException"),
                "handler 异常必须以 InvocationTargetException 形态进入日志（与反射路径一致），实际输出:\n$stderr",
            )
            assertFalse(
                stderr.contains("IllegalStateException"),
                "MethodHandle 的原始异常不得直接漏到日志里，实际输出:\n$stderr",
            )
        } finally {
            IncisionBridge.unregisterLocalDispatcher(cls.classLoader)
        }
    }

    @Test
    @DisplayName("unreflect 失败时回落到反射兜底：错误信息与直接 Method.invoke 一致（仅调用方类名不同）")
    fun unreflectableRouteFallsBackToReflection() {
        val fixtureName = "taboolib.module.incision.bridge.BridgeReflectionOnlyDispatcher"
        val cls = defineInFreshLoader(fixtureName)
        val dispatchMethod = dispatchMethodOf(cls)

        // 前提校验：非公开声明类使 unreflect 必然失败（访问控制），这条用例才落到兜底路径
        assertThrows(
            IllegalAccessException::class.java,
            { MethodHandles.lookup().unreflect(dispatchMethod) },
            "非公开声明类必须让 unreflect 失败，用例前提不成立",
        )
        // 优化前该路径就是 Method.invoke：兜底行为必须与它一致。
        // 反射错误里会带上"调用方类名"，生产路径的调用方是 Bridge 而不是本测试类，因此只把这一处归一化。
        val expectedReflectionError = try {
            dispatchMethod.invoke(null, signature, null, emptyArray<Any?>())
            "no-error"
        } catch (t: Throwable) {
            t.toString().replace(
                "class ${IncisionBridgeDispatchSemanticsTest::class.java.name}",
                "class ${IncisionBridge::class.java.name}",
            )
        }
        assertTrue(expectedReflectionError.startsWith("java.lang.IllegalAccessException"), "用例前提不成立")

        try {
            IncisionBridge.registerLocalDispatcher(cls)
            val stderr = captureErr {
                assertNull(
                    IncisionBridge.dispatch(cls, signature, null, emptyArray<Any?>()),
                    "无法调用的路由不得被当成命中，返回值必须是 null",
                )
            }
            assertTrue(
                stderr.contains("local dispatch failed: $expectedReflectionError"),
                "兜底路径必须仍然调用 Method.invoke 并打印同样的错误，期望包含" +
                    "「local dispatch failed: $expectedReflectionError」，实际输出:\n$stderr",
            )        } finally {
            IncisionBridge.unregisterLocalDispatcher(cls.classLoader)
        }
    }

    // ===== 夹具加载与辅助 =====

    private fun dispatchMethodOf(cls: Class<*>): java.lang.reflect.Method =
        cls.getMethod("dispatch", String::class.java, Any::class.java, Array<Any>::class.java)

    private fun defineInFreshLoader(fixtureName: String): Class<*> {
        val path = fixtureName.replace('.', '/') + ".class"
        val bytes = IncisionBridgeDispatchSemanticsTest::class.java.classLoader
            .getResourceAsStream(path)!!.use { it.readBytes() }
        return Class.forName(fixtureName, true, FixtureLoader(fixtureName, bytes))
    }

    /** 捕获 System.err —— Bridge 的失败路径只通过 stderr 暴露，这是它唯一的可观察出口。 */
    private fun captureErr(block: () -> Unit): String {
        val original = System.err
        val buffer = java.io.ByteArrayOutputStream()
        System.setErr(java.io.PrintStream(buffer, true, "UTF-8"))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString("UTF-8")
    }

    /** child-first 仅作用于夹具类，确保测试与 Bridge 仍然共享同一份 JUnit / Bridge 类型。 */
    private class FixtureLoader(
        private val fixtureName: String,
        private val fixtureBytes: ByteArray,
    ) : ClassLoader(IncisionBridgeDispatchSemanticsTest::class.java.classLoader) {

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
