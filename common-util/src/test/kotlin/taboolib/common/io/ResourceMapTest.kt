package taboolib.common.io

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ResourceMapTest {

    @Test
    fun `resource bytes are loaded on first access`() {
        var indexCount = 0
        var readCount = 0
        val resources = ResourceMap.of {
            indexCount++
            mapOf(
                "first.txt" to { readCount++; byteArrayOf(1) },
                "second.txt" to { readCount++; byteArrayOf(2) },
            )
        }

        assertEquals(0, indexCount)
        assertEquals(2, resources.size)
        assertEquals(1, indexCount)
        assertEquals(0, readCount)
        assertArrayEquals(byteArrayOf(1), resources["first.txt"])
        assertArrayEquals(byteArrayOf(1), resources["first.txt"])
        assertEquals(1, readCount)
    }

    @Test
    fun `combined resources remain lazy and use the latest value`() {
        var firstRead = 0
        var secondRead = 0
        val first = ResourceMap.of { mapOf("value.txt" to { firstRead++; byteArrayOf(1) }) }
        val second = ResourceMap.of { mapOf("value.txt" to { secondRead++; byteArrayOf(2) }) }
        val resources = ResourceMap.combine(listOf(first, second))

        assertEquals(0, firstRead)
        assertEquals(0, secondRead)
        assertArrayEquals(byteArrayOf(2), resources["value.txt"])
        assertEquals(0, firstRead)
        assertEquals(1, secondRead)
    }
}
