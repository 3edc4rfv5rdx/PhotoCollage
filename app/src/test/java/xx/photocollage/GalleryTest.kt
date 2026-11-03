package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTest {

    @Test
    fun imagesGoIntoTheirFoldersTheNewestFolderFirst() {
        val folders = foldersOf(
            listOf(
                MediaImage(1, "DCIM/Camera/", 100),
                MediaImage(2, "Pictures/Trips/", 300),
                MediaImage(3, "DCIM/Camera/", 200),
                MediaImage(4, "Download/", 50),
            ),
        )
        assertEquals(listOf("Pictures/Trips/", "DCIM/Camera/", "Download/"), folders.map { it.path })
        // Newest first inside a folder too.
        assertEquals(listOf(3L, 1L), folders[1].images.map { it.id })
    }

    @Test
    fun aFolderIsKnownByItsLastPart() {
        assertEquals("Camera", MediaFolder("DCIM/Camera/", emptyList()).label)
        assertEquals("Download", MediaFolder("Download", emptyList()).label)
        assertEquals("/", MediaFolder("", emptyList()).label)
    }

    @Test
    fun noImagesNoFolders() {
        assertEquals(emptyList<MediaFolder>(), foldersOf(emptyList()))
    }

    @Test
    fun aListOfNamesReadsInOrderCaseAside() {
        val images = listOf(MediaImage(1, "", 1, "b.jpg"), MediaImage(2, "", 2, "A.jpg"), MediaImage(3, "", 3, "c.jpg"))
        assertEquals(listOf("A.jpg", "b.jpg", "c.jpg"), byName(images) { it.name }.map { it.name })
    }
}
