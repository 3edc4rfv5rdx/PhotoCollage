package xx.photocollage.ui

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
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
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
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
import xx.photocollage.Pt
import xx.photocollage.insetShape
import xx.photocollage.middleOf
import xx.photocollage.shapePath
import xx.photocollage.thickness
import xx.photocollage.drawCollage
import xx.photocollage.fitCanvas
import xx.photocollage.layoutOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** How near a cut a finger has to come down to take it rather than the cell beside it. */
private val DIVIDER_REACH = 16.dp

/** How near a cut a tap has to be to show its ends rather than select the cell beside it. */
private val CUT_TAP_REACH = 12.dp

/** How near one of the shown cut's ends a finger has to come down to tilt the cut by it. */
private val HANDLE_REACH = 28.dp

/** The radius of the dot on each end of the shown cut. */
private val HANDLE_RADIUS = 9.dp

/** The long side of the photo that follows the finger while it is carried to another cell. */
private val CARRIED_SIZE = 96.dp

private val SELECTION_STROKE = 3.dp

/** The line round the canvas on screen, so a background as pale as the window still shows where the collage ends. */
private val EDGE_STROKE = 2.dp
private val EMPTY_ICON = 36.dp

/** The sign on an empty cell: darker than the cell's grey, in either theme, since the cell is the same grey in both. */
private val EmptyIconColor = Color(0xFF6E6E6E)

/** How far, in degrees, two fingers must turn before the photo under them tilts. */
private const val ROTATION_SLOP = 10f

/** What a finger that came down on a cell turned out to want, once it is clear. */
private enum class Touch { TAP, MOVE, HOLD }

/** A photo being carried from cell [from], the finger now at [at]. */
private data class Carry(val from: Int, val at: Offset)

/**
 * The collage as large as it fits in the space given, with every touch on
 * it: a tap selects a cell or asks for a photo for an empty one; a drag or a
 * pinch moves and zooms the photo under it; a drag on a gap moves that cut,
 * slant and all, and a tap on it shows a dot on each of its ends, a drag of
 * which tilts it; a long press picks a photo up to drop on another cell,
 * where the two trade places.
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
        val shownCut by rememberUpdatedState(state.cut)
        val pickFor by rememberUpdatedState(onPickFor)
        val haptics = LocalHapticFeedback.current
        var carry by remember { mutableStateOf<Carry?>(null) }
        val accent = MaterialTheme.colorScheme.primary
        val handleRing = MaterialTheme.colorScheme.onPrimary
        // Black on the light window, white on the dark one.
        val edgeColor = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color.White else Color.Black
        val edge = with(density) { EDGE_STROKE.toPx() }
        val stroke = with(density) { SELECTION_STROKE.toPx() }
        val carried = with(density) { CARRIED_SIZE.toPx() }

        Box(
            Modifier
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .pointerInput(vm) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        // The shown cut's ends first: they lie on cells, which would take the touch.
                        val tilting = shownCut?.let(current::dividerOf)?.let { d ->
                            listOf(true to d.from, false to d.to)
                                .map { (atFrom, end) -> atFrom to (Offset(end.x, end.y) - start).getDistance() }
                                .filter { it.second <= HANDLE_REACH.toPx() }
                                .minByOrNull { it.second }
                                ?.let { d to it.first }
                        }
                        if (tilting != null) {
                            val (d, atFrom) = tilting
                            down.consume()
                            vm.beginGesture()
                            try {
                                drag(down.id) { change ->
                                    d.tiltedTo(atFrom, change.position.x, change.position.y)?.let { (a, b) ->
                                        vm.moveCut(d.path, a, b)
                                    }
                                    change.consume()
                                }
                            } finally {
                                vm.endGesture()
                            }
                            return@awaitEachGesture
                        }
                        val divider = current.dividerAt(start.x, start.y, DIVIDER_REACH.toPx())
                        if (divider != null) {
                            down.consume()
                            vm.beginGesture()
                            // The cut moves once the finger has: a touch that never leaves the
                            // slop is a tap on the cell under it, as the cut's reach overlaps it.
                            var moved = false
                            val released = try {
                                drag(down.id) { change ->
                                    if (!moved) moved = (change.position - start).getDistance() > viewConfiguration.touchSlop
                                    if (moved) {
                                        val (a, b) = divider.shiftedTo(change.position.x, change.position.y)
                                        vm.moveCut(divider.path, a, b)
                                    }
                                    change.consume()
                                }
                            } finally {
                                vm.endGesture()
                            }
                            if (released && !moved) {
                                val onCut = (divider.distance(start.x, start.y) ?: Float.MAX_VALUE) <= CUT_TAP_REACH.toPx()
                                if (onCut) {
                                    vm.tapCut(divider.path)
                                } else {
                                    current.cellAt(start.x, start.y)?.let { cell -> if (vm.tapCell(cell)) pickFor(cell) }
                                }
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
                                // A pinch turns a little by itself: the photo tilts only once the
                                // fingers have turned past ROTATION_SLOP, the whole way at once then.
                                var turnedSoFar = 0f
                                var tilting = false
                                try {
                                    do {
                                        val event = awaitPointerEvent()
                                        // The room the photo is framed in, as the drawing frames it.
                                        if (cell !in current.cells.indices) break
                                        val box = current.frameOf(cell)
                                        val centroid = event.calculateCentroid(useCurrent = true)
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        val turn = event.calculateRotation()
                                        val rotation = if (tilting) {
                                            turn
                                        } else {
                                            turnedSoFar += turn
                                            tilting = abs(turnedSoFar) > ROTATION_SLOP
                                            if (tilting) turnedSoFar else 0f
                                        }
                                        if (centroid != Offset.Unspecified && (pan != Offset.Zero || zoom != 1f || rotation != 0f)) {
                                            vm.transformPhoto(
                                                cell, box.width, box.height, pan.x, pan.y, zoom,
                                                centroid.x - box.centerX, centroid.y - box.centerY, rotation,
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
                // On screen only: drawCollage, which a saved image is drawn with too, knows nothing of it.
                drawRect(
                    color = edgeColor,
                    topLeft = Offset(edge / 2, edge / 2),
                    size = Size(size.width - edge, size.height - edge),
                    style = Stroke(width = edge),
                )
                state.selected?.let { outline(layout, it, accent, stroke, dashed = false) }
                state.cut?.let(layout::dividerOf)?.let { cut ->
                    val from = Offset(cut.from.x, cut.from.y)
                    val to = Offset(cut.to.x, cut.to.y)
                    drawLine(accent, from, to, strokeWidth = stroke)
                    val radius = with(density) { HANDLE_RADIUS.toPx() }
                    listOf(from, to).forEach { end ->
                        drawCircle(accent, radius, end)
                        drawCircle(handleRing, radius, end, style = Stroke(width = stroke / 1.5f))
                    }
                }
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
                // In the middle of a slanting cell's corners, where its room is, not of the box round it.
                val boxed = layout.isBoxAt(index)
                if (!boxed && thickness(layout.shapes[index]) < icon) return@forEachIndexed
                val middle = if (boxed) Pt(cell.centerX, cell.centerY) else middleOf(layout.shapes[index])
                Icon(
                    Icons.Filled.AddPhotoAlternate,
                    contentDescription = null,
                    tint = EmptyIconColor,
                    modifier = Modifier
                        .offset { IntOffset((middle.x - icon / 2).roundToInt(), (middle.y - icon / 2).roundToInt()) }
                        .size(EMPTY_ICON),
                )
            }
        }
    }
}

/** A ring round cell [index], in [color]: solid for the selected cell, dashed for one waiting to trade its photo. */
private fun DrawScope.outline(layout: CollageLayout, index: Int, color: Color, width: Float, dashed: Boolean) {
    val cell: CellBox = layout.cells.getOrNull(index) ?: return
    val style = Stroke(
        width = width,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 4, width * 3)) else null,
    )
    if (!layout.isBoxAt(index)) {
        // Inside the shape by half the line, as the box's ring is.
        val shape = insetShape(layout.shapes[index], width / 2)
        drawPath(shapePath(shape, max(0f, layout.corner - width / 2)).asComposePath(), color, style = style)
        return
    }
    val radius = layout.cornerOf(index)
    drawRoundRect(
        color = color,
        topLeft = Offset(cell.left + width / 2, cell.top + width / 2),
        size = Size(max(0f, cell.width - width), max(0f, cell.height - width)),
        cornerRadius = CornerRadius(radius, radius),
        style = style,
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
