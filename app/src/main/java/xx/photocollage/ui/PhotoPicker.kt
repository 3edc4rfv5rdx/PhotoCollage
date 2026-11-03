package xx.photocollage.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.photocollage.AppSettings
import xx.photocollage.MediaAccess
import xx.photocollage.MediaFolder
import xx.photocollage.MediaImage
import xx.photocollage.R
import xx.photocollage.byName
import xx.photocollage.foldersOf
import xx.photocollage.loadMediaImages
import xx.photocollage.mediaAccess
import xx.photocollage.mediaPermissions
import xx.photocollage.uri

/** The side, in pixels, of the thumbnails the system is asked for: enough for a cell of the grid on any phone. */
private const val THUMB_PX = 320

/** How many bytes of thumbnails are kept decoded, so a folder scrolled back through is not asked for again. */
private const val THUMB_CACHE_BYTES = 32 * 1024 * 1024

/**
 * The app's own picker over the phone's media library, by folder: the
 * folders first, newest first, then the photos of the one opened. With
 * [multiple] each tap marks a photo — in the order tapped, the order they go
 * into the collage — and Add hands them all over, from whichever folders;
 * otherwise a tap hands that one over. Other apps opens the system's picker,
 * for PhotoBox and the cloud. Back goes from a folder to the folders, and
 * from there out. Drawn over the editor, filling the window.
 */
@Composable
fun PhotoPicker(multiple: Boolean, onPicked: (List<Uri>) -> Unit, onOtherApps: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Bumped when access changes, so the library is read again.
    var reads by remember { mutableIntStateOf(0) }
    val folders by produceState<List<MediaFolder>?>(null, reads) {
        value = withContext(Dispatchers.IO) { foldersOf(loadMediaImages(context)) }
    }
    val access = remember(reads) { mediaAccess(context) }
    val askMore = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reads++ }
    var openPath by rememberSaveable { mutableStateOf<String?>(null) }
    var picked by rememberSaveable { mutableStateOf(listOf<Long>()) }
    val open = folders?.firstOrNull { it.path == openPath }
    val asList by AppSettings.pickerList.collectAsState()
    val thumbs = remember {
        object : LruCache<Long, Bitmap>(THUMB_CACHE_BYTES) {
            override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
        }
    }

    fun hand(ids: List<Long>) {
        val all = folders.orEmpty().flatMap { it.images }.associateBy { it.id }
        onPicked(ids.mapNotNull { all[it]?.uri() })
    }

    // Over the editor, which would show through otherwise.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AppScreen(
            title = open?.label ?: stringResource(R.string.choose_photos),
            onBack = { if (openPath != null) openPath = null else onDismiss() },
            actions = {
                // Folders and photos alike: pictures with their names, or a list of names with small pictures.
                LargeIconButton(
                    if (asList) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                    stringResource(if (asList) R.string.show_as_grid else R.string.show_as_list),
                    { AppSettings.setPickerList(context, !asList) },
                )
                if (multiple && picked.isNotEmpty()) {
                    Button(onClick = { hand(picked) }) { Text("${stringResource(R.string.add)} (${picked.size})") }
                } else if (open == null) {
                    TextButton(onClick = onOtherApps) { Text(stringResource(R.string.other_apps)) }
                }
            },
        ) {
            // With access to some photos only, a way to give more.
            if (access == MediaAccess.SOME && open == null) {
                TextButton(onClick = { askMore.launch(mediaPermissions()) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text(stringResource(R.string.allow_more_photos))
                }
            }
            val list = folders
            when {
                list == null -> Unit
                list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_photos), style = MaterialTheme.typography.titleMedium)
                }
                open == null && asList -> {
                    // A list is read by name; the grid stays newest first.
                    val named = remember(list) { byName(list) { it.label } }
                    LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(named, key = { it.path }) { folder -> FolderRow(folder, thumbs) { openPath = folder.path } }
                    }
                }
                open == null -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(FOLDER_TILE),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.path }) { folder ->
                        FolderTile(folder, thumbs) { openPath = folder.path }
                    }
                }
                else -> {
                    // Where a photo stands in the order picked, from 1; null while it is not picked.
                    fun orderOf(image: MediaImage): Int? = picked.indexOf(image.id).takeIf { it >= 0 }?.plus(1)
                    fun tap(image: MediaImage) {
                        when {
                            !multiple -> hand(listOf(image.id))
                            image.id in picked -> picked = picked - image.id
                            else -> picked = picked + image.id
                        }
                    }
                    if (asList) {
                        // A list is read by name; the grid stays newest first, as photos are taken.
                        val named = remember(open) { byName(open.images) { it.name } }
                        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(named, key = { it.id }) { image -> PhotoRow(image, thumbs, orderOf(image)) { tap(image) } }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(PHOTO_TILE),
                            contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(open.images, key = { it.id }) { image -> PhotoTile(image, thumbs, orderOf(image)) { tap(image) } }
                        }
                    }
                }
            }
        }
    }
    // A folder that went away while open — its photos deleted elsewhere — leaves the folders showing.
    LaunchedEffect(folders, openPath) { if (folders != null && openPath != null && open == null) openPath = null }
}

private val FOLDER_TILE = 150.dp
private val PHOTO_TILE = 96.dp
private val MARK = 28.dp
private val ROW_THUMB = 48.dp

/** A folder: its newest photo, its name, and how many photos it holds. */
@Composable
private fun FolderTile(folder: MediaFolder, thumbs: LruCache<Long, Bitmap>, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Thumbnail(folder.images.first(), thumbs, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(
            folder.label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(folder.images.size.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/** A folder in the list: its newest photo small, its name, and under it its path and how many photos it holds. */
@Composable
private fun FolderRow(folder: MediaFolder, thumbs: LruCache<Long, Bitmap>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(folder.images.first(), thumbs, Modifier.size(ROW_THUMB))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(folder.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${folder.path.trimEnd('/').ifEmpty { "/" }} · ${folder.images.size}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A photo of the open folder in the grid, its name under it, with its place in the order picked on it once it is marked. */
@Composable
private fun PhotoTile(image: MediaImage, thumbs: LruCache<Long, Bitmap>, order: Int?, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(if (order != null) Modifier.border(3.dp, accent) else Modifier),
        ) {
            Thumbnail(image, thumbs, Modifier.fillMaxSize())
            if (order != null) OrderMark(order, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            image.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A photo of the open folder in the list: a small picture and its name, the place in the order picked at the end once it is marked. */
@Composable
private fun PhotoRow(image: MediaImage, thumbs: LruCache<Long, Bitmap>, order: Int?, onClick: () -> Unit) {
    val marked = if (order != null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(marked)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(image, thumbs, Modifier.size(ROW_THUMB))
        Text(
            image.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
        )
        if (order != null) OrderMark(order)
    }
}

/** The place, from 1, of a photo in the order picked: a number on an accent disc. */
@Composable
private fun OrderMark(order: Int, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.size(MARK).background(accent, CircleShape), contentAlignment = Alignment.Center) {
        Text(order.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

/** [image]'s thumbnail as the system makes it, cropped to [modifier]'s box; the box stays empty until it comes, or if it never does. */
@Composable
private fun Thumbnail(image: MediaImage, thumbs: LruCache<Long, Bitmap>, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState(thumbs.get(image.id), image.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(image.uri(), Size(THUMB_PX, THUMB_PX), null) }.getOrNull()
            }?.also { thumbs.put(image.id, it) }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
