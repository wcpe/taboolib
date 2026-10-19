package taboolib.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * 重定位缓存复用的回归测试。
 *
 * 背景：旧实现里缓存只要文件存在就会被无条件复用，导致过「缓存文件存在但内容未按当前规则
 * 重定位」时加载抛 NoClassDefFoundError 的事故。这里把三类行为钉住：
 * 1. 指纹必须随影响产物内容的输入变化；
 * 2. 没有标记文件的旧缓存必须被判为不可用（升级后自动重建）；
 * 3. 内容被破坏（截断 / 篡改为空）的缓存必须被判为不可用。
 */
class PrimitiveLoaderCacheTest {

    @TempDir
    lateinit var tempDir: File

    private fun rule(vararg pairs: Pair<String, String>): List<Array<String>> =
        pairs.map { arrayOf(it.first, it.second) }

    /** 造一个内容可辨的假 jar，用来代表重定位产物 */
    private fun fakeJar(name: String, entries: List<String>): File {
        val file = File(tempDir, name)
        JarOutputStream(file.outputStream()).use { out ->
            entries.forEach {
                out.putNextEntry(ZipEntry(it))
                out.write(it.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `指纹随重定向规则变化`() {
        val file = File("common-util-6.3.0-wcpe.2.jar")
        val a = PrimitiveLoader.relocateCacheHash(file, rule("io.izzel.taboolib" to "taboolib"))
        val b = PrimitiveLoader.relocateCacheHash(file, rule("io.izzel.taboolib" to "taboolib-v2"))
        assertNotEquals(a, b, "规则变了缓存指纹必须变，否则会复用到按旧规则生成的内容")
    }

    @Test
    fun `指纹随源文件名变化`() {
        val rules = rule("io.izzel.taboolib" to "taboolib")
        val a = PrimitiveLoader.relocateCacheHash(File("common-util-6.2.4.jar"), rules)
        val b = PrimitiveLoader.relocateCacheHash(File("common-util-6.3.0.jar"), rules)
        assertNotEquals(a, b, "不同版本的源文件不能共用同一份缓存")
    }

    @Test
    fun `相同输入得到稳定指纹`() {
        val rules = rule("io.izzel.taboolib" to "taboolib", "kotlin" to "kotlin210")
        val file = File("common-util-6.3.0-wcpe.2.jar")
        assertEquals(
            PrimitiveLoader.relocateCacheHash(file, rules),
            PrimitiveLoader.relocateCacheHash(file, rules),
            "同样的输入必须得到同样的指纹，否则缓存永远无法命中"
        )
    }

    @Test
    fun `没有标记文件的旧缓存不可用`() {
        val jar = fakeJar("legacy-cache.jar", listOf("taboolib/common/A.class"))
        assertFalse(
            PrimitiveLoader.isCacheUsable(jar, "any-hash"),
            "旧版本生成的缓存没有标记文件，必须判为不可用并重建"
        )
    }

    @Test
    fun `写入标记后缓存可用`() {
        val jar = fakeJar("fresh-cache.jar", listOf("taboolib/common/A.class"))
        val hash = "rule-hash"
        PrimitiveLoader.writeCacheMarker(jar, hash)
        assertTrue(PrimitiveLoader.cacheMarkerFile(jar).isFile, "标记文件应当被写出")
        assertTrue(PrimitiveLoader.isCacheUsable(jar, hash), "标记匹配时缓存应当可用")
    }

    @Test
    fun `规则指纹不匹配时缓存不可用`() {
        val jar = fakeJar("stale-cache.jar", listOf("taboolib/common/A.class"))
        PrimitiveLoader.writeCacheMarker(jar, "old-rule-hash")
        assertFalse(
            PrimitiveLoader.isCacheUsable(jar, "new-rule-hash"),
            "规则指纹不匹配说明产物是按旧规则生成的，必须重建"
        )
    }

    @Test
    fun `空文件缓存不可用`() {
        val jar = File(tempDir, "empty-cache.jar")
        jar.createNewFile()
        PrimitiveLoader.writeCacheMarker(jar, "rule-hash")
        assertFalse(
            PrimitiveLoader.isCacheUsable(jar, "rule-hash"),
            "0 字节缓存不可用（旧实现因 && 写错而漏判了这种情况）"
        )
    }

    @Test
    fun `产物内容被篡改后缓存不可用`() {
        val jar = fakeJar("tampered-cache.jar", listOf("taboolib/common/A.class"))
        val hash = "rule-hash"
        PrimitiveLoader.writeCacheMarker(jar, hash)
        assertTrue(PrimitiveLoader.isCacheUsable(jar, hash))

        // 用不同内容覆盖产物但保留标记文件，模拟「同名不同内容」的历史事故现场
        fakeJar("tampered-cache.jar", listOf("taboolib/common/B.class"))
        assertFalse(
            PrimitiveLoader.isCacheUsable(jar, hash),
            "产物内容与标记记录不一致时必须重建"
        )
    }
}
