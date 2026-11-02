package xx.photocollage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.photocollage.AppSettings
import xx.photocollage.Aspect
import xx.photocollage.Collage
import xx.photocollage.Node
import xx.photocollage.R
import xx.photocollage.TEMPLATE_COUNTS
import xx.photocollage.drawCollage
import xx.photocollage.exportSize
import xx.photocollage.templates

private val PREVIEW_HEIGHT = 96.dp

/** The gap the previews are drawn with: wider than a collage's own, so the cells read at this size. */
private const val PREVIEW_GAP = 0.05f

/**
 * The ready-made layouts, by how many cells: a row of counts, and under it
 * each layout for the count picked, drawn in the collage's own shape and
 * colour. The count starts at the collage's own, so its photos all fit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutDialog(collage: Collage, onDismiss: () -> Unit, onPick: (Node) -> Unit) {
    var count by rememberSaveable { mutableIntStateOf(collage.cellCount.coerceIn(TEMPLATE_COUNTS)) }
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
                                drawCollage(it.nativeCanvas, preview, size.width, size.height) { null }
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
 * The canvas's shape: portrait or landscape in one row on top, which turns
 * the collage at once, and the ratios under it, each with the size in pixels
 * it is saved at — the long side Settings asks for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormatDialog(collage: Collage, onLandscape: (Boolean) -> Unit, onDismiss: () -> Unit, onPick: (Aspect) -> Unit) {
    val longSide by AppSettings.exportSize.collectAsState()
    ChoiceDialog(
        title = stringResource(R.string.format),
        options = Aspect.entries,
        selected = collage.aspect,
        label = { aspect ->
            val (w, h) = exportSize(collage.copy(aspect = aspect), longSide)
            "${aspect.label(collage.landscape)} ($w×$h)"
        },
        onDismiss = onDismiss,
        onPick = onPick,
        header = {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                listOf(false to R.string.portrait, true to R.string.landscape).forEachIndexed { index, (landscape, text) ->
                    SegmentedButton(
                        selected = collage.landscape == landscape,
                        onClick = { onLandscape(landscape) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(text)) }
                }
            }
        },
    )
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
