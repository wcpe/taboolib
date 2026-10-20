package taboolib.common

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 多仓库解析的回归测试。
 *
 * 背景：运行时默认仓库原先只有一个地址，wcpe 未同步到的上游版本会导致下载失败。
 * 现在改为「配置的仓库按顺序优先，官方仓库兜底」，这里固定住三条关键行为：
 * 1. 配置的仓库必须排在兜底仓库之前（优先级不能颠倒）；
 * 2. 重复项与结尾斜杠必须归一化，避免同一个仓库被尝试两次；
 * 3. 配置为空时也要至少给出兜底仓库，不能返回空列表导致完全无法下载。
 */
class PrimitiveSettingsRepoTest {

    private val fallback = "https://repo.tabooproject.org/repository/releases"

    @Test
    fun `配置的仓库排在兜底仓库之前`() {
        val repos = PrimitiveSettings.buildRepoList("https://repo.wcpe.top/repository/maven-public/")
        assertEquals(2, repos.size)
        assertEquals("https://repo.wcpe.top/repository/maven-public", repos[0])
        assertEquals(fallback, repos[1])
    }

    @Test
    fun `支持逗号分隔的多个仓库且保持书写顺序`() {
        val repos = PrimitiveSettings.buildRepoList("https://a.example,https://b.example")
        assertArrayEquals(
            arrayOf("https://a.example", "https://b.example", fallback),
            repos
        )
    }

    @Test
    fun `支持分号分隔并清理空白`() {
        val repos = PrimitiveSettings.buildRepoList("  https://a.example ; https://b.example ")
        assertArrayEquals(
            arrayOf("https://a.example", "https://b.example", fallback),
            repos
        )
    }

    @Test
    fun `结尾斜杠被归一化以去重`() {
        val repos = PrimitiveSettings.buildRepoList("https://a.example/,https://a.example")
        assertArrayEquals(arrayOf("https://a.example", fallback), repos)
    }

    @Test
    fun `已包含兜底仓库时不重复追加`() {
        val repos = PrimitiveSettings.buildRepoList(fallback)
        assertArrayEquals(arrayOf(fallback), repos)
    }

    @Test
    fun `空配置时仍返回兜底仓库`() {
        assertArrayEquals(arrayOf(fallback), PrimitiveSettings.buildRepoList(""))
        assertArrayEquals(arrayOf(fallback), PrimitiveSettings.buildRepoList("   "))
    }

    @Test
    fun `默认配置为 wcpe 优先且非空`() {
        val repos = PrimitiveSettings.REPO_TABOOLIB_LIST
        assertTrue(repos.isNotEmpty(), "默认仓库列表不能为空，否则无法下载任何依赖")
        assertTrue(
            repos[0].startsWith("https://repo.wcpe.top/"),
            "默认应优先使用 wcpe 仓库，实际为 ${repos[0]}"
        )
        assertTrue(repos.contains(fallback), "必须保留官方仓库作为兜底")
        // reflex 与 taboolib 使用同一套优先级策略
        assertEquals(repos.toList(), PrimitiveSettings.REPO_REFLEX_LIST.toList())
    }

    @Test
    fun `列表内不存在重复仓库`() {
        val repos = PrimitiveSettings.buildRepoList("https://a.example,https://a.example/,https://a.example")
        assertEquals(repos.toList().distinct(), repos.toList())
        assertFalse(repos.size > 2, "重复仓库必须被合并，实际为 ${repos.toList()}")
    }
}
