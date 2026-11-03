package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotosTest {

    @Test
    fun aDecodeThatFailsIsTriedSmaller() {
        val tried = mutableListOf<Int>()
        val got = decodeShrinking(4000) { size -> tried += size; size.takeIf { it <= 1000 } }
        assertEquals(1000, got)
        assertEquals(listOf(4000, 2000, 1000), tried)
    }

    @Test
    fun aDecodeThatNeverWorksStopsAtTheSmallestSize() {
        val tried = mutableListOf<Int>()
        assertNull(decodeShrinking(2048) { size -> tried += size; null })
        assertEquals(listOf(2048, 1024, 512, 256), tried)
    }

    @Test
    fun aSizeAlreadyBelowTheSmallestIsTriedOnce() {
        val tried = mutableListOf<Int>()
        assertNull(decodeShrinking(100) { size -> tried += size; null })
        assertEquals(listOf(100), tried)
    }
}
