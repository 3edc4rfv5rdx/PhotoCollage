package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private fun dir() = File(temp.root, "drafts")

    private fun store(exists: (String) -> Boolean = { true }) = DraftStore(dir(), exists)

    @Test
    fun noDraftsYet() {
        val store = store()
        assertEquals(emptyList<DraftInfo>(), store.list())
        assertNull(store.read(newDraftId()))
    }

    @Test
    fun aWrittenDraftReadsBackAndIsListed() {
        val store = store()
        val id = newDraftId()
        assertTrue(store.write(id, collage))
        assertEquals(collage, store.read(id))
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun aDraftWrittenAgainHoldsTheNewCollage() {
        val store = store()
        val id = newDraftId()
        store.write(id, collage)
        val turned = collage.mapPhoto(0) { it.turned() }
        assertTrue(store.write(id, turned))
        assertEquals(turned, store.read(id))
        assertEquals(1, store.list().size)
    }

    @Test
    fun theDraftWrittenLastIsListedFirst() {
        val store = store()
        val older = newDraftId()
        val newer = newDraftId()
        store.write(older, collage)
        store.write(newer, collage)
        File(dir(), "$older.json").setLastModified(1_000_000)
        File(dir(), "$newer.json").setLastModified(2_000_000)
        assertEquals(listOf(newer, older), store.list().map { it.id })
    }

    @Test
    fun aDeletedDraftIsGone() {
        val store = store()
        val id = newDraftId()
        store.write(id, collage)
        assertTrue(store.delete(id))
        assertNull(store.read(id))
        assertEquals(emptyList<DraftInfo>(), store.list())
        assertTrue(store.delete(id))
    }

    @Test
    fun anIdWithAPathInItNamesNoFile() {
        val outside = File(temp.root, "x.json").apply { writeText(collage.toJson()) }
        val store = store()
        assertFalse(isDraftId("../x"))
        assertNull(store.read("../x"))
        assertFalse(store.write("../x", collage))
        assertFalse(store.delete("../x"))
        assertTrue(outside.exists())
    }

    @Test
    fun filesThatAreNoDraftsAreNotListed() {
        val store = store()
        val id = newDraftId()
        store.write(id, collage)
        File(dir(), "notes.json").writeText("{}")
        File(dir(), "$id.json.tmp").writeText("{}")
        assertEquals(listOf(id), store.list().map { it.id })
    }

    @Test
    fun aPhotoWhoseCopyIsGoneLeavesItsCellEmpty() {
        val id = newDraftId()
        assertTrue(store().write(id, collage))
        val read = store { it != "b.img" }.read(id)
        assertEquals(listOf(Photo("a.img", 40, 30)), read?.photos())
        assertEquals(2, read?.cellCount)
    }

    @Test
    fun aDraftThatCannotBeWrittenSaysSo() {
        dir().writeText("a file where the folder should be")
        val store = store()
        val id = newDraftId()
        assertFalse(store.write(id, collage))
        assertEquals(emptyList<DraftInfo>(), store.list())
    }

    @Test
    fun aDraftOfNoUseReadsAsNoneButIsListed() {
        val id = newDraftId()
        dir().mkdirs()
        File(dir(), "$id.json").writeText("not json")
        val store = store()
        assertEquals(listOf(id), store.list().map { it.id })
        assertNull(store.read(id))
    }

    @Test
    fun theOldSingleDraftIsMovedInOnce() {
        val old = File(temp.root, "draft.json").apply { writeText(collage.toJson()) }
        val store = store()
        val id = store.adopt(old)!!
        assertFalse(old.exists())
        assertEquals(collage, store.read(id))
        assertNull(store.adopt(old))
        assertEquals(1, store.list().size)
    }

    @Test
    fun newIdsDiffer() {
        assertNotEquals(newDraftId(), newDraftId())
        assertTrue(isDraftId(newDraftId()))
    }
}
