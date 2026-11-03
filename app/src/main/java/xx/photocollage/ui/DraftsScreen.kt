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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.photocollage.ArchiveChoice
import xx.photocollage.ArchivedDraft
import xx.photocollage.CollageViewModel
import xx.photocollage.DRAFT_TILE_LONG
import xx.photocollage.DraftInfo
import xx.photocollage.R
import kotlin.math.max

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
    Column(Modifier.clickable(onClick = onClick)) {
        DraftPicture(draft, tileModifier(current)) { vm.draftPreview(draft.id, DRAFT_TILE_LONG) }
        Text(
            dateTimeText(draft.savedAt),
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
    AppScreen(title = dateTimeText(draft.savedAt), onBack = onBack) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            val long = max(constraints.maxWidth, constraints.maxHeight).coerceIn(DRAFT_TILE_LONG, LARGE_MAX_LONG)
            DraftPicture(draft to long, Modifier.fillMaxSize()) { vm.draftPreview(draft.id, long) }
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

/** A tile's square, ringed in the accent when [marked]. */
@Composable
private fun tileModifier(marked: Boolean): Modifier {
    val scheme = MaterialTheme.colorScheme
    return Modifier
        .fillMaxWidth()
        .aspectRatio(1f)
        .clip(TILE_SHAPE)
        .border(if (marked) BorderStroke(3.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant), TILE_SHAPE)
        .padding(6.dp)
}

/**
 * A draft's picture as [load] draws it, fitted into [modifier]'s room: drawn
 * again when [key] changes — a draft saved again, a larger size — a spinner
 * till then, and nothing when it cannot be drawn.
 */
@Composable
private fun DraftPicture(key: Any, modifier: Modifier, load: suspend () -> Bitmap?) {
    var loading by remember(key) { mutableStateOf(true) }
    val picture by produceState<Bitmap?>(null, key) {
        value = load()
        loading = false
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        picture?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (loading) CircularProgressIndicator()
    }
}

/**
 * The drafts of an archive picked to import from, as a grid of their
 * pictures, the one saved last first: a tap chooses one or lets it go, and
 * Import brings the chosen ones in among the drafts here. One here already
 * is said to be, and comes in as a copy beside it. Back lets the archive go,
 * unless it is being imported from.
 */
@Composable
fun ImportScreen(vm: CollageViewModel, choice: ArchiveChoice, busy: Boolean) {
    // A list, not a set: it is kept across the activity being made anew.
    var chosen by rememberSaveable { mutableStateOf(listOf<String>()) }
    val all = chosen.size == choice.drafts.size
    AppScreen(
        title = stringResource(R.string.import_drafts),
        onBack = vm::closeArchive,
        actions = {
            LargeIconButton(
                if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                stringResource(if (all) R.string.select_none else R.string.select_all),
                onClick = { chosen = if (all) emptyList() else choice.drafts.map { it.id } },
                enabled = !busy,
            )
        },
    ) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TILE_MIN_WIDTH),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(choice.drafts, key = { it.id }) { draft ->
                val marked = draft.id in chosen
                ArchiveTile(vm, draft, marked, here = draft.id in choice.here) {
                    if (!busy) chosen = if (marked) chosen - draft.id else chosen + draft.id
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            DialogConfirmButton(stringResource(R.string.import_selected), enabled = chosen.isNotEmpty() && !busy) {
                vm.importChosen(chosen.toSet())
            }
        }
    }
}

/**
 * One draft of an archive: its picture, ringed and ticked while [chosen],
 * when it was saved under it, and under that whether it is [here] already.
 */
@Composable
private fun ArchiveTile(vm: CollageViewModel, draft: ArchivedDraft, chosen: Boolean, here: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            DraftPicture(draft, tileModifier(chosen)) { vm.archivePreview(draft.id) }
            if (chosen) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                )
            }
        }
        Text(
            dateTimeText(draft.savedAt),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp),
        )
        if (here) {
            Text(
                stringResource(R.string.already_in_drafts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}
