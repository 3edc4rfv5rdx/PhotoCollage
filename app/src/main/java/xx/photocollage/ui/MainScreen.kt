package xx.photocollage.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RoundedCorner
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.unit.dp
import xx.photocollage.Collage
import xx.photocollage.CollageViewModel
import xx.photocollage.Dir
import xx.photocollage.EditorEvent
import xx.photocollage.EditorState
import xx.photocollage.MAX_CORNER
import xx.photocollage.MAX_GAP
import xx.photocollage.R
import xx.photocollage.canSplit

private enum class Screen { EDITOR, SETTINGS }

/** The editor, and Settings over it from the ⋮ menu. */
@Composable
fun MainScreen(vm: CollageViewModel, onExit: () -> Unit, onAbout: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.EDITOR) }
    when (screen) {
        Screen.EDITOR -> EditorScreen(vm, onExit = onExit, onSettings = { screen = Screen.SETTINGS }, onAbout = onAbout)
        Screen.SETTINGS -> AppScreen(title = stringResource(R.string.settings), onBack = { screen = Screen.EDITOR }) {
            SettingsScreen(modifier = Modifier.weight(1f))
        }
    }
}

/** Which of the collage's own settings is open under the canvas. */
private enum class Adjust { NONE, GAP, CORNER }

/** Which dialog is up. */
private enum class Dialog { NONE, LAYOUT, FORMAT, BACKGROUND, SAVE, PROPERTIES, NEW_COLLAGE }

/** The collage's own settings, under the wrench while no cell is selected: three open a dialog, two a strip under the collage. */
private enum class CollageTool(val icon: ImageVector, @param:StringRes val text: Int) {
    LAYOUT(Icons.Filled.Dashboard, R.string.layout),
    FORMAT(Icons.Filled.AspectRatio, R.string.format),
    GAP(Icons.Filled.BorderOuter, R.string.gap),
    CORNERS(Icons.Filled.RoundedCorner, R.string.corners),
    BACKGROUND(Icons.Filled.Palette, R.string.background),
}

/** What can be done to the selected cell, under the same button while one is. */
private enum class CellTool(val icon: ImageVector, @param:StringRes val text: Int) {
    REPLACE(Icons.Filled.AddPhotoAlternate, R.string.replace_photo),
    SWAP(Icons.Filled.SwapHoriz, R.string.swap),
    TURN(Icons.AutoMirrored.Filled.RotateRight, R.string.turn),
    SPLIT_ACROSS(Icons.Filled.ViewColumn, R.string.split_across),
    SPLIT_DOWN(Icons.Filled.ViewAgenda, R.string.split_down),
    CLEAR(Icons.Filled.HideImage, R.string.clear_photo),
    REMOVE(Icons.Filled.DeleteOutline, R.string.remove_cell),
}

/** Whether [tool] can be used on the selected cell of [collage]: a photo to act on, room to cut, another cell. */
private fun CellTool.enabled(collage: Collage, cell: Int): Boolean {
    val hasPhoto = collage.leaves.getOrNull(cell)?.photo != null
    return when (this) {
        CellTool.REPLACE -> true
        CellTool.SWAP -> collage.cellCount > 1
        CellTool.TURN, CellTool.CLEAR -> hasPhoto
        CellTool.SPLIT_ACROSS -> collage.canSplit(cell, Dir.ROW)
        CellTool.SPLIT_DOWN -> collage.canSplit(cell, Dir.COLUMN)
        CellTool.REMOVE -> collage.cellCount > 1
    }
}

/** Where picked photos go: [NEW] into empty cells, [REPLACE] in place of the photo in a cell. */
private enum class PickMode { NEW, REPLACE }

@Composable
private fun EditorScreen(vm: CollageViewModel, onExit: () -> Unit, onSettings: () -> Unit, onAbout: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val rotation = rememberCanvasRotation()
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
                EditorEvent.PickPhotos -> pick(0, PickMode.NEW)
            }
        }
    }

    BackHandler(enabled = state.selected != null || state.swapFrom != null) { vm.deselect() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            TopBar(
                state,
                vm,
                onExit = onExit,
                onNew = { if (vm.newCollageLosesWork()) dialog = Dialog.NEW_COLLAGE else vm.startNew(saveFirst = false) },
                onSave = { dialog = Dialog.SAVE },
                onProperties = { dialog = Dialog.PROPERTIES },
                onCellTool = { tool ->
                    val cell = state.selected
                    if (cell != null) {
                        when (tool) {
                            CellTool.REPLACE -> pick(cell, PickMode.REPLACE)
                            CellTool.SWAP -> vm.startSwap()
                            CellTool.TURN -> vm.turnSelected()
                            CellTool.SPLIT_ACROSS -> vm.splitSelected(Dir.ROW)
                            CellTool.SPLIT_DOWN -> vm.splitSelected(Dir.COLUMN)
                            CellTool.CLEAR -> vm.clearSelected()
                            CellTool.REMOVE -> vm.removeSelected()
                        }
                    }
                },
                onTool = { tool ->
                    when (tool) {
                        CollageTool.LAYOUT -> dialog = Dialog.LAYOUT
                        CollageTool.FORMAT -> dialog = Dialog.FORMAT
                        CollageTool.GAP -> adjust = Adjust.GAP
                        CollageTool.CORNERS -> adjust = Adjust.CORNER
                        CollageTool.BACKGROUND -> dialog = Dialog.BACKGROUND
                    }
                },
                onSettings = onSettings,
                onAbout = onAbout,
            )
            // Only the collage turns with the phone; the bars and dialogs stay upright.
            RotatedFrame(rotation, Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
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
            // The room under the collage is kept whether anything is in it or not: a
            // panel that came and went would shrink and grow the collage under the
            // finger. The collage's own settings live under the wrench at the top.
            Column(Modifier.fillMaxWidth().heightIn(min = BOTTOM_PANEL_HEIGHT)) {
                when {
                    state.swapFrom != null -> {
                        HorizontalDivider()
                        SwapBar(onCancel = vm::cancelSwap)
                    }
                    adjust != Adjust.NONE -> {
                        HorizontalDivider()
                        AdjustRow(adjust, state, vm, onClose = { adjust = Adjust.NONE })
                    }
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
        Dialog.FORMAT -> FormatDialog(
            collage = state.collage,
            onLandscape = vm::setLandscape,
            onAspect = vm::setAspect,
            onLongSide = vm::setLongSide,
            onPreset = vm::setPreset,
            onRestore = vm::restoreFormat,
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.SAVE -> SaveDialog(onDismiss = { dialog = Dialog.NONE }) {
            dialog = Dialog.NONE
            vm.saveImage(it)
        }
        Dialog.BACKGROUND -> BackgroundDialog(
            current = state.collage.background,
            onPick = vm::setBackground,
            onDismiss = { dialog = Dialog.NONE },
        )
        Dialog.PROPERTIES -> PropertiesDialog(state.collage, onDismiss = { dialog = Dialog.NONE })
        Dialog.NEW_COLLAGE, Dialog.NONE -> Unit
    }
    // From the menu, or for photos shared over a collage that holds work.
    if (dialog == Dialog.NEW_COLLAGE || state.pendingShare != null) {
        NewCollageDialog(
            onSaveAndStart = {
                dialog = Dialog.NONE
                vm.startNew(saveFirst = true)
            },
            onStart = {
                dialog = Dialog.NONE
                vm.startNew(saveFirst = false)
            },
            onDismiss = {
                dialog = Dialog.NONE
                vm.dropPendingShare()
            },
        )
    }
}

private const val IMAGES = "image/*"

/** The room kept under the collage: one strip — a slider or the swap prompt. */
private val BOTTOM_PANEL_HEIGHT = 64.dp

@Composable
private fun TopBar(
    state: EditorState,
    vm: CollageViewModel,
    onExit: () -> Unit,
    onNew: () -> Unit,
    onSave: () -> Unit,
    onProperties: () -> Unit,
    onTool: (CollageTool) -> Unit,
    onCellTool: (CellTool) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    val hasPhotos = !state.collage.isEmpty
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        LargeIconButton(Icons.Filled.Close, stringResource(R.string.close), onExit)
        Spacer(Modifier.weight(1f))
        LargeIconButton(Icons.AutoMirrored.Filled.Undo, stringResource(R.string.undo), vm::undo, enabled = state.canUndo)
        LargeIconButton(Icons.AutoMirrored.Filled.Redo, stringResource(R.string.redo), vm::redo, enabled = state.canRedo)
        LargeIconButton(Icons.Filled.Save, stringResource(R.string.save_image), onSave, enabled = hasPhotos)
        // One button for both: the selected cell's tools while a cell is selected, the collage's otherwise —
        // a pencil for the one, a wrench for the other, so it says which before it opens.
        val cell = state.selected
        Box {
            if (cell != null) {
                LargeIconButton(Icons.Filled.Edit, stringResource(R.string.cell_tools), { tools = true })
            } else {
                LargeIconButton(Icons.Filled.Build, stringResource(R.string.collage_settings), { tools = true })
            }
            AppMenu(expanded = tools, onDismissRequest = { tools = false }) {
                if (cell != null) {
                    CellTool.entries.forEach { tool ->
                        // Replace is Add photo on an empty cell.
                        val empty = state.collage.leaves.getOrNull(cell)?.photo == null
                        val text = if (tool == CellTool.REPLACE && empty) R.string.add_photo else tool.text
                        AppMenuItem(stringResource(text), enabled = tool.enabled(state.collage, cell), icon = tool.icon) {
                            tools = false
                            onCellTool(tool)
                        }
                    }
                } else {
                    CollageTool.entries.forEach { tool ->
                        AppMenuItem(stringResource(tool.text), icon = tool.icon) {
                            tools = false
                            onTool(tool)
                        }
                    }
                }
            }
        }
        Box {
            MoreButton(onClick = { menu = true })
            AppMenu(expanded = menu, onDismissRequest = { menu = false }) {
                AppMenuItem(stringResource(R.string.new_collage)) {
                    menu = false
                    onNew()
                }
                AppMenuItem(stringResource(R.string.save_image), enabled = hasPhotos) {
                    menu = false
                    onSave()
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
                AppMenuItem(stringResource(R.string.properties)) {
                    menu = false
                    onProperties()
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

/** The open one of the gap and corner settings, under the collage, with ✓ to put it away. */
@Composable
private fun AdjustRow(adjust: Adjust, state: EditorState, vm: CollageViewModel, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { AdjustControl(adjust, state, vm) }
        // In the inverse pair, dark on a light window and light on a dark one, so it reads as the way out.
        FilledIconButton(
            onClick = onClose,
            modifier = Modifier.padding(end = 8.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
        ) { Icon(Icons.Filled.Check, stringResource(R.string.done)) }
    }
}

@Composable
private fun AdjustControl(adjust: Adjust, state: EditorState, vm: CollageViewModel) {
    val collage = state.collage
    when (adjust) {
        Adjust.GAP -> AdjustSlider(collage.gap, 0f..MAX_GAP, vm::setGap, vm::endGesture)
        Adjust.CORNER -> AdjustSlider(collage.corner, 0f..MAX_CORNER, vm::setCorner, vm::endGesture)
        Adjust.NONE -> Unit
    }
}

/**
 * A slider across the screen for one of the collage's settings. The system's
 * back swipe is kept off its whole width: at zero its thumb sits by the left
 * edge, where a drag would otherwise close the app.
 */
@Composable
private fun AdjustSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Slider(
        value = value,
        onValueChange = onChange,
        onValueChangeFinished = onDone,
        valueRange = range,
        modifier = Modifier.systemGestureExclusion().padding(horizontal = 24.dp),
    )
}
