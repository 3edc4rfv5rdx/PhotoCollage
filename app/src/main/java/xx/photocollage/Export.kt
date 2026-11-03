package xx.photocollage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How a saved collage is compressed: high enough that the photos in it lose nothing to the eye. */
private const val JPEG_QUALITY = 92

/** The app's folder under Documents/ that the folder picker offers first. */
private const val ALBUM = "PhotoCollage"

/** What came of drawing a collage to be saved. */
sealed interface Rendered {
    data class Done(val bitmap: Bitmap) : Rendered

    /** No memory for the image itself at its size. */
    data object TooLarge : Rendered

    /** [count] photos could not be read at any size: the image would have lacked them, so there is none. */
    data class Unreadable(val count: Int) : Rendered
}

/**
 * [collage] drawn at its [savedSize]. Each photo is
 * decoded afresh at the size its cell shows it at — sharper than the
 * editor's copies — and let go as soon as it is drawn, so only one is in
 * memory at a time. A photo that will not decode at that size is tried
 * smaller; one that will not at any is no image at all, rather than an
 * image with an empty cell where the editor showed a photo.
 */
fun renderCollage(context: Context, collage: Collage): Rendered {
    val (width, height) = collage.savedSize()
    val bitmap = try {
        createBitmap(width, height)
    } catch (e: OutOfMemoryError) {
        return Rendered.TooLarge
    }
    val layout = layoutOf(collage, width.toFloat(), height.toFloat())
    val needed = neededSizes(collage, layout)
    val unreadable = HashSet<Photo>()
    drawCollage(
        Canvas(bitmap),
        collage,
        width.toFloat(),
        height.toFloat(),
        layout,
        onDrawn = { it.recycle() },
        bitmapOf = notingUnreadable(unreadable) { photo ->
            // Never more than the photo has: past that every size is the same whole photo again.
            val start = min(needed[photo] ?: max(width, height), max(photo.width, photo.height))
            decodeShrinking(start) { decodePhoto(photoFile(context, photo), it) }
        },
    )
    if (unreadable.isNotEmpty()) {
        bitmap.recycle()
        return Rendered.Unreadable(unreadable.size)
    }
    return Rendered.Done(bitmap)
}

/**
 * The long side each photo of [collage] is needed at, drawn by [layout]: what
 * its cell's or overlay's framing shows it at, the larger where it is in two.
 */
private fun neededSizes(collage: Collage, layout: CollageLayout): Map<Photo, Int> {
    val needed = HashMap<Photo, Int>()
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = leaf.photo ?: return@forEachIndexed
        val box = layout.frameOf(index)
        val (w, h) = cell.clamped(box.width, box.height).shownSize(box.width, box.height)
        needed[cell.photo] = max(needed[cell.photo] ?: 0, ceil(max(w, h)).toInt())
    }
    collage.overlays.forEachIndexed { index, overlay ->
        val cell = overlay.photo ?: return@forEachIndexed
        val box = layout.overlays.getOrNull(index)?.photoFrame(layout.blend) ?: return@forEachIndexed
        val (w, h) = cell.clamped(box.width, box.height).shownSize(box.width, box.height)
        needed[cell.photo] = max(needed[cell.photo] ?: 0, ceil(max(w, h)).toInt())
    }
    return needed
}

/**
 * [collage] drawn [longSide] pixels on its long side, to be looked at, not
 * saved: each photo decoded about as small as it is shown, and once, though
 * a blend asks for it twice; one that will not decode leaves its cell empty.
 * Null without the memory for it.
 */
fun renderPreview(context: Context, collage: Collage, longSide: Int): Bitmap? {
    val (width, height) = collage.savedSize(longSide)
    val layout = layoutOf(collage, width.toFloat(), height.toFloat())
    val needed = neededSizes(collage, layout)
    val decoded = HashMap<Photo, Bitmap?>()
    return try {
        val bitmap = createBitmap(width, height)
        drawCollage(Canvas(bitmap), collage, width.toFloat(), height.toFloat(), layout) { photo ->
            // Never below the size a decode is worth making at, nor at nothing for a cell squeezed flat.
            val long = (needed[photo] ?: longSide).coerceAtLeast(MIN_DECODE_LONG)
            decoded.getOrPut(photo) { decodePhoto(photoFile(context, photo), long) }
        }
        bitmap
    } catch (e: OutOfMemoryError) {
        null
    } finally {
        decoded.values.forEach { it?.recycle() }
    }
}

/** [collage] as a JPEG [longSide] pixels on its long side, as [renderPreview] draws it; null when it cannot be drawn. */
fun previewJpeg(context: Context, collage: Collage, longSide: Int): ByteArray? {
    val bitmap = renderPreview(context, collage, longSide) ?: return null
    return try {
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    } finally {
        bitmap.recycle()
    }
}

/**
 * [decode] for a render, noting in [unreadable] each photo it could not give
 * the first time it was asked for — when its cell is drawn. The blend asks
 * again for the bands past the cell; a failure then costs a fade, not the
 * photo, so it does not make the photo unreadable.
 */
internal fun <T : Any> notingUnreadable(unreadable: MutableSet<Photo>, decode: (Photo) -> T?): (Photo) -> T? {
    val asked = HashSet<Photo>()
    return { photo ->
        val first = asked.add(photo)
        decode(photo).also { if (it == null && first) unreadable += photo }
    }
}

/** What came of saving an image. */
sealed interface SaveResult {
    /** Written, under [name]: the system numbers it when the name asked for is taken. */
    data class Saved(val name: String) : SaveResult

    /** The app's folder takes no file any more: its grant is gone, or the folder is. */
    data object FolderUnavailable : SaveResult

    /** Not written, for any other reason. */
    data object Failed : SaveResult
}

/** Saves [bitmap] as a JPEG into [folder], the app's folder by its tree URI; see [writeToFolder]. */
fun saveImage(context: Context, bitmap: Bitmap, folder: Uri, fileName: String): SaveResult =
    writeToFolder(context, folder, fileName, "image/jpeg") { out ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) { "nothing written" }
    }

/** The name the file at [uri] goes by, as its provider says; null if it will not say. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** A folder granted through the system's folder picker, as the user knows it: the path within its storage. */
fun treeLabel(tree: Uri): String {
    // "primary:Pictures/Trips" on the phone's own storage, "1A2B-3C4D:DCIM" on a card.
    val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return tree.toString()
    return id.substringAfter(':').ifEmpty { "/" }
}

/** The provider of the phone's own storage, as the system's file and folder pickers show it. */
const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** The folder or file at [path] on the phone's own storage — "DCIM", say — as the system's pickers name it. */
fun storageDocument(path: String): Uri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$path")

/**
 * Where the folder picker opens to choose the app's folder:
 * Documents/PhotoCollage when it is there, else Documents, to make it in.
 * Only where the picker starts: the folder is the one the user then grants.
 */
fun folderHint(): Uri {
    @Suppress("DEPRECATION") // Only to ask whether the folder is there; nothing is read or written through it.
    val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    val ours = runCatching { File(documents, ALBUM).isDirectory }.getOrDefault(false)
    val path = if (ours) "${Environment.DIRECTORY_DOCUMENTS}/$ALBUM" else Environment.DIRECTORY_DOCUMENTS
    return storageDocument(path)
}

/** An archive of drafts in the app's folder: where it is, its name, its size in bytes, and when it was last changed. */
data class ArchiveFile(val uri: Uri, val name: String, val size: Long, val modified: Long)

/**
 * The .zip files right in [tree] — a folder granted through the system's
 * folder picker — the newest first; null when the folder cannot be read:
 * its grant is gone, or the folder is.
 */
fun listArchives(context: Context, tree: Uri): List<ArchiveFile>? = runCatching {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    val columns = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    val found = ArrayList<ArchiveFile>()
    val cursor = checkNotNull(context.contentResolver.query(children, columns, null, null, null)) { "no listing" }
    cursor.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR || !name.endsWith(".zip", ignoreCase = true)) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            found += ArchiveFile(uri, name, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
        }
    }
    found.sortedWith(compareByDescending<ArchiveFile> { it.modified }.thenBy { it.name })
}.getOrNull()

/**
 * A file named [fileName], of type [mime], made in [tree] — a folder the
 * user granted through the system's folder picker — and filled by [write];
 * gone again if [write] throws. A file that cannot be made there — the grant
 * gone, or the folder — is [SaveResult.FolderUnavailable], not made
 * elsewhere behind their back.
 */
fun writeToFolder(context: Context, tree: Uri, fileName: String, mime: String, write: (OutputStream) -> Unit): SaveResult {
    val resolver = context.contentResolver
    val doc = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(resolver, parent, mime, fileName)
    } catch (e: Exception) {
        null
    } ?: return SaveResult.FolderUnavailable
    return try {
        checkNotNull(resolver.openOutputStream(doc)) { "no stream" }.use(write)
        SaveResult.Saved(displayName(context, doc) ?: fileName)
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        SaveResult.Failed
    }
}

/**
 * Writes [bitmap] into the cache for Share, over the one shared before:
 * only the latest is ever handed out. The file, or null when that failed.
 */
fun writeForSharing(context: Context, bitmap: Bitmap): File? = try {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    File(dir, "${defaultImageName()}.jpg").also { file ->
        dir.listFiles()?.forEach { it.delete() }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) }
    }
} catch (e: Exception) {
    null
}

/**
 * [input], as typed for a saved collage's name, made a file name: the
 * characters no folder takes dropped, ".jpg" put after it unless it ends in a
 * JPEG's extension already, and [defaultImageName] if nothing is left. A
 * name taken in the folder is not overwritten: the system numbers the new one.
 */
fun jpegFileName(input: String): String {
    val clean = input.filterNot { it in FORBIDDEN_IN_NAMES || it.isISOControl() }.trim().trim('.')
    val name = clean.ifEmpty { defaultImageName() }
    val lower = name.lowercase(Locale.ROOT)
    return if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) name else "$name.jpg"
}

private const val FORBIDDEN_IN_NAMES = "/\\:*?\"<>|"

/** A saved collage's name unless the user gives another: the app's and the moment, so a folder sorts them by time. */
fun defaultImageName(): String = "PhotoCollage-" + timeStamp()

/** The name an archive of the drafts is made under: the app's, what it holds, and the moment. */
fun draftArchiveName(): String = "PhotoCollage-drafts-${timeStamp()}.zip"

private fun timeStamp(): String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
