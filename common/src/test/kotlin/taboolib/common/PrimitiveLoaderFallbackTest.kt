package taboolib.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 仓库回退下载的回归测试。
 *
 * 背景：默认仓库改为 wcpe 优先后，一旦首选仓库不可达或缺少该构件，必须能自动回退到兜底
 * 仓库，否则插件在服务器上直接起不来。这里用三个本地 file:// 仓库模拟真实下载路径，
 * 钉住四条关键行为：
 * 1. 首选仓库有构件时直接命中，不应触碰后续仓库；
 * 2. 首选仓库缺少构件（本地表现为文件不存在）时回退到下一个仓库并下载成功；
 * 3. 首选仓库地址不可达（目录不存在）时同样回退成功；
 * 4. 所有仓库都失败时返回 false，且不留下残缺的 jar 或 sha1 文件。
 */
class PrimitiveLoaderFallbackTest {

    @TempDir
    lateinit var tempDir: File

    private val group = "io.izzel.taboolib"
    private val name = "common-env"
    private val version = "6.3.0-wcpe.4"
    private val jarName = "$name-$version.jar"

    /** 在指定仓库目录下布置一个构件（jar + 匹配的 sha1） */
    private fun publish(repoDir: File, content: String) {
        val dir = File(repoDir, "${group.replace(".", "/")}/$name/$version")
        dir.mkdirs()
        val jar = File(dir, jarName)
        jar.writeText(content)
        File(dir, "$jarName.sha1").writeText(PrimitiveIO.getHash(jar))
    }

    /** 造一个空的仓库目录并返回 file:// 形式地址 */
    private fun emptyRepo(label: String): String {
        val dir = File(tempDir, label)
        dir.mkdirs()
        return dir.toURI().toString()
    }

    private fun repoOf(label: String): File = File(tempDir, label)

    private fun targetFiles(): Pair<File, File> {
        val libs = File(tempDir, "libraries")
        val base = File(libs, "${group.replace(".", "/")}/$name/$version")
        return File(base, jarName) to File(base, "$jarName.sha1")
    }

    @Test
    fun `首选仓库有构件时直接命中`() {
        val primary = repoOf("primary").also { publish(it, "from-primary") }
        val secondary = repoOf("secondary").also { publish(it, "from-secondary") }
        val (jar, sha) = targetFiles()

        val ok = PrimitiveLoader.tryDownload(
            primary.toURI().toString(), group, name, version, null, jar, sha
        )

        assertTrue(ok, "首选仓库存在构件时必须下载成功")
        assertEquals("from-primary", jar.readText(), "应使用首选仓库的内容，而不是回退")
        assertTrue(sha.exists(), "sha1 必须一并落盘")
        assertTrue(secondary.exists(), "后续仓库应保持未被使用")
    }

    @Test
    fun `首选仓库缺少构件时回退到下一个仓库`() {
        // 首选仓库为空，构件只存在于第二个仓库
        val primary = emptyRepo("primary")
        val secondary = repoOf("secondary").also { publish(it, "from-secondary") }
        val (jar, sha) = targetFiles()

        val first = PrimitiveLoader.tryDownload(primary, group, name, version, null, jar, sha)
        assertFalse(first, "首选仓库缺少构件时必须报告失败，以便调用方回退")

        val second = PrimitiveLoader.tryDownload(
            secondary.toURI().toString(), group, name, version, null, jar, sha
        )
        assertTrue(second, "回退到第二个仓库后必须下载成功")
        assertEquals("from-secondary", jar.readText())
        assertTrue(PrimitiveIO.validation(jar, sha), "回退下载的产物必须通过校验")
    }

    @Test
    fun `首选仓库地址不可达时回退成功`() {
        // 目录不存在 —— 模拟仓库宕机 / DNS 解析失败
        val dead = File(tempDir, "does-not-exist").toURI().toString()
        val secondary = repoOf("secondary").also { publish(it, "from-secondary") }
        val (jar, sha) = targetFiles()

        assertFalse(
            PrimitiveLoader.tryDownload(dead, group, name, version, null, jar, sha),
            "不可达的仓库必须报告失败"
        )
        assertTrue(
            PrimitiveLoader.tryDownload(secondary.toURI().toString(), group, name, version, null, jar, sha),
            "回退到可用仓库后必须成功"
        )
        assertEquals("from-secondary", jar.readText())
    }

    @Test
    fun `全部仓库失败时不留下残缺文件`() {
        val empty = emptyRepo("empty")
        val (jar, sha) = targetFiles()

        val ok = PrimitiveLoader.tryDownload(empty, group, name, version, null, jar, sha)

        assertFalse(ok, "所有仓库都缺构件时必须返回 false")
        assertFalse(jar.exists(), "失败后不能残留 jar，否则下次会被误判为已下载")
        assertFalse(sha.exists(), "失败后不能残留 sha1")
    }

    @Test
    fun `回退链按顺序命中第一个可用仓库`() {
        // 第一个为空，第二个有 a，第三个有 b —— 应命中第二个的 a
        val first = emptyRepo("r1")
        val second = repoOf("r2").also { publish(it, "a") }
        val third = repoOf("r3").also { publish(it, "b") }
        val (jar, sha) = targetFiles()

        val repos = listOf(first, second.toURI().toString(), third.toURI().toString())
        var hit = false
        for (repo in repos) {
            if (PrimitiveLoader.tryDownload(repo, group, name, version, null, jar, sha)) {
                hit = true
                break
            }
        }

        assertTrue(hit, "回退链中必须命中某个仓库")
        assertEquals("a", jar.readText(), "应命中顺序上更靠前的第二个仓库")
    }
}
