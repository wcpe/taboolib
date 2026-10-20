package taboolib.common

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * 仓库缺少 sha1 时的下载行为测试。
 *
 * 背景：e2e 用 file:// 指向本地 m2 仓库，而 maven-publish 生成的本地仓库没有 .sha1 文件。
 * 此时：
 * - 开发模式（IS_DEV_MODE）下应该用 jar 自行生成 sha1 并继续，否则本地开发环境无法启动；
 * - 非开发模式下应判定该仓库失败，让调用方回退到下一个仓库，而不是用未校验的产物。
 *
 * 这里通过 taboolib.dev 系统属性显式控制 IS_DEV_MODE 来覆盖两条分支。
 */
class PrimitiveLoaderSha1Test {

    @TempDir
    lateinit var tempDir: File

    private val group = "io.izzel.taboolib"
    private val name = "common-env"
    private val version = "6.3.0-wcpe.4"
    private val jarName = "$name-$version.jar"

    /** 布置一个只有 jar、没有 sha1 的仓库（模拟 maven-publish 的本地仓库） */
    private fun repoWithoutSha1(): String {
        val dir = File(tempDir, "repo")
        val target = File(dir, "${group.replace(".", "/")}/$name/$version")
        target.mkdirs()
        File(target, jarName).writeText("no-sha1-content")
        return dir.toURI().toString()
    }

    private fun targetFiles(): Pair<File, File> {
        val base = File(tempDir, "libs/${group.replace(".", "/")}/$name/$version")
        return File(base, jarName) to File(base, "$jarName.sha1")
    }

    @Test
    fun `非开发模式下缺 sha1 的仓库应判定失败`() {
        // 显式处于非开发模式
        val previous = System.getProperty("taboolib.dev")
        System.setProperty("taboolib.dev", "false")
        try {
            val repo = repoWithoutSha1()
            val (jar, sha) = targetFiles()

            val ok = PrimitiveLoader.tryDownload(repo, group, name, version, null, jar, sha)

            assertFalse(ok, "非开发模式下缺 sha1 必须判定失败，以便回退到下一个仓库")
            assertFalse(jar.exists(), "失败后不应残留 jar")
            assertFalse(sha.exists(), "失败后不应残留 sha1")
        } finally {
            if (previous == null) System.clearProperty("taboolib.dev") else System.setProperty("taboolib.dev", previous)
        }
    }

    @Test
    fun `带 sha1 的仓库在非开发模式下可以成功`() {
        val previous = System.getProperty("taboolib.dev")
        System.setProperty("taboolib.dev", "false")
        try {
            val dir = File(tempDir, "repo-ok")
            val target = File(dir, "${group.replace(".", "/")}/$name/$version")
            target.mkdirs()
            val source = File(target, jarName)
            source.writeText("with-sha1-content")
            File(target, "$jarName.sha1").writeText(PrimitiveIO.getHash(source))

            val (jar, sha) = targetFiles()
            val ok = PrimitiveLoader.tryDownload(dir.toURI().toString(), group, name, version, null, jar, sha)

            assertTrue(ok, "jar 与 sha1 都齐全时必须下载成功")
            assertTrue(PrimitiveIO.validation(jar, sha), "下载产物必须通过校验")
        } finally {
            if (previous == null) System.clearProperty("taboolib.dev") else System.setProperty("taboolib.dev", previous)
        }
    }
}
