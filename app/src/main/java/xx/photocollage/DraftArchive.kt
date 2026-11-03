package xx.photocollage

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** The archive's format; one of another is not read. */
private const val ARCHIVE_VERSION = 1

/** The archive's table of contents: its format, and each draft's id and when it was saved. */
private const val INDEX_ENTRY = "drafts.json"
private const val DRAFT_PREFIX = "drafts/"
private const val PREVIEW_PREFIX = "previews/"
private const val PHOTO_PREFIX = "photos/"
private const val TEXT_EXT = ".json"
private const val PREVIEW_EXT = ".jpg"

/** The most the index or a draft's text may take: far past any real one, short of what would starve memory. */
private const val MAX_TEXT_BYTES = 4L * 1024 * 1024

/** The most a draft's picture may take: far past a JPEG [DRAFT_TILE_LONG] pixels long. */
private const val MAX_PREVIEW_BYTES = 4L * 1024 * 1024

/** What the drafts' archives are, to the folder they are made in and the system's file picker. */
const val ARCHIVE_MIME = "application/zip"

/**
 * [drafts] as an archive into [out]: each one's text as [read] gives it, its
 * picture as [preview] draws it, if it can, and every photo it names, from
 * [photoFile], once however many drafts name it. A draft [read] cannot read
 * is left out. How many drafts went in. [out] is left open.
 */
fun writeDraftArchive(
    out: OutputStream,
    drafts: List<DraftInfo>,
    read: (String) -> Collage?,
    photoFile: (String) -> File,
    preview: (Collage) -> ByteArray?,
): Int {
    val zip = ZipOutputStream(out)
    val index = JSONArray()
    val photos = HashSet<String>()
    for (draft in drafts) {
        val collage = read(draft.id) ?: continue
        zip.putBytes(DRAFT_PREFIX + draft.id + TEXT_EXT, collage.toJson().toByteArray(), compress = true)
        preview(collage)?.let { zip.putBytes(PREVIEW_PREFIX + draft.id + PREVIEW_EXT, it, compress = false) }
        for (photo in collage.photos()) {
            if (!photos.add(photo.file)) continue
            // Photos are compressed already: deflating them again costs time and saves nothing.
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry(PHOTO_PREFIX + photo.file))
            photoFile(photo.file).inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        index.put(JSONObject().put("id", draft.id).put("savedAt", draft.savedAt))
    }
    val text = JSONObject().put("version", ARCHIVE_VERSION).put("drafts", index).toString()
    zip.putBytes(INDEX_ENTRY, text.toByteArray(), compress = true)
    zip.finish()
    return index.length()
}

private fun ZipOutputStream.putBytes(name: String, bytes: ByteArray, compress: Boolean) {
    setLevel(if (compress) Deflater.DEFAULT_COMPRESSION else Deflater.NO_COMPRESSION)
    putNextEntry(ZipEntry(name))
    write(bytes)
    closeEntry()
}

/** One draft an archive holds: its [id], and when it was saved, in milliseconds since the epoch. */
data class ArchivedDraft(val id: String, val savedAt: Long)

/**
 * An archive [writeDraftArchive] made, read from [file]; see [open]. Its
 * [drafts] are the ones it holds that can be read, the one saved last first.
 * Closed when done with.
 */
class DraftArchive private constructor(val file: File, private val zip: ZipFile) : Closeable {

    val drafts: List<ArchivedDraft> = readIndex()

    /** Draft [id]'s picture as a JPEG; null when the archive has none. */
    fun preview(id: String): ByteArray? =
        if (isDraftId(id)) runCatching { bytes(PREVIEW_PREFIX + id + PREVIEW_EXT, MAX_PREVIEW_BYTES) }.getOrNull() else null

    /** The collage draft [id] holds, a photo the archive lacks leaving its cell empty; null when it cannot be read. */
    fun collage(id: String): Collage? {
        if (!isDraftId(id)) return null
        val text = runCatching { bytes(DRAFT_PREFIX + id + TEXT_EXT, MAX_TEXT_BYTES) }.getOrNull() ?: return null
        return collageFromJson(String(text), ::hasPhoto)
    }

    private fun hasPhoto(name: String): Boolean =
        isPhotoName(name) && zip.getEntry(PHOTO_PREFIX + name)?.isDirectory == false

    /**
     * Photo [name] copied into [dest], whole or not at all, and no more than
     * a photo taken in may be; whether it was.
     */
    fun copyPhoto(name: String, dest: File): Boolean {
        if (!hasPhoto(name)) return false
        val temp = File(dest.parentFile, "${dest.name}.tmp")
        val ok = runCatching {
            val whole = zip.getInputStream(zip.getEntry(PHOTO_PREFIX + name)).use { input ->
                temp.outputStream().use { copyAtMost(input, it, MAX_IMPORT_BYTES) }
            }
            whole && temp.renameTo(dest)
        }.getOrDefault(false)
        if (!ok) temp.delete()
        return ok
    }

    override fun close() = zip.close()

    /** The drafts the index lists that are there to read, each once; none when there is no index of this format. */
    private fun readIndex(): List<ArchivedDraft> {
        val json = JSONObject(String(bytes(INDEX_ENTRY, MAX_TEXT_BYTES) ?: return emptyList()))
        if (json.optInt("version") != ARCHIVE_VERSION) return emptyList()
        val array = json.optJSONArray("drafts") ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { i -> array.optJSONObject(i) }
            .map { ArchivedDraft(it.optString("id"), it.optLong("savedAt").coerceAtLeast(0)) }
            .distinctBy { it.id }
            .filter { collage(it.id) != null }
            .sortedWith(compareByDescending<ArchivedDraft> { it.savedAt }.thenBy { it.id })
    }

    /** Entry [name]'s bytes; null when there is no such entry or it is over [limit]. */
    private fun bytes(name: String, limit: Long): ByteArray? {
        val entry = zip.getEntry(name)?.takeUnless { it.isDirectory } ?: return null
        val out = ByteArrayOutputStream()
        val whole = zip.getInputStream(entry).use { copyAtMost(it, out, limit) }
        return if (whole) out.toByteArray() else null
    }

    companion object {
        /** The archive in [file]; null when it is none, or holds no draft that can be read. */
        fun open(file: File): DraftArchive? {
            val zip = runCatching { ZipFile(file) }.getOrNull() ?: return null
            val archive = runCatching { DraftArchive(file, zip) }.getOrNull()
            if (archive == null || archive.drafts.isEmpty()) {
                runCatching { zip.close() }
                return null
            }
            return archive
        }
    }
}

/** What came of importing drafts: how many went in, and how many could not. */
data class DraftsImported(val done: Int, val failed: Int)

/**
 * Drafts [ids] of [archive] in among [store]'s, their photos into
 * [photoDir] where no copy by that name is there yet: names are random, so
 * one there is that very photo. A draft whose id is taken comes in as a copy
 * under a new one, so nothing here is written over; each keeps when it was
 * saved. One missing a photo the archive cannot give does not come in.
 */
fun importDrafts(archive: DraftArchive, ids: Collection<String>, store: DraftStore, photoDir: File): DraftsImported {
    val savedAt = archive.drafts.associate { it.id to it.savedAt }
    photoDir.mkdirs()
    var done = 0
    for (id in ids) {
        val ok = runCatching {
            val collage = archive.collage(id)
            collage != null &&
                collage.photos().all { File(photoDir, it.file).isFile || archive.copyPhoto(it.file, File(photoDir, it.file)) } &&
                store.write(if (store.has(id)) newDraftId() else id, collage, savedAt[id]?.takeIf { it > 0 })
        }.getOrDefault(false)
        if (ok) done++
    }
    return DraftsImported(done, ids.size - done)
}
