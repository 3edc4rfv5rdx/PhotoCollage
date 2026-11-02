package xx.photocollage

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
) {
    /** The cell under ([x], [y]), or null for a gap. */
    fun cellAt(x: Float, y: Float): Int? = cells.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }

    /** The cell nearest ([x], [y]): the one under it, or across the gap from it. */
    fun nearestCell(x: Float, y: Float): Int? = cellAt(x, y) ?: cells.indices.minByOrNull { i ->
        val c = cells[i]
        val dx = max(max(c.left - x, 0f), x - c.right)
        val dy = max(max(c.top - y, 0f), y - c.bottom)
        dx * dx + dy * dy
    }

    /** The cut within [reach] of ([x], [y]), the nearest if more are; null when none is. */
    fun dividerAt(x: Float, y: Float, reach: Float): Divider? = dividers
        .mapNotNull { d -> d.distance(x, y)?.let { d to it } }
        .filter { it.second <= reach }
        .minByOrNull { it.second }
        ?.first

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
    return CollageLayout(cells, dividers, collage.corner * short)
}

/** The size of the canvas of [ratio] that fits in [width] by [height], as large as it goes. */
fun fitCanvas(ratio: Float, width: Float, height: Float): Pair<Float, Float> =
    if (width / height > ratio) height * ratio to height else width to width / ratio

// ---------- A photo in its cell ----------

/**
 * The photo's shown size, in pixels, in a cell [cellWidth] by [cellHeight]:
 * just covering it at zoom 1, larger with [CellPhoto.zoom].
 */
fun CellPhoto.shownSize(cellWidth: Float, cellHeight: Float): Pair<Float, Float> {
    val ratio = shownRatio
    val w = zoom * max(cellWidth, cellHeight * ratio)
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
    val halfX = min(cellWidth / w / 2, 0.5f)
    val halfY = min(cellHeight / h / 2, 0.5f)
    return held.copy(
        centerX = centerX.coerceIn(halfX, 1f - halfX),
        centerY = centerY.coerceIn(halfY, 1f - halfY),
    )
}

/**
 * This framing after a finger gesture over a cell [cellWidth] by
 * [cellHeight]: moved by ([panX], [panY]) pixels and zoomed by [scale]
 * round ([pivotX], [pivotY]), a point given from the cell's middle — the
 * point of the photo under the fingers stays under them.
 */
fun CellPhoto.transformed(
    cellWidth: Float,
    cellHeight: Float,
    panX: Float,
    panY: Float,
    scale: Float,
    pivotX: Float,
    pivotY: Float,
): CellPhoto {
    val (w0, h0) = shownSize(cellWidth, cellHeight)
    // The photo's point under the pivot, before.
    val u = centerX + pivotX / w0
    val v = centerY + pivotY / h0
    val zoomed = copy(zoom = (zoom * scale).coerceIn(1f, MAX_ZOOM))
    val (w1, h1) = zoomed.shownSize(cellWidth, cellHeight)
    // That point back under the pivot, then the pan.
    return zoomed.copy(
        centerX = u - pivotX / w1 - panX / w1,
        centerY = v - pivotY / h1 - panY / h1,
    ).clamped(cellWidth, cellHeight)
}

/** This photo turned a quarter clockwise, framed afresh: the old framing means nothing on the turned photo. */
fun CellPhoto.turned(): CellPhoto = refit(copy(quarter = (quarter + 1) % 4))
