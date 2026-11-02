package xx.photocollage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/**
 * Where the app keeps its own copy of every photo it was given. A copy, not
 * the URI: the read grant another app hands over ends with the activity, and
 * a draft opened next week still needs the photo.
 */
fun photoDir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

/** The saved draft, beside the photo folder. */
fun draftFile(context: Context): File = File(context.filesDir, "draft.json")

/** The file a [Photo] names. */
fun photoFile(context: Context, photo: Photo): File = File(photoDir(context), photo.file)

/**
 * Copies the image at [uri] into [photoDir] and reads its upright size.
 * Null when it cannot be read or is no image; the half-written copy is not
 * left behind.
 */
fun importPhoto(context: Context, uri: Uri): Photo? {
    val name = "${UUID.randomUUID()}.img"
    val file = File(photoDir(context), name)
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { src -> file.outputStream().use { src.copyTo(it) } }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            file.delete()
            return null
        }
        val sideways = isSideways(orientationOf(file))
        Photo(
            file = name,
            width = if (sideways) bounds.outHeight else bounds.outWidth,
            height = if (sideways) bounds.outWidth else bounds.outHeight,
        )
    } catch (e: Exception) {
        file.delete()
        null
    }
}

/**
 * Deletes every photo copy not in [keep]. Run once per process, before
 * anything is imported: the copies of an unsaved collage from an earlier
 * run are of no more use, and nothing of this run is there yet to lose.
 */
fun forgetPhotosExcept(context: Context, keep: Set<String>) {
    photoDir(context).listFiles()?.forEach { if (it.name !in keep) it.delete() }
}

/**
 * Decodes [file] upright, at least [minLong] pixels on its long side where
 * the photo has them, but not much more: halved for as long as it stays
 * that big. Null when it cannot be read or memory runs out.
 */
fun decodePhoto(file: File, minLong: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    val long = maxOf(bounds.outWidth, bounds.outHeight)
    if (long <= 0) {
        null
    } else {
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(long, minLong) }
        BitmapFactory.decodeFile(file.path, options)?.let { upright(it, orientationOf(file)) }
    }
} catch (e: OutOfMemoryError) {
    null
} catch (e: Exception) {
    null
}

/** The largest power of two [long] can be divided by and still be at least [minLong]. */
internal fun sampleSize(long: Int, minLong: Int): Int {
    var sample = 1
    while (long / (sample * 2) >= minLong) sample *= 2
    return sample
}

private fun orientationOf(file: File): Int = try {
    ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (e: Exception) {
    ExifInterface.ORIENTATION_NORMAL
}

private fun isSideways(orientation: Int): Boolean = orientation in setOf(
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_ROTATE_270,
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_TRANSVERSE,
)

/** [bitmap] turned and mirrored the way EXIF [orientation] says it is meant to be seen. */
private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> matrix.apply { setRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> matrix.apply { setRotate(-90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (turned !== bitmap) bitmap.recycle()
    return turned
}
