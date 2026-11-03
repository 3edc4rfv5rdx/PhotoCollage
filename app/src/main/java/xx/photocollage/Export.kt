package xx.photocollage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How a saved collage is compressed: high enough that the photos in it lose nothing to the eye. */
private const val JPEG_QUALITY = 92

/** The folder under Pictures/ saved collages go into unless Settings names another. */
private const val ALBUM = "PhotoCollage"

/** That folder as the user knows it. */
const val DEFAULT_FOLDER_LABEL = "Pictures/$ALBUM"

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
    // The size each photo is needed at, by its cell: what its framing shows it at.
    val needed = HashMap<Photo, Int>()
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = leaf.photo ?: return@forEachIndexed
        val box = layout.frameOf(index)
        val (w, h) = cell.clamped(box.width, box.height).shownSize(box.width, box.height)
        needed[cell.photo] = max(needed[cell.photo] ?: 0, ceil(max(w, h)).toInt())
    }
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

    /** The folder picked in Settings takes no file any more: its grant is gone, or the folder is. */
    data object FolderUnavailable : SaveResult

    /** Not written, for any other reason. */
    data object Failed : SaveResult
}

/**
 * Saves [bitmap] as a JPEG into [folder] — a folder the user picked in
 * Settings, by its tree URI — or into Pictures/PhotoCollage when null.
 */
fun saveImage(context: Context, bitmap: Bitmap, folder: Uri?, fileName: String): SaveResult {
    if (folder != null) return saveToFolder(context, bitmap, folder, fileName)
    val uri = saveToPictures(context, bitmap, fileName) ?: return SaveResult.Failed
    return SaveResult.Saved(displayName(context, uri) ?: fileName)
}

/** The name the file at [uri] goes by, as its provider says; null if it will not say. */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()

/** The folder [saveImage] writes into, as the user knows it: the path within its storage. */
fun folderLabel(folder: Uri?): String {
    if (folder == null) return DEFAULT_FOLDER_LABEL
    // "primary:Pictures/Trips" on the phone's own storage, "1A2B-3C4D:DCIM" on a card.
    val id = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull() ?: return folder.toString()
    return id.substringAfter(':').ifEmpty { "/" }
}

/** Through MediaStore, which needs no permission for its own Pictures/ folder. The file written, or null. */
private fun saveToPictures(context: Context, bitmap: Bitmap, fileName: String): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        // Hidden from other apps until it is written whole.
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    // Throws, not only gives null, when the storage is not there.
    val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return null
    return try {
        writeJpeg(context, bitmap, uri)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        null
    }
}

/**
 * Into a folder the user granted through the system's folder picker. A file
 * that cannot be made there — the grant gone, or the folder — is
 * [SaveResult.FolderUnavailable], not made elsewhere behind their back.
 */
private fun saveToFolder(context: Context, bitmap: Bitmap, tree: Uri, fileName: String): SaveResult {
    val resolver = context.contentResolver
    val doc = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(resolver, parent, "image/jpeg", fileName)
    } catch (e: Exception) {
        null
    } ?: return SaveResult.FolderUnavailable
    return try {
        writeJpeg(context, bitmap, doc)
        SaveResult.Saved(displayName(context, doc) ?: fileName)
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        SaveResult.Failed
    }
}

private fun writeJpeg(context: Context, bitmap: Bitmap, uri: Uri) {
    val written = context.contentResolver.openOutputStream(uri)?.use {
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
    }
    check(written == true) { "nothing written" }
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
fun defaultImageName(): String =
    "PhotoCollage-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
