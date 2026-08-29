package taboolib.module.incision.loader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * 固定 API v2 后端安装协议：先注册 transformer，再判断是否需要同步重转换。
 * 注册先于 loaded-check 可封闭竞态：类若在检查前后首次定义，ClassFileLoadHook 必然能看到 transformer。
 */
class BackendInstallLifecycleTest {

    @Test
    fun pendingLoadNotifiesOnlyAfterLoadedCheck() {
        val backend = FakeBackend(loaded = false)
        val installation = backend.install("example/Test") { bytes -> bytes.copyOf() }

        assertEquals(Backend.InstallStatus.PENDING_LOAD, installation.status)
        assertEquals(0, backend.retransformCount)
        assertNotNull(installation.token)
    }

    @Test
    fun loadedClassRetransformsSynchronouslyWithoutPendingNotification() {
        val backend = FakeBackend(loaded = true)
        val installation = backend.install("example/Test") { bytes -> bytes.copyOf() }

        assertEquals(Backend.InstallStatus.INSTALLED, installation.status)
        assertEquals(1, backend.retransformCount)
    }

    private class FakeBackend(
        private val loaded: Boolean,
    ) : Backend {
        override val name = "fake"
        var retransformCount = 0
        private var transformer: ((ByteArray) -> ByteArray?)? = null

        override fun available() = true
        override fun addTransformer(className: String, transformer: (ByteArray) -> ByteArray?): Backend.BackendToken =
            object : Backend.BackendToken {
                init { this@FakeBackend.transformer = transformer }
                override fun remove() { this@FakeBackend.transformer = null }
            }
        override fun isClassLoaded(className: String) = loaded
        override fun retransform(className: String): Boolean {
            retransformCount++
            transformer?.invoke(byteArrayOf(1))
            return true
        }
    }
}
