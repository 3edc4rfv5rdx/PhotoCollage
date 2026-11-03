package xx.photocollage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xx.photocollage.BACKGROUND_SQUARE
import xx.photocollage.R

/** The room between two colours of the square. */
private val SWATCH_GAP = 2.dp

/** The colour in force, shown beside the title: a pill like a button with no label. */
private val CURRENT_WIDTH = 96.dp
private val CURRENT_HEIGHT = 40.dp
private val CURRENT_SHAPE = CircleShape

/**
 * The background colour, picked from [BACKGROUND_SQUARE]: every pick takes
 * effect at once, as a step of its own, the one in force marked with a ring
 * and shown beside the title. Done keeps it, Cancel puts back the colour the
 * dialog opened on.
 */
@Composable
fun BackgroundDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    // What Cancel goes back to: the colour as the dialog found it.
    val opened = remember { current }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // The colour in force at the right edge, large enough to read whatever the square's size.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.background))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier
                            .size(width = CURRENT_WIDTH, height = CURRENT_HEIGHT)
                            .background(Color(current), CURRENT_SHAPE)
                            // Outlined, so a colour as pale or dark as the dialog still shows.
                            .border(1.dp, MaterialTheme.colorScheme.outline, CURRENT_SHAPE),
                    )
                }
            }
        },
        text = {
            BoxWithConstraints {
                val columns = BACKGROUND_SQUARE.first().size
                // As large as the dialog's width lets every column be.
                val side = (maxWidth - SWATCH_GAP * (columns - 1)) / columns
                Column(verticalArrangement = Arrangement.spacedBy(SWATCH_GAP)) {
                    BACKGROUND_SQUARE.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP)) {
                            row.forEach { argb ->
                                val color = Color(argb)
                                Box(
                                    Modifier
                                        .size(side)
                                        .background(color)
                                        .clickable { onPick(argb) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (argb == current) {
                                        // A ring that shows on the colour it marks: white on a dark one, black on a light one.
                                        val ring = if (color.luminance() < 0.5f) Color.White else Color.Black
                                        Box(Modifier.size(side * 0.6f).border(2.dp, ring, CircleShape))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { DialogConfirmButton(stringResource(R.string.done), onClick = onDismiss) },
        dismissButton = {
            DialogDismissButton(stringResource(R.string.cancel)) {
                onPick(opened)
                onDismiss()
            }
        },
    )
}
