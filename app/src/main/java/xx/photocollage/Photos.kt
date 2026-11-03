package xx.photocollage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where the app keeps its own copy of every photo it was given. A copy, not
 * the URI: the read grant another app hands over ends with the activity, and
 * a draft opened next week still needs the photo.
 */
fun photoDir(context: Context): File = File(context.filesDir, "photos").apply { mkdirs() }

/** The saved draft, beside the photo folder. */
fun draftFile(context: Context): File = File(context.filesDir, "draft.json")

/** The collage being edited, written after every step and opened again at the next start. */
fun sessionFile(context: Context): File = File(context.filesDir, "current.json")

/** The file a [Photo] names. */
fun photoFile(context: Context, photo: Photo): File = File(photoDir(context), photo.file)

/** The most an import copies in: a stream longer than that is taken for no photo, before it fills the phone. */
const val MAX_IMPORT_BYTES = 500L * 1024 * 1024

/**
 * Copies the image at [uri] into [photoDir] and reads its upright size.
 * Null when it cannot be read, is no image or is over [MAX_IMPORT_BYTES]; the
 * half-written copy is not left behind.
 */
fun importPhoto(context: Context, uri: Uri): Photo? {
    val name = "${UUID.randomUUID()}.img"
    val file = File(photoDir(context), name)
    return try {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val whole = input.use { src -> file.outputStream().use { copyAtMost(src, it, MAX_IMPORT_BYTES) } }
        if (!whole) {
            file.delete()
            return null
        }
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
 * Copies [input] into [output], at most [limit] bytes: true when that was all
 * of it, false when there was more — the copy then stops, no byte past
 * [limit] written.
 */
internal fun copyAtMost(input: InputStream, output: OutputStream, limit: Long): Boolean {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        total += read
        if (total > limit) return false
        output.write(buffer, 0, read)
    }
}

/**
 * Deletes every file in [dir] not in [keep] — the draft's photos and the
 * collage left off at's — the first time [done] is seen unset, and sets it.
 * Meant to run before the first copy of a run is made into [dir]: the other
 * copies of an earlier run are of no more use, and nothing of this run is
 * there yet to lose. Whatever calls it later deletes nothing.
 */
fun forgetPhotosOnce(done: AtomicBoolean, dir: File, keep: () -> Set<String>) {
    if (!done.compareAndSet(false, true)) return
    val kept = keep()
    dir.listFiles()?.forEach { if (it.name !in kept) it.delete() }
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

/** The long side below which [decodeShrinking] stops asking for less: a photo smaller than that is no photo to show. */
const val MIN_DECODE_LONG = 256

/**
 * What [decode] makes at [minLong] pixels on the long side, or failing that —
 * memory short, most likely — at half of it, and so on down to
 * [MIN_DECODE_LONG]: a photo a little soft beats a cell left empty. Null when
 * every size failed.
 */
internal fun <T : Any> decodeShrinking(minLong: Int, decode: (Int) -> T?): T? {
    var target = minLong
    while (true) {
        decode(target)?.let { return it }
        if (target / 2 < MIN_DECODE_LONG) return null
        target /= 2
    }
}

/** Whether a decode that last failed at [failedAt], if ever, may be tried again at [now]: [wait] after the failure. */
internal fun retryDue(failedAt: Long?, now: Long, wait: Long): Boolean = failedAt == null || now - failedAt >= wait

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
    val turned = try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (e: OutOfMemoryError) {
        // Let go of the photo at once, so a try at a smaller size has its room.
        bitmap.recycle()
        throw e
    }
    if (turned !== bitmap) bitmap.recycle()
    return turned
}
