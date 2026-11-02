package xx.photocollage.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import xx.photocollage.Box as CellBox
import xx.photocollage.CollageLayout
import xx.photocollage.CollageViewModel
import xx.photocollage.EditorState
import xx.photocollage.drawCollage
import xx.photocollage.fitCanvas
import xx.photocollage.layoutOf
import kotlin.math.max
import kotlin.math.roundToInt

/** How near a cut a finger has to come down to take it rather than the cell beside it. */
private val DIVIDER_REACH = 16.dp

/** The long side of the photo that follows the finger while it is carried to another cell. */
private val CARRIED_SIZE = 96.dp

private val SELECTION_STROKE = 3.dp
private val EMPTY_ICON = 36.dp

/** The sign on an empty cell: darker than the cell's grey, in either theme, since the cell is the same grey in both. */
private val EmptyIconColor = Color(0xFF6E6E6E)

/** What a finger that came down on a cell turned out to want, once it is clear. */
private enum class Touch { TAP, MOVE, HOLD }

/** A photo being carried from cell [from], the finger now at [at]. */
private data class Carry(val from: Int, val at: Offset)

/**
 * The collage as large as it fits in the space given, with every touch on
 * it: a tap selects a cell or asks for a photo for an empty one; a drag or a
 * pinch moves and zooms the photo under it; a drag on a gap moves that cut;
 * a long press picks a photo up to drop on another cell, where the two trade
 * places.
 */
@Composable
fun CollageCanvas(
    state: EditorState,
    previews: Map<String, Bitmap>,
    vm: CollageViewModel,
    onPickFor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val (w, h) = fitCanvas(state.collage.ratio, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        if (w <= 0f || h <= 0f) return@BoxWithConstraints
        val layout = remember(state.collage, w, h) { layoutOf(state.collage, w, h) }
        val current by rememberUpdatedState(layout)
        val collage by rememberUpdatedState(state.collage)
        val pickFor by rememberUpdatedState(onPickFor)
        val haptics = LocalHapticFeedback.current
        var carry by remember { mutableStateOf<Carry?>(null) }
        val accent = MaterialTheme.colorScheme.primary
        val stroke = with(density) { SELECTION_STROKE.toPx() }
        val carried = with(density) { CARRIED_SIZE.toPx() }

        Box(
            Modifier
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .pointerInput(vm) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        val divider = current.dividerAt(start.x, start.y, DIVIDER_REACH.toPx())
                        if (divider != null) {
                            down.consume()
                            vm.beginGesture()
                            try {
                                drag(down.id) { change ->
                                    vm.moveDivider(divider.path, divider.ratioAt(change.position.x, change.position.y))
                                    change.consume()
                                }
                            } finally {
                                vm.endGesture()
                            }
                            return@awaitEachGesture
                        }
                        val cell = current.cellAt(start.x, start.y) ?: return@awaitEachGesture

                        // Tap, drag or hold: whichever shows first.
                        var touch: Touch? = null
                        withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (touch == null) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                val main = event.changes.firstOrNull { it.id == down.id }
                                touch = when {
                                    pressed.isEmpty() -> Touch.TAP
                                    pressed.size > 1 -> Touch.MOVE
                                    main == null || !main.pressed -> Touch.MOVE
                                    (main.position - start).getDistance() > viewConfiguration.touchSlop -> Touch.MOVE
                                    else -> null
                                }
                            }
                        }
                        val hasPhoto = collage.leaves.getOrNull(cell)?.photo != null
                        when (touch ?: Touch.HOLD) {
                            Touch.TAP -> if (vm.tapCell(cell)) pickFor(cell)

                            Touch.MOVE -> {
                                if (!hasPhoto) return@awaitEachGesture
                                vm.beginGesture()
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        val box = current.cells.getOrNull(cell) ?: break
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        if (centroid != Offset.Unspecified && (pan != Offset.Zero || zoom != 1f)) {
                                            vm.transformPhoto(
                                                cell, box.width, box.height, pan.x, pan.y, zoom,
                                                centroid.x - box.centerX, centroid.y - box.centerY,
                                            )
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } while (event.changes.any { it.pressed })
                                } finally {
                                    vm.endGesture()
                                }
                            }

                            Touch.HOLD -> {
                                if (!hasPhoto) return@awaitEachGesture
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                carry = Carry(cell, start)
                                try {
                                    drag(down.id) { change ->
                                        carry = Carry(cell, change.position)
                                        change.consume()
                                    }
                                    val end = carry?.at ?: start
                                    current.nearestCell(end.x, end.y)?.takeIf { it != cell }?.let { vm.dropPhoto(cell, it) }
                                } finally {
                                    carry = null
                                }
                            }
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { canvas ->
                    drawCollage(canvas.nativeCanvas, state.collage, size.width, size.height, layout) {
                        previews[it.file]
                    }
                }
                state.selected?.let { outline(layout, it, accent, stroke, dashed = false) }
                state.swapFrom?.let { outline(layout, it, accent, stroke, dashed = true) }
                carry?.let { c ->
                    val target = layout.nearestCell(c.at.x, c.at.y)
                    if (target != null && target != c.from) outline(layout, target, accent, stroke, dashed = false)
                    outline(layout, c.from, accent, stroke, dashed = true)
                    state.collage.leaves.getOrNull(c.from)?.photo?.let { photo ->
                        previews[photo.photo.file]?.let { drawCarried(it, c.at, carried) }
                    }
                }
            }
            // A sign on every empty cell that a tap there brings a photo in.
            layout.cells.forEachIndexed { index, cell ->
                if (state.collage.leaves.getOrNull(index)?.photo != null) return@forEachIndexed
                val icon = with(density) { EMPTY_ICON.toPx() }
                if (cell.width < icon || cell.height < icon) return@forEachIndexed
                Icon(
                    Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    tint = EmptyIconColor,
                    modifier = Modifier
                        .offset { IntOffset((cell.centerX - icon / 2).roundToInt(), (cell.centerY - icon / 2).roundToInt()) }
                        .size(EMPTY_ICON),
                )
            }
        }
    }
}

/** A ring round cell [index], in [color]: solid for the selected cell, dashed for one waiting to trade its photo. */
private fun DrawScope.outline(layout: CollageLayout, index: Int, color: Color, width: Float, dashed: Boolean) {
    val cell: CellBox = layout.cells.getOrNull(index) ?: return
    val radius = layout.cornerOf(index)
    drawRoundRect(
        color = color,
        topLeft = Offset(cell.left + width / 2, cell.top + width / 2),
        size = Size(max(0f, cell.width - width), max(0f, cell.height - width)),
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(
            width = width,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 4, width * 3)) else null,
        ),
    )
}

/** The photo being carried, [long] pixels on its long side, centred under the finger at [at] and a little see-through. */
private fun DrawScope.drawCarried(bitmap: Bitmap, at: Offset, long: Float) {
    val scale = long / max(bitmap.width, bitmap.height)
    val w = bitmap.width * scale
    val h = bitmap.height * scale
    val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
    drawIntoCanvas {
        it.nativeCanvas.drawBitmap(bitmap, null, RectF(at.x - w / 2, at.y - h / 2, at.x + w / 2, at.y + h / 2), paint)
    }
}
