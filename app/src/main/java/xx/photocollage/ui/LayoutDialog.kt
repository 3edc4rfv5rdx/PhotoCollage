package xx.photocollage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xx.photocollage.Aspect
import xx.photocollage.Collage
import xx.photocollage.Node
import xx.photocollage.Preset
import xx.photocollage.R
import xx.photocollage.TEMPLATE_COUNTS
import xx.photocollage.drawCollage
import xx.photocollage.photos
import xx.photocollage.preset
import xx.photocollage.savedSize
import xx.photocollage.templates

private val PREVIEW_HEIGHT = 96.dp

/** The gap the previews are drawn with: wider than a collage's own, so the cells read at this size. */
private const val PREVIEW_GAP = 0.05f

/**
 * The ready-made layouts, by how many cells: a row of counts, and under it
 * each layout for the count picked, drawn in the collage's own shape on its
 * background, the cells in the accent. The count starts at the collage's
 * own, so its photos all fit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutDialog(collage: Collage, onDismiss: () -> Unit, onPick: (Node) -> Unit) {
    var count by rememberSaveable { mutableIntStateOf(collage.cellCount.coerceIn(TEMPLATE_COUNTS)) }
    // The cells in the accent, so the layouts stand out on any background.
    val cellColor = MaterialTheme.colorScheme.primary.toArgb()
    val options = remember(count) { templates(count) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layout)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TEMPLATE_COUNTS.forEach { n ->
                        FilterChip(selected = n == count, onClick = { count = n }, label = { Text(n.toString()) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEach { root ->
                        val preview = collage.copy(root = root, gap = PREVIEW_GAP, corner = 0f)
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
        confirmButton = { DialogDismissButton(stringResource(R.string.cancel), onDismiss) },
    )
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
                    value = collage.preset()?.label ?: NO_PRESET,
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
    val rows = listOf(
        stringResource(R.string.orientation) to stringResource(if (collage.landscape) R.string.landscape else R.string.portrait),
        stringResource(R.string.shape) to collage.aspect.label(collage.landscape),
        stringResource(R.string.size) to sizeText(collage.savedSize()),
        stringResource(R.string.preset) to (collage.preset()?.label ?: NO_PRESET),
        stringResource(R.string.cells) to collage.cellCount.toString(),
        stringResource(R.string.photos_count) to collage.photos().size.toString(),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.properties)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { (label, value) ->
                    Row {
                        Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        confirmButton = { DialogDismissButton(stringResource(R.string.close), onDismiss) },
    )
}

/** What the preset field shows when the shape, way round and size make none. */
private const val NO_PRESET = "—"

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
