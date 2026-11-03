package xx.photocollage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DraftArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val photos by lazy { File(temp.root, "photos").apply { mkdirs() } }
    private val store by lazy { DraftStore(File(temp.root, "drafts")) { File(photos, it).isFile } }

    private fun collage(vararg names: String) = Collage(
        root = names.fold<String, Node>(Leaf()) { node, name ->
            Split(Dir.ROW, 0.5f, node, Leaf(CellPhoto(Photo(name, 40, 30))))
        },
    )

    private fun photo(name: String, text: String = name) = File(photos, name).apply { writeText(text) }

    /** The drafts here as an archive file, each with [preview] as its picture. */
    private fun export(preview: ByteArray? = byteArrayOf(1, 2, 3)): Pair<File, Int> {
        val file = File(temp.root, "out-${System.nanoTime()}.zip")
        val count = file.outputStream().use { out ->
            writeDraftArchive(out, store.list(), store::read, { File(photos, it) }) { preview }
        }
        return file to count
    }

    /** A fresh install: its own empty drafts and photos. */
    private fun elsewhere(): Pair<DraftStore, File> {
        val root = temp.newFolder()
        val dir = File(root, "photos")
        return DraftStore(File(root, "drafts")) { File(dir, it).isFile } to dir
    }

    @Test
    fun draftsAndTheirPhotosGoOutAndComeBackInElsewhere() {
        photo("a.img")
        photo("b.img")
        val one = newDraftId()
        val two = newDraftId()
        store.write(one, collage("a.img"), savedAt = 1_000_000)
        store.write(two, collage("a.img", "b.img"), savedAt = 2_000_000)
        val (file, count) = export()
        assertEquals(2, count)

        val archive = DraftArchive.open(file)!!
        assertEquals(listOf(ArchivedDraft(two, 2_000_000), ArchivedDraft(one, 1_000_000)), archive.drafts)
        assertArrayEquals(byteArrayOf(1, 2, 3), archive.preview(one))

        val (there, dir) = elsewhere()
        assertEquals(DraftsImported(2, 0), importDrafts(archive, listOf(one, two), there, dir))
        assertEquals(collage("a.img"), there.read(one))
        assertEquals(collage("a.img", "b.img"), there.read(two))
        assertEquals(listOf(DraftInfo(two, 2_000_000), DraftInfo(one, 1_000_000)), there.list())
        assertEquals("b.img", File(dir, "b.img").readText())
        archive.close()
    }

    @Test
    fun onlyTheChosenDraftsComeIn() {
        photo("a.img")
        photo("b.img")
        val one = newDraftId()
        val two = newDraftId()
        store.write(one, collage("a.img"))
        store.write(two, collage("b.img"))
        val archive = DraftArchive.open(export().first)!!
        val (there, dir) = elsewhere()
        importDrafts(archive, listOf(two), there, dir)
        assertEquals(listOf(two), there.list().map { it.id })
        assertFalse(File(dir, "a.img").exists())
        archive.close()
    }

    @Test
    fun aDraftHereAlreadyComesInAsACopyAndWritesNothingOver() {
        photo("a.img")
        val id = newDraftId()
        store.write(id, collage("a.img"))
        val archive = DraftArchive.open(export().first)!!
        // Cell 1: cell 0 is the empty one the builder starts from.
        val changed = collage("a.img").mapPhoto(1) { it.turned() }
        assertNotEquals(collage("a.img"), changed)
        store.write(id, changed)

        assertEquals(DraftsImported(1, 0), importDrafts(archive, listOf(id), store, photos))
        assertEquals(changed, store.read(id))
        val copy = store.list().map { it.id }.single { it != id }
        assertEquals(collage("a.img"), store.read(copy))
        assertNotEquals(id, copy)
        archive.close()
    }

    @Test
    fun aPhotoHereAlreadyIsNotCopiedAgain() {
        photo("a.img", "archived")
        val id = newDraftId()
        store.write(id, collage("a.img"))
        val archive = DraftArchive.open(export().first)!!
        photo("a.img", "kept")
        importDrafts(archive, listOf(id), store, photos)
        assertEquals("kept", File(photos, "a.img").readText())
        archive.close()
    }

    @Test
    fun aPhotoNamedByTwoDraftsGoesOutOnce() {
        photo("a.img")
        store.write(newDraftId(), collage("a.img"))
        store.write(newDraftId(), collage("a.img"))
        val (file, _) = export()
        val names = java.util.zip.ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        assertEquals(1, names.count { it == "photos/a.img" })
    }

    @Test
    fun aDraftWithoutAPictureStillGoesOut() {
        photo("a.img")
        val id = newDraftId()
        store.write(id, collage("a.img"))
        val archive = DraftArchive.open(export(preview = null).first)!!
        assertNull(archive.preview(id))
        assertEquals(listOf(id), archive.drafts.map { it.id })
        archive.close()
    }

    @Test
    fun noDraftsMakeAnArchiveThatOpensAsNone() {
        val (file, count) = export()
        assertEquals(0, count)
        assertNull(DraftArchive.open(file))
    }

    @Test
    fun aFileThatIsNoArchiveOpensAsNone() {
        val file = File(temp.root, "x.zip").apply { writeText("not a zip") }
        assertNull(DraftArchive.open(file))
    }

    @Test
    fun anIndexEntryNamingAPathIsLeftOut() {
        val file = File(temp.root, "evil.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("drafts.json"))
            zip.write("""{"version":1,"drafts":[{"id":"../x","savedAt":1}]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("drafts/../x.json"))
            zip.write(collage().toJson().toByteArray())
            zip.closeEntry()
        }
        assertNull(DraftArchive.open(file))
    }

    @Test
    fun aDraftWhosePhotoTheArchiveLacksHasThatCellEmpty() {
        val id = newDraftId()
        val file = File(temp.root, "partial.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("drafts.json"))
            zip.write("""{"version":1,"drafts":[{"id":"$id","savedAt":5}]}""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("drafts/$id.json"))
            zip.write(collage("gone.img").toJson().toByteArray())
            zip.closeEntry()
        }
        val archive = DraftArchive.open(file)!!
        val (there, dir) = elsewhere()
        assertEquals(DraftsImported(1, 0), importDrafts(archive, listOf(id), there, dir))
        assertEquals(emptyList<Photo>(), there.read(id)?.photos())
        archive.close()
    }

    @Test
    fun anArchiveOfAnotherVersionOpensAsNone() {
        val file = File(temp.root, "v2.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("drafts.json"))
            zip.write("""{"version":2,"drafts":[]}""".toByteArray())
            zip.closeEntry()
        }
        assertNull(DraftArchive.open(file))
    }

    @Test
    fun aDraftNotInTheArchiveCountsAsFailed() {
        photo("a.img")
        store.write(newDraftId(), collage("a.img"))
        val archive = DraftArchive.open(export().first)!!
        val (there, dir) = elsewhere()
        assertEquals(DraftsImported(0, 1), importDrafts(archive, listOf(newDraftId()), there, dir))
        assertTrue(there.list().isEmpty())
        archive.close()
    }
}
