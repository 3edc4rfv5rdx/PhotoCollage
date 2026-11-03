package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryTest {

    private fun entry(name: String, folder: Boolean = false, modified: Long = 0) = FolderEntry(name, name, folder, modified)

    @Test
    fun foldersComeFirstByNameThenPhotosNewestFirst() {
        val order = pickerOrder(
            listOf(
                entry("old.jpg", modified = 100),
                entry("trips", folder = true, modified = 900),
                entry("new.jpg", modified = 300),
                entry("Camera", folder = true, modified = 1),
            ),
        )
        assertEquals(listOf("Camera", "trips", "new.jpg", "old.jpg"), order.map { it.name })
    }

    @Test
    fun onlyImagesAreOffered() {
        assertTrue(isImageType("image/jpeg"))
        assertTrue(isImageType("image/heic"))
        assertFalse(isImageType("application/zip"))
        assertFalse(isImageType(null))
    }

    @Test
    fun aListOfNamesReadsInOrderCaseAside() {
        val names = listOf("b.jpg", "A.jpg", "c.jpg")
        assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), byName(names) { it })
    }
}
