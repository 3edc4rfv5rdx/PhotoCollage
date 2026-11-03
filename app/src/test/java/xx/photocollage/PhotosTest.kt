package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PhotosTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A photo folder from an earlier run: one copy the draft names, one nothing does. */
    private fun earlierRun(): File = temp.newFolder("photos").apply {
        File(this, "draft.img").writeText("x")
        File(this, "stale.img").writeText("x")
    }

    @Test
    fun anImportBeforeTheStartsCleanUpKeepsItsCopy() {
        val dir = earlierRun()
        val done = AtomicBoolean(false)
        val keep = { setOf("draft.img") }
        // The import takes the lock first: it cleans up, then copies.
        forgetPhotosOnce(done, dir, keep)
        File(dir, "shared.img").writeText("x")
        // The start's own clean-up comes after it.
        forgetPhotosOnce(done, dir, keep)
        assertTrue(File(dir, "shared.img").exists())
        assertTrue(File(dir, "draft.img").exists())
        assertFalse(File(dir, "stale.img").exists())
    }

    @Test
    fun theCleanUpBeforeAnImportLeavesItAlone() {
        val dir = earlierRun()
        val done = AtomicBoolean(false)
        val keep = { setOf("draft.img") }
        forgetPhotosOnce(done, dir, keep)
        forgetPhotosOnce(done, dir, keep)
        File(dir, "picked.img").writeText("x")
        forgetPhotosOnce(done, dir, keep)
        assertTrue(File(dir, "picked.img").exists())
        assertFalse(File(dir, "stale.img").exists())
    }

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

    @Test
    fun aFailedDecodeIsTriedAgainOnlyAfterTheWait() {
        assertTrue(retryDue(null, 0L, 10_000L))
        assertFalse(retryDue(1_000L, 5_000L, 10_000L))
        assertTrue(retryDue(1_000L, 11_000L, 10_000L))
    }
}
