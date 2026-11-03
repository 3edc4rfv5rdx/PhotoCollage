package xx.photocollage

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * One image of the phone's media library, as the app's own picker lists it:
 * [id] in MediaStore, the [folder] it lies in as MediaStore gives it —
 * "DCIM/Camera/", empty at the top of the storage — when it was [added], in
 * seconds, and its file [name].
 */
data class MediaImage(val id: Long, val folder: String, val added: Long, val name: String = "")

/** [items] in the order a list of names reads — case aside — by the [name] of each: photos and folders alike. */
fun <T> byName(items: List<T>, name: (T) -> String): List<T> = items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, name))

/** A folder of the picker: its [path] as MediaStore gives it, its [images] newest first. */
data class MediaFolder(val path: String, val images: List<MediaImage>) {
    /** The folder as the user knows it: its last part, "/" for the top of the storage. */
    val label: String get() = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
}

/** [images] by the folder they lie in, each newest first, the folder with the newest image first. */
fun foldersOf(images: List<MediaImage>): List<MediaFolder> =
    images.groupBy { it.folder }
        .map { (path, inFolder) -> MediaFolder(path, inFolder.sortedByDescending { it.added }) }
        .sortedByDescending { it.images.first().added }

/** The URI the media library reads [image] by. */
fun MediaImage.uri(): Uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

/**
 * Every image the app may see in the media library: all of them with full
 * access, only those the user picked with access to some (Android 14 up).
 * Empty when it may see none or the library will not answer.
 */
fun loadMediaImages(context: Context): List<MediaImage> = runCatching {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DISPLAY_NAME,
    )
    context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)
        ?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(MediaImage(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2), cursor.getString(3).orEmpty()))
                }
            }
        }.orEmpty()
}.getOrDefault(emptyList())

/** The permissions the app's picker asks for: to read images, and on Android 14 up to be given only some. */
fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** How much of the media library the app may read. */
enum class MediaAccess { NONE, SOME, ALL }

fun mediaAccess(context: Context): MediaAccess {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_MEDIA_IMAGES) ->
            MediaAccess.ALL
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && granted(Manifest.permission.READ_EXTERNAL_STORAGE) ->
            MediaAccess.ALL
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> MediaAccess.SOME
        else -> MediaAccess.NONE
    }
}
