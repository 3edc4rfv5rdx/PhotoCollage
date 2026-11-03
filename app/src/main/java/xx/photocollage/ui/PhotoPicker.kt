package xx.photocollage.ui

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xx.photocollage.AppSettings
import xx.photocollage.FolderEntry
import xx.photocollage.R
import xx.photocollage.byName
import xx.photocollage.listFolder
import xx.photocollage.pickerOrder
import xx.photocollage.pickerThumbnail
import xx.photocollage.rootDocId
import xx.photocollage.storageDocument
import xx.photocollage.treeLabel
import xx.photocollage.uriIn

/** The side, in pixels, of the thumbnails asked for: enough for a cell of the grid on any phone. */
private const val THUMB_PX = 320

/** How many bytes of thumbnails are kept decoded, so a folder scrolled back through is not asked for again. */
private const val THUMB_CACHE_BYTES = 32 * 1024 * 1024

/** Where the folder picker opens to add a folder of photos: the camera's. */
private val PHOTO_FOLDER_HINT: Uri = storageDocument("DCIM")

/** One step down into a granted folder: the [tree] granted, the folder's [docId] in it, and its [name]. */
private data class Place(val tree: Uri, val docId: String, val name: String)

/** [Place] as one saveable string, each part escaped so none can hold the separator. */
private fun Place.encoded(): String = listOf(tree.toString(), docId, name).joinToString("\n") { Uri.encode(it) }

private fun placeOf(text: String): Place? {
    val parts = text.split("\n").map { Uri.decode(it) }
    return if (parts.size == 3) Place(parts[0].toUri(), parts[1], parts[2]) else null
}

/**
 * The app's own picker over the folders the user granted it, as files and
 * folders: the granted folders first, by name, then a folder's folders and
 * photos, as deep as they go. With [multiple] each tap marks a photo — in
 * the order tapped, the order they go into the collage — and Add hands them
 * all over, from whichever folders; otherwise a tap hands that one over.
 * Other apps opens the system's picker, for PhotoBox and the cloud. Back
 * goes up a folder, and from the granted ones out. Drawn over the editor,
 * filling the window.
 */
@Composable
fun PhotoPicker(multiple: Boolean, onPicked: (List<Uri>) -> Unit, onOtherApps: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val roots by AppSettings.photoFolders.collectAsState()
    // The folders gone down into from a granted one; empty at the granted ones themselves.
    var trail by rememberSaveable { mutableStateOf(listOf<String>()) }
    val here = remember(trail) { trail.lastOrNull()?.let(::placeOf) }
    // The photos marked, by URI, in the order tapped.
    var picked by rememberSaveable { mutableStateOf(listOf<String>()) }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    val asList by AppSettings.pickerList.collectAsState()
    val thumbs = remember {
        object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }
    // A grant that cannot be kept is no folder to show.
    val addFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { runCatching { AppSettings.addPhotoFolder(context, it) } }
    }
    val listing by produceState<Loaded<List<FolderEntry>>>(Loaded.Loading, here) {
        value = Loaded.Loading
        val place = here ?: return@produceState
        value = loadedOf(withContext(Dispatchers.IO) { listFolder(context, place.tree, place.docId)?.let(::pickerOrder) })
    }

    fun open(place: Place) {
        trail = trail + place.encoded()
    }

    fun tap(photo: Uri) {
        val key = photo.toString()
        when {
            !multiple -> onPicked(listOf(photo))
            key in picked -> picked = picked - key
            else -> picked = picked + key
        }
    }

    // Where a photo stands in the order picked, from 1; null while it is not picked.
    fun orderOf(photo: Uri): Int? = picked.indexOf(photo.toString()).takeIf { it >= 0 }?.plus(1)

    // Over the editor, which would show through otherwise.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AppScreen(
            title = here?.name ?: stringResource(R.string.choose_photos),
            onBack = { if (trail.isNotEmpty()) trail = trail.dropLast(1) else onDismiss() },
            actions = {
                // Folders and photos alike: pictures with their names, or a list of names with small pictures.
                if (here != null) {
                    LargeIconButton(
                        if (asList) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                        stringResource(if (asList) R.string.show_as_grid else R.string.show_as_list),
                        { AppSettings.setPickerList(context, !asList) },
                    )
                }
                if (multiple && picked.isNotEmpty()) {
                    Button(onClick = { onPicked(picked.map { it.toUri() }) }) {
                        Text("${stringResource(R.string.add)} (${picked.size})")
                    }
                } else if (here == null) {
                    TextButton(onClick = onOtherApps) { Text(stringResource(R.string.other_apps)) }
                }
            },
        ) {
            if (here == null) {
                Roots(
                    roots,
                    onOpen = { root -> rootDocId(root)?.let { open(Place(root, it, rootName(root))) } },
                    onRemove = { removing = it.toString() },
                    onAdd = { addFolder.launch(PHOTO_FOLDER_HINT) },
                )
            } else {
                FolderContents(
                    here.tree,
                    listing,
                    asList,
                    thumbs,
                    ::orderOf,
                    onFolder = { open(Place(here.tree, it.docId, it.name)) },
                    onPhoto = ::tap,
                )
            }
        }
    }

    removing?.let { text ->
        val root = text.toUri()
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.remove_photo_folder) + "?") },
            text = { Text(treeLabel(root)) },
            confirmButton = {
                DialogConfirmButton(stringResource(R.string.remove_photo_folder)) {
                    removing = null
                    AppSettings.removePhotoFolder(context, root)
                }
            },
            dismissButton = { DialogDismissButton(stringResource(R.string.cancel)) { removing = null } },
        )
    }
}

/** A granted folder's name as the user knows it: the last part of its path. */
private fun rootName(root: Uri): String = treeLabel(root).trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** The granted folders, by path, each to open or take off the list, and a way to add one. */
@Composable
private fun Roots(roots: List<Uri>, onOpen: (Uri) -> Unit, onRemove: (Uri) -> Unit, onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (roots.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_photo_folders), style = MaterialTheme.typography.titleMedium)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(roots, key = { it.toString() }) { root ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(root) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(ROW_THUMB))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(rootName(root), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(treeLabel(root), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onRemove(root) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.remove_photo_folder))
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.End) {
            DialogConfirmButton(stringResource(R.string.add_folder), onClick = onAdd)
        }
    }
}

/**
 * What a folder of granted [tree] holds, as [listing] found it: its folders
 * by name, then its photos newest first — or all by name as a list.
 */
@Composable
private fun FolderContents(
    tree: Uri,
    listing: Loaded<List<FolderEntry>>,
    asList: Boolean,
    thumbs: LruCache<String, Bitmap>,
    orderOf: (Uri) -> Int?,
    onFolder: (FolderEntry) -> Unit,
    onPhoto: (Uri) -> Unit,
) {
    when (listing) {
        Loaded.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Loaded.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.folder_unavailable), style = MaterialTheme.typography.titleMedium)
        }
        is Loaded.Found -> {
            val entries = listing.value
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_photos), style = MaterialTheme.typography.titleMedium)
                }
            } else if (asList) {
                // A list is read by name; the grid keeps the photos newest first, as they are taken.
                val named = remember(entries) { entries.filter { it.isFolder } + byName(entries.filterNot { it.isFolder }) { it.name } }
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(named, key = { it.docId }) { entry ->
                        val uri = entry.uriIn(tree)
                        if (entry.isFolder) {
                            EntryRow(entry, uri, thumbs, order = null) { onFolder(entry) }
                        } else {
                            EntryRow(entry, uri, thumbs, orderOf(uri)) { onPhoto(uri) }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(PHOTO_TILE),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.docId }) { entry ->
                        val uri = entry.uriIn(tree)
                        if (entry.isFolder) {
                            EntryTile(entry, uri, thumbs, order = null) { onFolder(entry) }
                        } else {
                            EntryTile(entry, uri, thumbs, orderOf(uri)) { onPhoto(uri) }
                        }
                    }
                }
            }
        }
    }
}

private val PHOTO_TILE = 96.dp
private val MARK = 28.dp
private val ROW_THUMB = 48.dp

/** A folder or photo in the grid, its name under it; a photo with its place in the order picked on it once it is marked. */
@Composable
private fun EntryTile(entry: FolderEntry, uri: Uri, thumbs: LruCache<String, Bitmap>, order: Int?, onClick: () -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(if (order != null) Modifier.border(3.dp, accent) else Modifier),
        ) {
            Picture(entry.isFolder, uri, thumbs, Modifier.fillMaxSize())
            if (order != null) OrderMark(order, Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** A folder or photo in the list: a small picture and its name; a photo with its place in the order picked at the end once it is marked. */
@Composable
private fun EntryRow(entry: FolderEntry, uri: Uri, thumbs: LruCache<String, Bitmap>, order: Int?, onClick: () -> Unit) {
    val marked = if (order != null) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(marked)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Picture(entry.isFolder, uri, thumbs, Modifier.size(ROW_THUMB))
        Text(
            entry.name,
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

/**
 * The picture of the folder or photo at [uri], cropped to [modifier]'s box:
 * a folder's sign, a photo's thumbnail. The box stays empty till a
 * thumbnail comes, or if it never does.
 */
@Composable
private fun Picture(isFolder: Boolean, uri: Uri, thumbs: LruCache<String, Bitmap>, modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (isFolder) {
            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.fillMaxSize(0.6f))
        } else {
            val context = LocalContext.current
            val key = uri.toString()
            val bitmap by produceState(thumbs.get(key), key) {
                if (value == null) {
                    value = withContext(Dispatchers.IO) { pickerThumbnail(context, uri, THUMB_PX) }?.also { thumbs.put(key, it) }
                }
            }
            bitmap?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
