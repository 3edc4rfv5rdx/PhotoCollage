package xx.photocollage.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.StayCurrentLandscape
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xx.photocollage.Aspect
import xx.photocollage.BACKGROUNDS
import xx.photocollage.CollageViewModel
import xx.photocollage.Dir
import xx.photocollage.EditorEvent
import xx.photocollage.EditorState
import xx.photocollage.MAX_CELLS
import xx.photocollage.MAX_CORNER
import xx.photocollage.MAX_GAP
import xx.photocollage.R

private enum class Screen { EDITOR, SETTINGS }

/** The editor, and Settings over it from the ⋮ menu. */
@Composable
fun MainScreen(vm: CollageViewModel, onAbout: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    when (screen) {
        Screen.EDITOR -> EditorScreen(vm, onSettings = { screen = Screen.SETTINGS }, onAbout = onAbout)
        Screen.SETTINGS -> AppScreen(title = stringResource(R.string.settings), onBack = { screen = Screen.EDITOR }) {
            SettingsScreen(modifier = Modifier.weight(1f))
        }
    }
}

/** Which of the collage's own settings is open under the canvas. */
private enum class Adjust { NONE, GAP, CORNER, BACKGROUND }

/** Which dialog is up. */
private enum class Dialog { NONE, LAYOUT, FORMAT }

/** Where picked photos go: [NEW] into empty cells, [REPLACE] in place of the photo in a cell. */
private enum class PickMode { NEW, REPLACE }

@Composable
private fun EditorScreen(vm: CollageViewModel, onSettings: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var adjust by rememberSaveable { mutableStateOf(Adjust.NONE) }
    var dialog by rememberSaveable { mutableStateOf(Dialog.NONE) }
    // The cell a picker was opened for; saveable, since the picker can outlive the activity.
    var pickCell by rememberSaveable { mutableIntStateOf(0) }

    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        vm.addPhotos(uris, pickCell)
    }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.replacePhoto(pickCell, it) }
    }
    fun pick(cell: Int, mode: PickMode) {
        pickCell = cell
        // GET_CONTENT, not the system photo picker: it lists every app that
        // hands out images — PhotoBox among them — beside the phone's own.
        if (mode == PickMode.NEW) pickMany.launch(IMAGES) else pickOne.launch(IMAGES)
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is EditorEvent.Say -> Toast.makeText(context, noticeText(context, event.notice), Toast.LENGTH_SHORT).show()
                is EditorEvent.Share -> shareImage(context, event.file)
            }
        }
    }

    BackHandler(enabled = state.selected != null || state.swapFrom != null) { vm.deselect() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            TopBar(state, vm, onNew = { pick(0, PickMode.NEW) }, onSettings = onSettings, onAbout = onAbout)
            Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CollageCanvas(
                    state = state,
                    previews = vm.previews,
                    vm = vm,
                    onPickFor = { pick(it, PickMode.NEW) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.collage.isEmpty && state.collage.cellCount == 1) {
                    Button(onClick = { pick(0, PickMode.NEW) }) { Text(stringResource(R.string.choose_photos)) }
                }
            }
            HorizontalDivider()
            when {
                state.swapFrom != null -> SwapBar(onCancel = vm::cancelSwap)
                state.selected != null -> CellTools(state, vm, onReplace = { pick(it, PickMode.REPLACE) })
                else -> {
                    AdjustRow(adjust, state, vm)
                    CollageTools(
                        state = state,
                        adjust = adjust,
                        onAdjust = { adjust = if (adjust == it) Adjust.NONE else it },
                        onLayout = { dialog = Dialog.LAYOUT },
                        onFormat = { dialog = Dialog.FORMAT },
                        onOrientation = vm::toggleOrientation,
                    )
                }
            }
        }
        if (state.busy) {
            // Over everything, and taking every touch, while photos come in or a collage is drawn.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    }

    when (dialog) {
        Dialog.LAYOUT -> LayoutDialog(state.collage, onDismiss = { dialog = Dialog.NONE }) {
            vm.applyTemplate(it)
            dialog = Dialog.NONE
        }
        Dialog.FORMAT -> ChoiceDialog(
            title = stringResource(R.string.format),
            options = Aspect.entries,
            selected = state.collage.aspect,
            label = { it.label(state.collage.landscape) },
            onDismiss = { dialog = Dialog.NONE },
            onPick = {
                vm.setAspect(it)
                dialog = Dialog.NONE
            },
        )
        Dialog.NONE -> Unit
    }
}

private const val IMAGES = "image/*"

@Composable
private fun TopBar(
    state: EditorState,
    vm: CollageViewModel,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val hasPhotos = !state.collage.isEmpty
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LargeIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo), vm::undo, enabled = state.canUndo)
        LargeIconButton(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo), vm::redo, enabled = state.canRedo)
        Spacer(Modifier.weight(1f))
        Box {
            MoreButton(onClick = { menu = true })
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AppMenuItem(stringResource(R.string.new_collage)) {
                    menu = false
                    vm.newCollage()
                    onNew()
                }
                AppMenuItem(stringResource(R.string.save_image), enabled = hasPhotos) {
                    menu = false
                    vm.saveImage()
                }
                AppMenuItem(stringResource(R.string.share), enabled = hasPhotos) {
                    menu = false
                    vm.shareImage()
                }
                AppMenuItem(stringResource(R.string.save_draft), enabled = hasPhotos) {
                    menu = false
                    vm.saveDraft()
                }
                AppMenuItem(stringResource(R.string.open_draft), enabled = state.hasDraft) {
                    menu = false
                    vm.openDraft()
                }
                AppMenuItem(stringResource(R.string.settings)) {
                    menu = false
                    onSettings()
                }
                AppMenuItem(stringResource(R.string.about)) {
                    menu = false
                    onAbout()
                }
            }
        }
    }
}

/** The tools for the whole collage, when no cell is selected. */
@Composable
private fun CollageTools(
    state: EditorState,
    adjust: Adjust,
    onAdjust: (Adjust) -> Unit,
    onLayout: () -> Unit,
    onFormat: () -> Unit,
    onOrientation: () -> Unit,
) {
    val collage = state.collage
    ToolRow {
        Tool(Icons.Filled.Dashboard, stringResource(R.string.layout), onClick = onLayout)
        Tool(Icons.Filled.AspectRatio, collage.aspect.label(collage.landscape), onClick = onFormat)
        Tool(
            if (collage.landscape) Icons.Filled.StayCurrentLandscape else Icons.Filled.StayCurrentPortrait,
            stringResource(if (collage.landscape) R.string.landscape else R.string.portrait),
            enabled = collage.aspect != Aspect.SQUARE,
            onClick = onOrientation,
        )
        Tool(Icons.Filled.BorderOuter, stringResource(R.string.gap), active = adjust == Adjust.GAP) { onAdjust(Adjust.GAP) }
        Tool(Icons.Filled.RoundedCorner, stringResource(R.string.corners), active = adjust == Adjust.CORNER) {
            onAdjust(Adjust.CORNER)
        }
        Tool(Icons.Filled.Palette, stringResource(R.string.background), active = adjust == Adjust.BACKGROUND) {
            onAdjust(Adjust.BACKGROUND)
        }
    }
}

/** The tools for the selected cell. */
@Composable
private fun CellTools(state: EditorState, vm: CollageViewModel, onReplace: (Int) -> Unit) {
    val cell = state.selected ?: return
    val canSplit = state.collage.cellCount < MAX_CELLS
    ToolRow {
        Tool(Icons.Filled.AddPhotoAlternate, stringResource(R.string.replace_photo)) { onReplace(cell) }
        Tool(Icons.Filled.SwapHoriz, stringResource(R.string.swap), enabled = state.collage.cellCount > 1, onClick = vm::startSwap)
        Tool(Icons.AutoMirrored.Filled.RotateRight, stringResource(R.string.turn), onClick = vm::turnSelected)
        Tool(Icons.Filled.ViewColumn, stringResource(R.string.split_across), enabled = canSplit) { vm.splitSelected(Dir.ROW) }
        Tool(Icons.Filled.ViewAgenda, stringResource(R.string.split_down), enabled = canSplit) { vm.splitSelected(Dir.COLUMN) }
        Tool(Icons.Filled.HideImage, stringResource(R.string.clear_photo), onClick = vm::clearSelected)
        Tool(
            Icons.Filled.DeleteOutline,
            stringResource(R.string.remove_cell),
            enabled = state.collage.cellCount > 1,
            onClick = vm::removeSelected,
        )
        Tool(Icons.Filled.Close, stringResource(R.string.done), onClick = vm::deselect)
    }
}

/** While a swap waits for its second cell: what to do, and a way out. */
@Composable
private fun SwapBar(onCancel: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.swap_hint), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        DialogDismissButton(stringResource(R.string.cancel), onCancel)
    }
}

/** The open one of the gap, corner and background settings, above the tools; nothing when none is. */
@Composable
private fun AdjustRow(adjust: Adjust, state: EditorState, vm: CollageViewModel) {
    val collage = state.collage
    when (adjust) {
        Adjust.GAP -> Slider(
            value = collage.gap,
            onValueChange = vm::setGap,
            onValueChangeFinished = vm::endGesture,
            valueRange = 0f..MAX_GAP,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Adjust.CORNER -> Slider(
            value = collage.corner,
            onValueChange = vm::setCorner,
            onValueChangeFinished = vm::endGesture,
            valueRange = 0f..MAX_CORNER,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Adjust.BACKGROUND -> Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BACKGROUNDS.forEach { argb ->
                AccentSwatch(Color(argb), selected = argb == collage.background) { vm.setBackground(argb) }
            }
        }
        Adjust.NONE -> Unit
    }
}

@Composable
private fun ToolRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

/** One tool: its glyph over its name, tinted with the accent while [active]. */
@Composable
private fun Tool(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val color = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        active -> scheme.primary
        else -> scheme.onSurface
    }
    Column(
        Modifier
            .widthIn(min = 64.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(LARGE_ICON))
        Text(
            label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
