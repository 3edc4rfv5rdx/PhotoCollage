package xx.photocollage.ui

import android.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import xx.photocollage.Aspect
import xx.photocollage.Collage
import xx.photocollage.Node
import xx.photocollage.Photo
import xx.photocollage.Preset
import xx.photocollage.R
import xx.photocollage.TEMPLATE_COUNTS
import xx.photocollage.drawCollage
import xx.photocollage.photos
import xx.photocollage.preset
import xx.photocollage.savedSize
import xx.photocollage.shownPath
import xx.photocollage.mirrored
import xx.photocollage.templates
import xx.photocollage.turnedClockwise

private val PREVIEW_HEIGHT = 96.dp

/** The gap the previews are drawn with: wider than a collage's own, so the cells read at this size. */
private const val PREVIEW_GAP = 0.05f

/** The count a collage of one cell opens the dialog at, instead of 1, which offers nothing to pick. */
private const val START_COUNT = 3

/** The count the dialog opens at for a collage of [cells] cells; see [LayoutDialog]. */
private fun startCount(cells: Int): Int = (if (cells == 1) START_COUNT else cells).coerceIn(TEMPLATE_COUNTS)

/**
 * The ready-made layouts, by how many cells: a drop-down of counts, and under
 * it each layout for the count picked, drawn in the collage's own shape, the
 * cells in the inverse colour on the dialog's own background. The count
 * starts at the collage's own, so its photos all fit — at [START_COUNT] for a
 * collage of one cell, which has no layout to keep. Turn and Mirror, above
 * Cancel, turn every layout a quarter clockwise and mirror it left for right
 * — which is why no two layouts offered are one another turned or mirrored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutDialog(collage: Collage, onDismiss: () -> Unit, onPick: (Node) -> Unit) {
    var count by rememberSaveable { mutableIntStateOf(startCount(collage.cellCount)) }
    // The cells in the inverse colour, dark on a light dialog and light on a dark one, the gaps the
    // dialog's own: the same on whatever background the collage has.
    val cellColor = MaterialTheme.colorScheme.inverseSurface.toArgb()
    // Every layout is shown mirrored if [mirrored], then turned a quarter clockwise [turns] times.
    var turns by rememberSaveable { mutableIntStateOf(0) }
    var mirrored by rememberSaveable { mutableStateOf(false) }
    val options = remember(count, turns, mirrored) {
        templates(count).map { root ->
            val base = if (mirrored) root.mirrored() else root
            (0 until turns).fold(base) { node, _ -> node.turnedClockwise() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownRow(
                    label = stringResource(R.string.photos_count),
                    value = count.toString(),
                    options = TEMPLATE_COUNTS.toList(),
                    optionLabel = { it.toString() },
                    onPick = { count = it },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { root ->
                        val preview = collage.copy(root = root, gap = PREVIEW_GAP, corner = 0f, background = Color.TRANSPARENT)
                        Canvas(
                            Modifier
                                .height(PREVIEW_HEIGHT)
                                .aspectRatio(preview.ratio)
                                .clickable { onPick(root) }
                                .padding(2.dp),
                        ) {
                            drawIntoCanvas {
                                drawCollage(it.nativeCanvas, preview, size.width, size.height, emptyColor = cellColor) { null }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // A line of their own above Cancel, the dialog's width between them.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlimButton(stringResource(R.string.turn), Modifier.weight(1f)) { turns = (turns + 1) % 4 }
                    // Mirrors what is shown: a mirror after the turns is the mirror first, turned back as far.
                    SlimButton(stringResource(R.string.mirror), Modifier.weight(1f)) {
                        mirrored = !mirrored
                        turns = (4 - turns) % 4
                    }
                }
                DialogDismissButton(stringResource(R.string.cancel), onDismiss)
            }
        },
    )
}

/** The height of [SlimButton]: lower than a dialog's own buttons, as it only changes what is shown. */
private val SLIM_BUTTON_HEIGHT = 32.dp

/** A dialog button in the accent, lower than [DialogDismissButton], for a tool that changes the view. */
@Composable
private fun SlimButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(SLIM_BUTTON_HEIGHT),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Text(text, maxLines = 1)
    }
}

/**
 * The canvas's shape and size, as four drop-downs: which way round, the
 * shape, its size in pixels, and a preset — a named standard that sets the
 * other three at once, shown as picked whenever they happen to make it. Every
 * pick takes effect at once, as a step of its own; Done keeps them, Cancel
 * puts back the format the dialog opened on.
 */
@Composable
fun FormatDialog(
    collage: Collage,
    onLandscape: (Boolean) -> Unit,
    onAspect: (Aspect) -> Unit,
    onLongSide: (Int) -> Unit,
    onPreset: (Preset) -> Unit,
    onRestore: (Collage) -> Unit,
    onDismiss: () -> Unit,
) {
    // What Cancel goes back to: the collage as the dialog found it.
    val opened = remember { collage }
    val portrait = stringResource(R.string.portrait)
    val landscape = stringResource(R.string.landscape)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.format)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DropdownRow(
                    label = stringResource(R.string.orientation),
                    value = if (collage.landscape) landscape else portrait,
                    options = listOf(false, true),
                    optionLabel = { if (it) landscape else portrait },
                    onPick = onLandscape,
                )
                DropdownRow(
                    label = stringResource(R.string.shape),
                    value = collage.aspect.label(collage.landscape),
                    options = Aspect.entries,
                    optionLabel = { it.label(collage.landscape) },
                    onPick = onAspect,
                )
                DropdownRow(
                    label = stringResource(R.string.size),
                    value = sizeText(collage.savedSize()),
                    options = collage.aspect.sizes,
                    optionLabel = { sizeText(collage.savedSize(it)) },
                    onPick = onLongSide,
                )
                // The preset sets the three above: set apart from them.
                HorizontalDivider(
                    thickness = 3.dp,
                    modifier = Modifier.fillMaxWidth(0.9f).align(Alignment.CenterHorizontally),
                )
                DropdownRow(
                    label = stringResource(R.string.preset),
                    value = collage.preset()?.label ?: NO_VALUE,
                    options = Preset.entries,
                    optionLabel = { it.label },
                    onPick = onPreset,
                )
            }
        },
        confirmButton = { DialogConfirmButton(stringResource(R.string.done), onClick = onDismiss) },
        dismissButton = {
            DialogDismissButton(stringResource(R.string.cancel)) {
                onRestore(opened)
                onDismiss()
            }
        },
    )
}

/** What the collage is: its way round, shape, saved size, preset, and how many cells and photos it has. */
@Composable
fun PropertiesDialog(collage: Collage, onDismiss: () -> Unit) {
    InfoDialog(
        title = stringResource(R.string.properties),
        rows = listOf(
            stringResource(R.string.orientation) to stringResource(if (collage.landscape) R.string.landscape else R.string.portrait),
            stringResource(R.string.shape) to collage.aspect.label(collage.landscape),
            stringResource(R.string.size) to sizeText(collage.savedSize()),
            stringResource(R.string.preset) to (collage.preset()?.label ?: NO_VALUE),
            stringResource(R.string.cells) to collage.cellCount.toString(),
            stringResource(R.string.photos_count) to collage.photos().size.toString(),
        ),
        onDismiss = onDismiss,
    )
}

/** What a photo is: where it came from, and its size upright, in pixels and in megapixels. */
@Composable
fun PhotoInfoDialog(photo: Photo, onDismiss: () -> Unit) {
    val megapixels = remember(photo) {
        // Tenths while they still tell photos apart, whole millions from ten up.
        NumberFormat.getNumberInstance().apply { maximumFractionDigits = if (photo.megapixels < 10) 1 else 0 }
            .format(photo.megapixels)
    }
    InfoDialog(
        title = stringResource(R.string.photo_info),
        rows = listOf(
            stringResource(R.string.photo_source) to (photo.source?.let(::shownPath) ?: NO_VALUE),
            stringResource(R.string.size) to sizeText(photo.width to photo.height),
            stringResource(R.string.megapixels) to megapixels,
        ),
        onDismiss = onDismiss,
    )
}

/** [rows] of a label and its value under [title], and Close. */
@Composable
private fun InfoDialog(title: String, rows: List<Pair<String, String>>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) ->
                    // The label whole, the value in what is left at the right — a long file name wraps there.
                    Row {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 16.dp))
                        Text(
                            value,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        confirmButton = { DialogDismissButton(stringResource(R.string.close), onDismiss) },
    )
}

/** What a field shows when there is nothing to show: no preset the format makes, nowhere a photo is known to have come from. */
private const val NO_VALUE = "—"

/** Every drop-down field the same width, wide enough for the longest preset's name. */
private val DROPDOWN_WIDTH = 176.dp

private fun sizeText(size: Pair<Int, Int>): String = "${size.first}×${size.second}"

/** A setting's [label], and its [value] in a field that opens the [options] to pick from. */
@Composable
private fun <T> DropdownRow(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(
                onClick = { open = true },
                modifier = Modifier.width(DROPDOWN_WIDTH),
                contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
            ) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            AppMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    AppMenuItem(optionLabel(option)) {
                        open = false
                        onPick(option)
                    }
                }
            }
        }
    }
}

/** An aspect as a ratio, width first — turned round for a [landscape] canvas. The same in every language. */
fun Aspect.label(landscape: Boolean): String {
    val (short, long) = when (this) {
        Aspect.SQUARE -> return "1:1"
        Aspect.A4 -> return "A4"
        Aspect.FOUR_FIVE -> "4" to "5"
        Aspect.THREE_FOUR -> "3" to "4"
        Aspect.TWO_THREE -> "2" to "3"
        Aspect.NINE_SIXTEEN -> "9" to "16"
    }
    return if (landscape) "$long:$short" else "$short:$long"
}
