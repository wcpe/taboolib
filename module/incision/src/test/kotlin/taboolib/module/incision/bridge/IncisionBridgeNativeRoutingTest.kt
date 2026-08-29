package taboolib.module.incision.bridge

import io.izzel.incision.bridge.IncisionBridge
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 验证多个隔离后端共享唯一 native owner 时，字节码必须按 delegate 注册顺序串联处理。
 * 后一个 transformer 接收前一个的输出，任何插件都不能覆盖或跳过另一个插件的织入。
 */
class IncisionBridgeNativeRoutingTest {

    @Test
    fun nativeDelegatesTransformSequentially() {
        IncisionBridge.registerNativeBackend(FirstBackend::class.java, true)
        IncisionBridge.registerNativeBackend(SecondBackend::class.java, false)
        try {
            val transformed = IncisionBridge.transformNative(null, "example/Target", byteArrayOf(1))
            assertArrayEquals(byteArrayOf(1, 2, 3), transformed)
            assertEquals("loadedClassCount:example/Target", IncisionBridge.invokeNative("loadedClassCount", arrayOf("example/Target")))
        } finally {
            IncisionBridge.unregisterNativeBackend(FirstBackend::class.java)
            IncisionBridge.unregisterNativeBackend(SecondBackend::class.java)
        }
    }

    @Test
    fun delegateMethodsAreResolvedBeforeClassFileLoadHook() {
        IncisionBridge.registerNativeBackend(FirstBackend::class.java, true)
        try {
            val field = IncisionBridge::class.java.getDeclaredField("nativeTransformCache").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val cache = field.get(null) as ConcurrentHashMap<Class<*>, Method>
            val callback = cache[FirstBackend::class.java]

            assertSame(FirstBackend::class.java, callback?.declaringClass)
            assertEquals("onSharedClassFileLoad", callback?.name)
            assertEquals(3, callback?.parameterCount)
        } finally {
            IncisionBridge.unregisterNativeBackend(FirstBackend::class.java)
        }
    }

    @Test
    fun nativeHookDoesNotReenterDelegateOrWeaveBackendProtocol() {
        IncisionBridge.registerNativeBackend(ReentrantBackend::class.java, true)
        try {
            assertArrayEquals(
                byteArrayOf(1, 4),
                IncisionBridge.transformNative(null, "example/Target", byteArrayOf(1)),
            )
            assertEquals(
                null,
                IncisionBridge.transformNative(null, "taboolib/module/incision/loader/Backend\$BackendToken", byteArrayOf(1)),
            )
        } finally {
            IncisionBridge.unregisterNativeBackend(ReentrantBackend::class.java)
        }
    }

    object FirstBackend {
        @JvmStatic fun onSharedClassFileLoad(loader: ClassLoader?, name: String, bytes: ByteArray) = bytes + 2
        @JvmStatic fun sharedNativeInvoke(operation: String, args: Array<Any?>): Any? =
            if (operation == "dispose") null else "$operation:${args[0]}"
    }

    object SecondBackend {
        @JvmStatic fun onSharedClassFileLoad(loader: ClassLoader?, name: String, bytes: ByteArray) = bytes + 3
    }

    object ReentrantBackend {
        @JvmStatic fun onSharedClassFileLoad(loader: ClassLoader?, name: String, bytes: ByteArray): ByteArray {
            // 模拟 native hook 内触发协议类加载；内层回调必须直接放行，外层仍需完成一次织入。
            IncisionBridge.transformNative(loader, "example/Backend\$BackendToken", bytes)
            return bytes + 4
        }

        @JvmStatic fun sharedNativeInvoke(operation: String, args: Array<Any?>): Any? = null
    }
}
