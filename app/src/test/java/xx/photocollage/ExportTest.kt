package xx.photocollage

import org.junit.Assert.assertEquals
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
}
