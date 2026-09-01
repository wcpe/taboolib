package taboolib.e2e

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import taboolib.common.PrimitiveSettings

class LocalVersionTest {

    @Test
    fun `端到端测试使用可从本地仓库解析的版本`() {
        assertTrue(PrimitiveSettings.TABOOLIB_VERSION.endsWith("-local"))
        assertFalse(PrimitiveSettings.TABOOLIB_VERSION.endsWith("-local-dev"))
    }
}
