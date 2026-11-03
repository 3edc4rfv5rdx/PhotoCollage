package xx.photocollage.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import xx.photocollage.OverlayShape
import xx.photocollage.Pt
import xx.photocollage.R
import xx.photocollage.outlineOf
import xx.photocollage.shapePath

/** How each overlay shape is named. */
@StringRes
private fun OverlayShape.label(): Int = when (this) {
    OverlayShape.CIRCLE -> R.string.circle
    OverlayShape.SQUARE -> R.string.square
    OverlayShape.TRIANGLE -> R.string.triangle
    OverlayShape.DIAMOND -> R.string.diamond
    OverlayShape.HEXAGON -> R.string.hexagon
    OverlayShape.STAR -> R.string.star
    OverlayShape.HEART -> R.string.heart
}

private val TILE = 64.dp
private val TILE_SHAPE = RoundedCornerShape(12.dp)

/**
 * The shapes an overlay can take, each drawn in the inverse colour with its
 * name under it: a tap picks one. [current], when given, is ringed in the
 * accent — the shape of the overlay being changed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayShapeDialog(current: OverlayShape? = null, onDismiss: () -> Unit, onPick: (OverlayShape) -> Unit) {
    val fill = MaterialTheme.colorScheme.inverseSurface
    val accent = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (current == null) R.string.overlay else R.string.shape)) },
        text = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OverlayShape.entries.forEach { shape ->
                    Column(
                        Modifier.width(TILE + 8.dp).clip(TILE_SHAPE).clickable { onPick(shape) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Canvas(
                            Modifier
                                .size(TILE)
                                .border(3.dp, if (shape == current) accent else Color.Transparent, TILE_SHAPE)
                                .padding(10.dp),
                        ) {
                            val side = minOf(size.width, size.height)
                            val outline = outlineOf(shape).map { Pt(it.x * side + size.width / 2, it.y * side + size.height / 2) }
                            drawPath(shapePath(outline, 0f).asComposePath(), fill)
                        }
                        Text(
                            stringResource(shape.label()),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = { DialogDismissButton(stringResource(R.string.cancel), onDismiss) },
    )
}
