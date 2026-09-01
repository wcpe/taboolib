package taboolib.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReflexVersionTest {

    @Test
    fun `运行时版本与构建版本一致`() {
        assertEquals(System.getProperty("reflexVersion"), ReflexVersion.VERSION)
    }
}
