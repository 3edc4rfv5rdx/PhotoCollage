package xx.photocollage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Size

/**
 * One thing in a folder the user granted, as the app's own picker lists it:
 * a folder or an image, by its [docId] within the granted tree, its file
 * [name], and when it was last changed.
 */
data class FolderEntry(val docId: String, val name: String, val isFolder: Boolean, val modified: Long)

/** The URI [entry] is read through, in the granted [tree] it was listed from. */
fun FolderEntry.uriIn(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

/** [items] in the order a list of names reads — case aside — by the [name] of each: photos and folders alike. */
fun <T> byName(items: List<T>, name: (T) -> String): List<T> = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name))

/** [entries] as the picker shows them: the folders first, by name, then the images, the newest first. */
fun pickerOrder(entries: List<FolderEntry>): List<FolderEntry> =
    byName(entries.filter { it.isFolder }) { it.name } + entries.filterNot { it.isFolder }.sortedByDescending { it.modified }

/** Whether a file of [mime] type is one the picker offers: an image. */
fun isImageType(mime: String?): Boolean = mime != null && mime.startsWith("image/")

/** The folder a granted [tree] is itself, within it. */
fun rootDocId(tree: Uri): String? = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()

/**
 * The folders and images right in folder [docId] of granted [tree], in no
 * order; anything else there is left out, and so are hidden ones. Null when
 * it cannot be read: its grant is gone, or the folder is.
 */
fun listFolder(context: Context, tree: Uri, docId: String): List<FolderEntry>? = runCatching {
    val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
    val columns = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED,
    )
    val cursor = checkNotNull(context.contentResolver.query(children, columns, null, null, null)) { "no listing" }
    cursor.use { c ->
        buildList {
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                val mime = c.getString(2)
                val folder = mime == Document.MIME_TYPE_DIR
                if (!folder && !isImageType(mime)) continue
                add(FolderEntry(id, name, folder, if (c.isNull(3)) 0 else c.getLong(3)))
            }
        }
    }
}.getOrNull()

/**
 * The image at [uri] small, about [px] pixels a side, to be shown in the
 * picker: the provider's own thumbnail, else the file decoded small. Null
 * when neither can be had.
 */
fun pickerThumbnail(context: Context, uri: Uri, px: Int): Bitmap? {
    val resolver = context.contentResolver
    runCatching { resolver.loadThumbnail(uri, Size(px, px), null) }.getOrNull()?.let { return it }
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(long, px) }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()
}
