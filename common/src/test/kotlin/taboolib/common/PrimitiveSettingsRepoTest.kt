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
 * 现在改为「wcpe 仓库恒居首、配置的仓库按序随后、官方仓库兜底」——wcpe 居首是硬保证：
 * 上游 gradle 插件会把 repo.tabooproject.org 烧录进插件包 env.properties，若照烧录顺序则
 * fork 私有版本（wcpe.x）永远拉不到。这里固定住四条关键行为：
 * 1. wcpe 仓库必须排第一（不受配置内容影响，已存在则不重复）；
 * 2. 配置的仓库按书写序排在 wcpe 之后、兜底之前；
 * 3. 重复项与结尾斜杠必须归一化，避免同一个仓库被尝试两次；
 * 4. 配置为空时也要至少给出 wcpe 与兜底仓库，不能返回空列表导致完全无法下载。
 */
class PrimitiveSettingsRepoTest {

    private val fallback = "https://repo.tabooproject.org/repository/releases"
    private val wcpe = "https://repo.wcpe.top/repository/maven-public"

    @Test
    fun `配置的仓库排在兜底仓库之前`() {
        val repos = PrimitiveSettings.buildRepoList("https://repo.wcpe.top/repository/maven-public/")
        assertEquals(2, repos.size)
        assertEquals(wcpe, repos[0])
        assertEquals(fallback, repos[1])
    }

    @Test
    fun `支持逗号分隔的多个仓库且保持书写顺序`() {
        val repos = PrimitiveSettings.buildRepoList("https://a.example,https://b.example")
        assertArrayEquals(
            arrayOf(wcpe, "https://a.example", "https://b.example", fallback),
            repos
        )
    }

    @Test
    fun `支持分号分隔并清理空白`() {
        val repos = PrimitiveSettings.buildRepoList("  https://a.example ; https://b.example ")
        assertArrayEquals(
            arrayOf(wcpe, "https://a.example", "https://b.example", fallback),
            repos
        )
    }

    @Test
    fun `结尾斜杠被归一化以去重`() {
        val repos = PrimitiveSettings.buildRepoList("https://a.example/,https://a.example")
        assertArrayEquals(arrayOf(wcpe, "https://a.example", fallback), repos)
    }

    @Test
    fun `已包含兜底仓库时不重复追加`() {
        val repos = PrimitiveSettings.buildRepoList(fallback)
        assertArrayEquals(arrayOf(wcpe, fallback), repos)
    }

    @Test
    fun `空配置时仍返回兜底仓库`() {
        assertArrayEquals(arrayOf(wcpe, fallback), PrimitiveSettings.buildRepoList(""))
        assertArrayEquals(arrayOf(wcpe, fallback), PrimitiveSettings.buildRepoList("   "))
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
        assertFalse(repos.size > 3, "重复仓库必须被合并（wcpe+配置+兜底共 3 项），实际为 ${repos.toList()}")
    }
}
