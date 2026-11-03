package xx.photocollage.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.photocollage.CollageViewModel
import xx.photocollage.DraftInfo
import xx.photocollage.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.max

/** The long side a draft's picture in the grid is drawn at: sharp in a tile, light enough for a screenful. */
private const val TILE_LONG = 480

/** The most a draft shown large is drawn at, however large the screen. */
private const val LARGE_MAX_LONG = 2048

/**
 * The drafts saved by hand, as a grid of their pictures, the one written
 * last first, the one the collage is kept as ringed. A tap shows a draft
 * large, to open or delete; opening it over a collage that holds work asks
 * first, as a new collage does. [onOpened] once a draft takes the collage's
 * place.
 */
@Composable
fun DraftsScreen(vm: CollageViewModel, onBack: () -> Unit, onOpened: () -> Unit) {
    val state by vm.state.collectAsState()
    // By id, not by place: the list may change under it.
    var shownId by rememberSaveable { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = shownId?.let { id -> state.drafts.firstOrNull { it.id == id } }

    fun open(id: String) {
        when {
            // The collage is this very draft, as saved: nothing to open.
            id == state.draftId && state.collage == state.draft -> onOpened()
            vm.collageLosesWork() -> asking = id
            else -> {
                vm.openDraft(id, saveFirst = false)
                onOpened()
            }
        }
    }

    if (shown != null) {
        DraftView(vm, shown, onBack = { shownId = null }, onOpen = { open(shown.id) }, onDelete = {
            shownId = null
            vm.deleteDraft(shown.id)
        })
    } else {
        AppScreen(title = stringResource(R.string.drafts), onBack = onBack) {
            if (state.drafts.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_drafts), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(TILE_MIN_WIDTH),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.drafts, key = { it.id }) { draft ->
                        DraftTile(vm, draft, current = draft.id == state.draftId) { shownId = draft.id }
                    }
                }
            }
        }
    }

    asking?.let { id ->
        NewCollageDialog(
            question = R.string.open_draft,
            saveAndGo = R.string.save_draft_and_open,
            goWithout = R.string.open_without_saving,
            onSaveAndStart = {
                asking = null
                vm.openDraft(id, saveFirst = true)
                onOpened()
            },
            onStart = {
                asking = null
                vm.openDraft(id, saveFirst = false)
                onOpened()
            },
            onDismiss = { asking = null },
        )
    }
}

private val TILE_MIN_WIDTH = 150.dp
private val TILE_SHAPE = RoundedCornerShape(12.dp)

/** One draft in the grid: its picture in a square, ringed in the accent when [current], and when it was saved under it. */
@Composable
private fun DraftTile(vm: CollageViewModel, draft: DraftInfo, current: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.clickable(onClick = onClick)) {
        DraftPicture(
            vm,
            draft,
            TILE_LONG,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(TILE_SHAPE)
                .border(
                    if (current) BorderStroke(3.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant),
                    TILE_SHAPE,
                )
                .padding(6.dp),
        )
        Text(
            savedAtText(draft),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
    }
}

/** One draft large, with when it was saved as the title, and Delete and Open under it. */
@Composable
private fun DraftView(
    vm: CollageViewModel,
    draft: DraftInfo,
    onBack: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    AppScreen(title = savedAtText(draft), onBack = onBack) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            val long = max(constraints.maxWidth, constraints.maxHeight).coerceIn(TILE_LONG, LARGE_MAX_LONG)
            DraftPicture(vm, draft, long, Modifier.fillMaxSize())
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        ) {
            DialogConfirmButton(stringResource(R.string.delete), danger = true) { confirming = true }
            DialogConfirmButton(stringResource(R.string.open), onClick = onOpen)
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.delete_draft) + "?") },
            text = { Text(stringResource(R.string.delete_draft_warning) + ".") },
            confirmButton = {
                DialogConfirmButton(stringResource(R.string.delete), danger = true) {
                    confirming = false
                    onDelete()
                }
            },
            dismissButton = { DialogDismissButton(stringResource(R.string.cancel)) { confirming = false } },
        )
    }
}

/**
 * [draft] drawn [longSide] pixels on its long side, fitted into [modifier]'s
 * room: drawn again when it is saved again, a spinner till then, and nothing
 * when it cannot be drawn.
 */
@Composable
private fun DraftPicture(vm: CollageViewModel, draft: DraftInfo, longSide: Int, modifier: Modifier) {
    var loading by remember(draft, longSide) { mutableStateOf(true) }
    val picture by produceState<Bitmap?>(null, draft, longSide) {
        value = vm.draftPreview(draft.id, longSide)
        loading = false
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        picture?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (loading) CircularProgressIndicator()
    }
}

/** When [draft] was saved, short, in the app's language. */
@Composable
private fun savedAtText(draft: DraftInfo): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
    }
    return format.format(Instant.ofEpochMilli(draft.savedAt).atZone(ZoneId.systemDefault()))
}
