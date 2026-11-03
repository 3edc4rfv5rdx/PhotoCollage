package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {

    @Test
    fun aTypedNameGetsTheExtension() {
        assertEquals("my trip.jpg", jpegFileName("  my trip "))
    }

    @Test
    fun anExtensionAlreadyThereIsKept() {
        assertEquals("x.JPG", jpegFileName("x.JPG"))
        assertEquals("y.jpeg", jpegFileName("y.jpeg"))
    }

    @Test
    fun charactersNoFolderTakesAreDropped() {
        assertEquals("abc.jpg", jpegFileName("a/b:c"))
        assertEquals("q.jpg", jpegFileName("q?*\"<>|\\"))
    }

    @Test
    fun nothingLeftIsTheDefaultName() {
        val name = jpegFileName(" .. / ")
        assertTrue(name, name.startsWith("PhotoCollage-") && name.endsWith(".jpg"))
    }

    @Test
    fun aPhotoThatFailsOnlyForTheBlendIsNotUnreadable() {
        val photo = Photo("a.img", 10, 10)
        val unreadable = HashSet<Photo>()
        // Drawn in its cell, then not to be had again for its bands.
        val results = ArrayDeque(listOf("bitmap", null))
        val bitmapOf = notingUnreadable(unreadable) { results.removeFirst() }
        assertEquals("bitmap", bitmapOf(photo))
        assertNull(bitmapOf(photo))
        assertTrue(unreadable.isEmpty())
    }

    @Test
    fun aPhotoThatFailsInItsCellIsUnreadable() {
        val photo = Photo("a.img", 10, 10)
        val unreadable = HashSet<Photo>()
        val bitmapOf = notingUnreadable<String>(unreadable) { null }
        assertNull(bitmapOf(photo))
        assertNull(bitmapOf(photo))
        assertEquals(setOf(photo), unreadable)
    }
}
