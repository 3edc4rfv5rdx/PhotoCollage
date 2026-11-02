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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import xx.photocollage.Aspect
import xx.photocollage.Collage
import xx.photocollage.Node
import xx.photocollage.R
import xx.photocollage.TEMPLATE_COUNTS
import xx.photocollage.drawCollage
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
