package xx.photocollage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** How a saved collage is compressed: high enough that the photos in it lose nothing to the eye. */
private const val JPEG_QUALITY = 92

/** The folder under Pictures/ saved collages go into. */
private const val ALBUM = "PhotoCollage"

/** A canvas [longSide] pixels on its long side, the other side by [Collage.ratio]. */
fun exportSize(collage: Collage, longSide: Int): Pair<Int, Int> {
    val ratio = collage.ratio
    return if (ratio >= 1f) {
        longSide to max(1, (longSide / ratio).roundToInt())
    } else {
        max(1, (longSide * ratio).roundToInt()) to longSide
    }
}

/**
 * [collage] drawn at [longSide] pixels on its long side. Each photo is
 * decoded afresh at the size its cell shows it at — sharper than the
 * editor's copies — and let go as soon as it is drawn, so only one is in
 * memory at a time. Null when memory runs out.
 */
fun renderCollage(context: Context, collage: Collage, longSide: Int): Bitmap? {
    val (width, height) = exportSize(collage, longSide)
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
    ) { photo -> decodePhoto(photoFile(context, photo), needed[photo] ?: longSide) }
    return bitmap
}

/** Puts [bitmap] into Pictures/PhotoCollage as a JPEG, where every gallery sees it. Its URI, or null when that failed. */
fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${exportName()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
        // Hidden from other apps until it is written whole.
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
    return try {
        val written = resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        check(written == true) { "nothing written" }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        null
    }
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

/** A saved collage's name: the app's and the moment, so the gallery sorts them by time. */
private fun exportName(): String =
    "PhotoCollage_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT))
