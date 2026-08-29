package taboolib.common.env.legacy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 验证 Maven 坐标到本地仓库文件的转换。
 *
 * @author sky
 */
class ArtifactTest {

    @Test
    fun `find local artifact file`() {
        assertEquals(File("libraries/org/example/demo/1.0/demo-1.0.jar"), Artifact("org.example:demo:1.0").findFile(File("libraries")))
        assertEquals(File("libraries/org/example/demo/1.0/demo-1.0-all.zip"), Artifact("org.example:demo:zip:all:1.0").findFile(File("libraries")))
    }
}
