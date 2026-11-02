package xx.photocollage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/** How a saved collage is compressed: high enough that the photos in it lose nothing to the eye. */
private const val JPEG_QUALITY = 92

/** The folder under Pictures/ saved collages go into unless Settings names another. */
private const val ALBUM = "PhotoCollage"

/** That folder as the user knows it. */
const val DEFAULT_FOLDER_LABEL = "Pictures/$ALBUM"

/**
 * [collage] drawn at its [savedSize]. Each photo is
 * decoded afresh at the size its cell shows it at — sharper than the
 * editor's copies — and let go as soon as it is drawn, so only one is in
 * memory at a time. Null when memory runs out.
 */
fun renderCollage(context: Context, collage: Collage): Bitmap? {
    val (width, height) = collage.savedSize()
    val bitmap = try {
        createBitmap(width, height)
    } catch (e: OutOfMemoryError) {
        return null
    }
    val layout = layoutOf(collage, width.toFloat(), height.toFloat())
    // The size each photo is needed at, by its cell: what its framing shows it at.
    val needed = HashMap<Photo, Int>()
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = leaf.photo ?: return@forEachIndexed
        val box = layout.cells[index]
        val (w, h) = cell.clamped(box.width, box.height).shownSize(box.width, box.height)
        needed[cell.photo] = max(needed[cell.photo] ?: 0, ceil(max(w, h)).toInt())
    }
    drawCollage(
        Canvas(bitmap),
        collage,
        width.toFloat(),
        height.toFloat(),
        layout,
        onDrawn = { it.recycle() },
    ) { photo -> decodePhoto(photoFile(context, photo), needed[photo] ?: max(width, height)) }
    return bitmap
}

/**
 * Saves [bitmap] as a JPEG into [folder] — a folder the user picked in
 * Settings, by its tree URI — or into Pictures/PhotoCollage when null.
 * Whether it was written.
 */
fun saveImage(context: Context, bitmap: Bitmap, folder: Uri?): Boolean =
    if (folder == null) saveToPictures(context, bitmap) else saveToFolder(context, bitmap, folder)

/** The folder [saveImage] writes into, as the user knows it: the path within its storage. */
fun folderLabel(folder: Uri?): String {
    if (folder == null) return DEFAULT_FOLDER_LABEL
    // "primary:Pictures/Trips" on the phone's own storage, "1A2B-3C4D:DCIM" on a card.
    val id = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull() ?: return folder.toString()
    return id.substringAfter(':').ifEmpty { "/" }
}

/** Through MediaStore, which needs no permission for its own Pictures/ folder. */
private fun saveToPictures(context: Context, bitmap: Bitmap): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${exportName()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        // Hidden from other apps until it is written whole.
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        writeJpeg(context, bitmap, uri)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/**
 * Into a folder the user granted through the system's folder picker. False
 * when the grant is gone or the folder is: the file is not made elsewhere
 * behind their back.
 */
private fun saveToFolder(context: Context, bitmap: Bitmap, tree: Uri): Boolean {
    val resolver = context.contentResolver
    val doc = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        DocumentsContract.createDocument(resolver, parent, "image/jpeg", "${exportName()}.jpg")
    } catch (e: Exception) {
        null
    } ?: return false
    return try {
        writeJpeg(context, bitmap, doc)
        true
    } catch (e: Exception) {
        runCatching { DocumentsContract.deleteDocument(resolver, doc) }
        false
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
    File(dir, "${exportName()}.jpg").also { file ->
        dir.listFiles()?.forEach { it.delete() }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) }
    }
} catch (e: Exception) {
    null
}

/** A saved collage's name: the app's and the moment, so a folder sorts them by time. */
private fun exportName(): String =
    "PhotoCollage-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
