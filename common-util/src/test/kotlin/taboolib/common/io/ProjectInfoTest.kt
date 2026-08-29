package taboolib.common.io

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 验证未重定位与已重定位环境下的 TabooLib 根包路径。
 *
 * @author sky
 */
class ProjectInfoTest {

    @Test
    fun `taboolib path does not duplicate root package`() {
        val previous = System.getProperty("taboolib.group")
        try {
            System.setProperty("taboolib.group", "taboolib")
            assertEquals("taboolib", taboolibPath)
            System.setProperty("taboolib.group", "example.plugin")
            assertEquals("example.plugin.taboolib", taboolibPath)
        } finally {
            if (previous == null) {
                System.clearProperty("taboolib.group")
            } else {
                System.setProperty("taboolib.group", previous)
            }
        }
    }
}
