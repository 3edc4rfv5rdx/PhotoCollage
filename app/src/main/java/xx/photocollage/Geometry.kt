package xx.photocollage

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A rectangle in pixels; free of android.graphics so the layout is testable on the JVM. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun inset(by: Float): Box = Box(left + by, top + by, right - by, bottom - by)
}

/**
 * A cut as it lies on the canvas: [path] finds it in the tree (see
 * [withRatio]), [span] is the room it divides, and [at] the line itself —
 * an x for a [Dir.ROW] cut, a y for a [Dir.COLUMN] one — in the middle of
 * the gap.
 */
data class Divider(val path: List<Boolean>, val dir: Dir, val span: Box, val at: Float) {

    /** The cut's ratio were its line dragged to ([x], [y]). */
    fun ratioAt(x: Float, y: Float): Float =
        if (dir == Dir.ROW) (x - span.left) / span.width else (y - span.top) / span.height

    /**
     * How far from the line a touch inside [cell] still takes it: as far as
     * the cell's edge on this side, and a quarter of the cell past it, so the
     * middle half of every cell is the cell's however narrow it is.
     */
    fun reachInto(cell: Box): Float = if (dir == Dir.ROW) {
        abs(at - if (cell.centerX < at) cell.right else cell.left) + cell.width / 4
    } else {
        abs(at - if (cell.centerY < at) cell.bottom else cell.top) + cell.height / 4
    }

    /** How far ([x], [y]) is from the line, or null when it is beside the line's length rather than across it. */
    fun distance(x: Float, y: Float): Float? = if (dir == Dir.ROW) {
        if (y in span.top..span.bottom) abs(x - at) else null
    } else {
        if (x in span.left..span.right) abs(y - at) else null
    }
}

/** Where everything lies on a canvas of a given size: the cells in reading order, the cuts, and the pixel sizes. */
class CollageLayout(
    val cells: List<Box>,
    val dividers: List<Divider>,
    /** The rounding of each cell's corners, in pixels, before a small cell caps it. */
    val corner: Float,
    /** How far, in pixels, each photo fades over its neighbours past its cell's edges; 0 for none. */
    val blend: Float = 0f,
) {
    /**
     * The room [cells][index]'s photo is framed in: the cell, and as far
     * past it as the photo fades over its neighbours, so the photo is there to
     * fade. A gesture moves the photo within it as the drawing does.
     */
    fun frameOf(index: Int): Box = cells[index].inset(-blend)

    /** The cell under ([x], [y]), or null for a gap. */
    fun cellAt(x: Float, y: Float): Int? = cells.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }

    /** The cell nearest ([x], [y]): the one under it, or across the gap from it. */
    fun nearestCell(x: Float, y: Float): Int? = cellAt(x, y) ?: cells.indices.minByOrNull { i ->
        val c = cells[i]
        val dx = max(max(c.left - x, 0f), x - c.right)
        val dy = max(max(c.top - y, 0f), y - c.bottom)
        dx * dx + dy * dy
    }

    /**
     * The cut within [reach] of ([x], [y]), the nearest if more are; null when
     * none is. Inside a cell a cut reaches no further than [Divider.reachInto].
     */
    fun dividerAt(x: Float, y: Float, reach: Float): Divider? {
        val inside = cellAt(x, y)?.let { cells[it] }
        return dividers
            .mapNotNull { d -> d.distance(x, y)?.let { d to it } }
            .filter { (d, distance) -> distance <= reach && (inside == null || distance <= d.reachInto(inside)) }
            .minByOrNull { it.second }
            ?.first
    }

    /** [cells][index]'s corner rounding: [corner], but never more than half its shorter side. */
    fun cornerOf(index: Int): Float = min(corner, min(cells[index].width, cells[index].height) / 2)
}

/**
 * Lays [collage] out on a canvas [width] by [height] pixels. The gap goes
 * round the edge as well as between cells, the same width everywhere: the
 * cuts divide the canvas less half a gap all round, and each cell gives up
 * another half on every side.
 */
fun layoutOf(collage: Collage, width: Float, height: Float): CollageLayout {
    val short = min(width, height)
    val gap = collage.gap * short
    val cells = mutableListOf<Box>()
    val dividers = mutableListOf<Divider>()

    fun place(node: Node, box: Box, path: List<Boolean>) {
        when (node) {
            is Leaf -> cells += box.inset(gap / 2)
            is Split -> {
                val (a, b, at) = if (node.dir == Dir.ROW) {
                    val x = box.left + box.width * node.ratio
                    Triple(box.copy(right = x), box.copy(left = x), x)
                } else {
                    val y = box.top + box.height * node.ratio
                    Triple(box.copy(bottom = y), box.copy(top = y), y)
                }
                dividers += Divider(path, node.dir, box, at)
                place(node.first, a, path + false)
                place(node.second, b, path + true)
            }
        }
    }

    place(collage.root, Box(0f, 0f, width, height).inset(gap / 2), emptyList())
    return CollageLayout(cells, dividers, collage.corner * short, collage.blend * short)
}

/** The smallest a cut or a drag may make a cell either way, as a part of the canvas's shorter side. */
const val MIN_CELL = 0.05f

/** The narrowest side of any cell of [collage], as a part of the canvas's shorter side. */
fun Collage.smallestCell(): Float {
    val short = min(ratio, 1f)
    return layoutOf(this, ratio, 1f).cells.minOf { min(it.width, it.height) } / short
}

/**
 * Whether [next], a change of this collage's cells, leaves every cell big
 * enough to see and touch: none under [MIN_CELL] — or, where one already is,
 * none smaller than before, so a collage that came that way can still be
 * worked on.
 */
fun Collage.cellsStayBigEnough(next: Collage): Boolean {
    val after = next.smallestCell()
    return after >= MIN_CELL || after >= smallestCell()
}

/** The size of the canvas of [ratio] that fits in [width] by [height], as large as it goes. */
fun fitCanvas(ratio: Float, width: Float, height: Float): Pair<Float, Float> =
    if (width / height > ratio) height * ratio to height else width to width / ratio

// ---------- A photo in its cell ----------

/**
 * The room the photo has to cover in its own frame: the cell [cellWidth] by
 * [cellHeight] turned back by [CellPhoto.angle], as the box round it. Upright,
 * the cell itself.
 */
private fun CellPhoto.span(cellWidth: Float, cellHeight: Float): Pair<Float, Float> {
    if (angle == 0f) return cellWidth to cellHeight
    val rad = Math.toRadians(angle.toDouble())
    val c = abs(cos(rad)).toFloat()
    val s = abs(sin(rad)).toFloat()
    return (cellWidth * c + cellHeight * s) to (cellWidth * s + cellHeight * c)
}

/**
 * The photo's shown size, in pixels, in a cell [cellWidth] by [cellHeight]:
 * just covering it at zoom 1 — tilted, covering the tilted cell's every
 * corner — larger with [CellPhoto.zoom].
 */
fun CellPhoto.shownSize(cellWidth: Float, cellHeight: Float): Pair<Float, Float> {
    val (spanW, spanH) = span(cellWidth, cellHeight)
    val ratio = shownRatio
    val w = zoom * max(spanW, spanH * ratio)
    return w to w / ratio
}

/**
 * This framing with its centre kept where the photo still covers the cell
 * [cellWidth] by [cellHeight] — no edge of it shows inside — and its zoom
 * kept between 1 and [MAX_ZOOM].
 */
fun CellPhoto.clamped(cellWidth: Float, cellHeight: Float): CellPhoto {
    val z = zoom.coerceIn(1f, MAX_ZOOM)
    val held = copy(zoom = z)
    val (w, h) = held.shownSize(cellWidth, cellHeight)
    val (spanW, spanH) = held.span(cellWidth, cellHeight)
    val halfX = min(spanW / w / 2, 0.5f)
    val halfY = min(spanH / h / 2, 0.5f)
    return held.copy(
        centerX = centerX.coerceIn(halfX, 1f - halfX),
        centerY = centerY.coerceIn(halfY, 1f - halfY),
    )
}

/**
 * This framing after a finger gesture over a cell [cellWidth] by
 * [cellHeight]: the fingers' middle moved by ([panX], [panY]) pixels to
 * ([pivotX], [pivotY]), given from the cell's middle, the photo zoomed by
 * [scale] and tilted by [rotation] degrees clockwise round it — the point of
 * the photo under the fingers stays under them.
 */
fun CellPhoto.transformed(
    cellWidth: Float,
    cellHeight: Float,
    panX: Float,
    panY: Float,
    scale: Float,
    pivotX: Float,
    pivotY: Float,
    rotation: Float = 0f,
): CellPhoto {
    val (w0, h0) = shownSize(cellWidth, cellHeight)
    // The photo's point under the fingers before they moved, in its own frame.
    val (ax, ay) = turnBack(pivotX - panX, pivotY - panY, angle)
    val u = centerX + ax / w0
    val v = centerY + ay / h0
    val moved = copy(zoom = (zoom * scale).coerceIn(1f, MAX_ZOOM), angle = normalizedAngle(angle + rotation))
    val (w1, h1) = moved.shownSize(cellWidth, cellHeight)
    // That point under the fingers where they are now.
    val (bx, by) = turnBack(pivotX, pivotY, moved.angle)
    return moved.copy(centerX = u - bx / w1, centerY = v - by / h1).clamped(cellWidth, cellHeight)
}

/** ([x], [y]) on the screen as seen in a photo tilted [degrees] clockwise: turned back by them. */
private fun turnBack(x: Float, y: Float, degrees: Float): Pair<Float, Float> {
    if (degrees == 0f) return x to y
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return (x * c + y * s) to (-x * s + y * c)
}

/** [degrees] brought within -180 (not included) to 180. */
internal fun normalizedAngle(degrees: Float): Float {
    var a = degrees % 360f
    if (a > 180f) a -= 360f
    if (a <= -180f) a += 360f
    return a
}

/** This photo turned a quarter clockwise, framed afresh: the old framing means nothing on the turned photo. */
fun CellPhoto.turned(): CellPhoto = refit(copy(quarter = (quarter + 1) % 4))
