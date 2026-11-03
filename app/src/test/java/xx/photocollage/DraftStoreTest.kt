package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DraftStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val collage = Collage(
        root = Split(Dir.ROW, 0.5f, Leaf(CellPhoto(Photo("a.img", 40, 30))), Leaf(CellPhoto(Photo("b.img", 10, 20)))),
    )

    private fun store(exists: (String) -> Boolean = { true }) = DraftStore(File(temp.root, "draft.json"), exists)

    @Test
    fun noDraftYet() {
        val store = store()
        assertFalse(store.present)
        assertNull(store.read())
    }

    @Test
    fun aWrittenDraftReadsBack() {
        val store = store()
        assertTrue(store.write(collage))
        assertTrue(store.present)
        assertEquals(collage, store.read())
    }

    @Test
    fun aPhotoWhoseCopyIsGoneLeavesItsCellEmpty() {
        assertTrue(store().write(collage))
        val read = store { it != "b.img" }.read()
        assertEquals(listOf(Photo("a.img", 40, 30)), read?.photos())
        assertEquals(2, read?.cellCount)
    }

    @Test
    fun aDraftThatCannotBeWrittenSaysSo() {
        val store = DraftStore(File(temp.root, "missing/draft.json")) { true }
        assertFalse(store.write(collage))
        assertFalse(store.present)
    }

    @Test
    fun aDraftOfNoUseReadsAsNoneButIsThere() {
        File(temp.root, "draft.json").writeText("not json")
        val store = store()
        assertTrue(store.present)
        assertNull(store.read())
    }
}
