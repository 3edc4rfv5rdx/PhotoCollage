package xx.photocollage

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** A rectangle in pixels; free of android.graphics so the layout is testable on the JVM. */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom

    fun inset(by: Float): Box = Box(left + by, top + by, right - by, bottom - by)

    /** Its corners clockwise on the screen from the top left, as a [Shape]. */
    val corners: List<Pt> get() = listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom))
}

/** A point in pixels. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(k: Float) = Pt(x * k, y * k)
    val length: Float get() = hypot(x, y)
}

/**
 * A convex outline in pixels: its corners clockwise on the screen, the way
 * [Box.corners] goes; fewer than three when nothing is left of it. Every
 * cell is one: a box while its cuts are straight, any convex shape once one
 * slants.
 */
typealias Shape = List<Pt>

/**
 * Which side of the line from [a] through [b] [p] lies on: above 0 to the
 * right of the way it runs on the screen, below 0 to the left — the inside of
 * a [Shape] is to the right of each of its sides.
 */
private fun side(a: Pt, b: Pt, p: Pt): Float = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

/** The part of [shape] on the right of the line from [a] through [b], [right] false for the left part instead. */
internal fun clipShape(shape: Shape, a: Pt, b: Pt, right: Boolean = true): Shape {
    if (shape.size < 3) return emptyList()
    val sign = if (right) 1f else -1f
    val out = ArrayList<Pt>(shape.size + 2)
    for (i in shape.indices) {
        val p = shape[i]
        val q = shape[(i + 1) % shape.size]
        val sp = sign * side(a, b, p)
        val sq = sign * side(a, b, q)
        if (sp >= 0f) out += p
        if ((sp > 0f && sq < 0f) || (sp < 0f && sq > 0f)) out += p + (q - p) * (sp / (sp - sq))
    }
    return withoutRepeats(out)
}

/** [points] without a point right on the one before it, the last against the first too; none at all when fewer than three are left. */
private fun withoutRepeats(points: List<Pt>): Shape {
    val out = ArrayList<Pt>(points.size)
    for (p in points) if (out.isEmpty() || (p - out.last()).length > SAME_POINT) out += p
    while (out.size > 1 && (out.first() - out.last()).length <= SAME_POINT) out.removeAt(out.size - 1)
    return if (out.size < 3) emptyList() else out
}

/** How near two points are taken for one, in pixels. */
private const val SAME_POINT = 1e-3f

/**
 * [shape] with every side moved [by] pixels in, its corners where the moved
 * sides meet; nothing when it is no thicker than twice that. Out, by a
 * negative [by], a sharp corner reaches far: [roundedCorners] rounds it back.
 */
internal fun insetShape(shape: Shape, by: Float): Shape {
    if (shape.size < 3) return emptyList()
    if (by == 0f) return shape
    if (by < 0f) return outsetShape(shape, -by)
    var out = shape
    for (i in shape.indices) {
        val a = shape[i]
        val b = shape[(i + 1) % shape.size]
        val n = inward(a, b) * by
        out = clipShape(out, a + n, b + n)
        if (out.isEmpty()) return out
    }
    return out
}

/** [shape] with every side moved [by] pixels out, its corners where the moved sides meet. */
private fun outsetShape(shape: Shape, by: Float): Shape = shape.indices.map { i ->
    val prevA = shape[(i + shape.size - 1) % shape.size]
    val a = shape[i]
    val b = shape[(i + 1) % shape.size]
    val n1 = inward(prevA, a) * -by
    val n2 = inward(a, b) * -by
    lineCross(prevA + n1, a + n1, a + n2, b + n2) ?: (a + n2)
}

/** The unit normal of the side from [a] to [b] pointing into the shape, which lies to its right. */
internal fun inward(a: Pt, b: Pt): Pt {
    val d = b - a
    val len = d.length
    return if (len == 0f) Pt(0f, 0f) else Pt(-d.y / len, d.x / len)
}

/** Where the line through [a] and [b] crosses the one through [c] and [d]; null when they run side by side. */
private fun lineCross(a: Pt, b: Pt, c: Pt, d: Pt): Pt? {
    val r = b - a
    val s = d - c
    val denom = r.x * s.y - r.y * s.x
    if (abs(denom) < 1e-9f) return null
    val t = ((c.x - a.x) * s.y - (c.y - a.y) * s.x) / denom
    return a + r * t
}

/** The box round [shape]; an empty one at its first point, or at the origin, when it has no inside. */
internal fun boundsOf(shape: Shape): Box {
    if (shape.size < 3) return shape.firstOrNull()?.let { Box(it.x, it.y, it.x, it.y) } ?: Box(0f, 0f, 0f, 0f)
    return Box(shape.minOf { it.x }, shape.minOf { it.y }, shape.maxOf { it.x }, shape.maxOf { it.y })
}

/** Whether ([x], [y]) is inside [shape] or on its edge. */
internal fun shapeContains(shape: Shape, x: Float, y: Float): Boolean {
    if (shape.size < 3) return false
    val p = Pt(x, y)
    return shape.indices.all { i -> side(shape[i], shape[(i + 1) % shape.size], p) >= 0f }
}

/** How far ([x], [y]) is from [shape]: 0 inside it. */
internal fun distanceTo(shape: Shape, x: Float, y: Float): Float {
    if (shapeContains(shape, x, y)) return 0f
    if (shape.isEmpty()) return Float.MAX_VALUE
    val p = Pt(x, y)
    return shape.indices.minOf { i -> segmentDistance(p, shape[i], shape[(i + 1) % shape.size]) }
}

/** How far [p] is from the segment from [a] to [b]. */
private fun segmentDistance(p: Pt, a: Pt, b: Pt): Float {
    val d = b - a
    val len2 = d.x * d.x + d.y * d.y
    val t = if (len2 == 0f) 0f else (((p.x - a.x) * d.x + (p.y - a.y) * d.y) / len2).coerceIn(0f, 1f)
    return (p - (a + d * t)).length
}

/**
 * How thick [shape] is at its thinnest: the least of how far it reaches from
 * each of its sides. A box's shorter side; a thin wedge's few pixels.
 */
internal fun thickness(shape: Shape): Float {
    if (shape.size < 3) return 0f
    return shape.indices.minOf { i ->
        val a = shape[i]
        val b = shape[(i + 1) % shape.size]
        val len = (b - a).length
        if (len == 0f) Float.MAX_VALUE else shape.maxOf { abs(side(a, b, it)) } / len
    }
}

/** The middle of [shape]'s corners: where a sign on it sits. */
internal fun middleOf(shape: Shape): Pt =
    if (shape.isEmpty()) Pt(0f, 0f) else Pt(shape.sumOf { it.x.toDouble() }.toFloat() / shape.size, shape.sumOf { it.y.toDouble() }.toFloat() / shape.size)

/** Whether [shape] is [box] itself: four corners, square to the canvas, as every cell is while no cut slants. */
internal fun isBox(shape: Shape, box: Box): Boolean = shape == box.corners

/**
 * One corner of a shape rounded off: the arc from [start], on the side
 * coming in, to [end], on the side going out, round [center] at [radius],
 * starting at [startAngle] degrees and sweeping [sweep] — as Android's arcTo
 * takes them.
 */
data class RoundCorner(val start: Pt, val end: Pt, val center: Pt, val radius: Float, val startAngle: Float, val sweep: Float)

/**
 * [shape]'s corners rounded by [radius], each no further along its sides
 * than half of the shorter of them, so two roundings never cross — as a box
 * caps its rounding at half its shorter side. A corner too straight to
 * round, or a radius of 0, is a corner of no size at the point itself.
 */
fun roundedCorners(shape: Shape, radius: Float): List<RoundCorner> = shape.indices.map { i ->
    val p0 = shape[(i + shape.size - 1) % shape.size]
    val v = shape[i]
    val p1 = shape[(i + 1) % shape.size]
    val l0 = (p0 - v).length
    val l1 = (p1 - v).length
    if (radius <= 0f || l0 == 0f || l1 == 0f) return@map RoundCorner(v, v, v, 0f, 0f, 0f)
    val u0 = (p0 - v) * (1f / l0)
    val u1 = (p1 - v) * (1f / l1)
    val angle = acos((u0.x * u1.x + u0.y * u1.y).coerceIn(-1f, 1f))
    if (angle < 1e-3f || angle > Math.PI.toFloat() - 1e-3f) return@map RoundCorner(v, v, v, 0f, 0f, 0f)
    val half = tan(angle / 2)
    val reach = min(radius / half, min(l0, l1) / 2)
    val r = reach * half
    val start = v + u0 * reach
    val end = v + u1 * reach
    val bisector = (u0 + u1).let { it * (1f / it.length) }
    val center = v + bisector * (r / sin(angle / 2))
    val a0 = Math.toDegrees(atan2((start.y - center.y).toDouble(), (start.x - center.x).toDouble())).toFloat()
    val a1 = Math.toDegrees(atan2((end.y - center.y).toDouble(), (end.x - center.x).toDouble())).toFloat()
    var sweep = a1 - a0
    while (sweep > 180f) sweep -= 360f
    while (sweep <= -180f) sweep += 360f
    RoundCorner(start, end, center, r, a0, sweep)
}

/**
 * A cut as it lies on the canvas: [path] finds it in the tree (see
 * [withCut]), [span] is the box round the room it divides, [ratio] and
 * [ratio2] where it crosses that box's sides, and [from] to [to] the line
 * itself, as far as it runs through the room, in the middle of the gap —
 * [from] at the end [ratio] gives.
 */
data class Divider(
    val path: List<Boolean>,
    val dir: Dir,
    val span: Box,
    val ratio: Float,
    val ratio2: Float,
    val from: Pt,
    val to: Pt,
) {
    /** Where the cut crosses the side of [span] it starts from, and the one it ends on. */
    private val ends: Pair<Pt, Pt>
        get() = endsOf(dir, span, ratio, ratio2)

    /**
     * The cut's ratios were it moved, keeping its slant, to run through
     * ([x], [y]); held where [cutRatios] lets it lie, the slant kept.
     */
    fun shiftedTo(x: Float, y: Float): Pair<Float, Float> {
        val shift = if (dir == Dir.ROW) {
            val along = if (span.height == 0f) 0f else (y - span.top) / span.height
            (x - span.left) / span.width - (ratio + (ratio2 - ratio) * along)
        } else {
            val along = if (span.width == 0f) 0f else (x - span.left) / span.width
            (y - span.top) / span.height - (ratio + (ratio2 - ratio) * along)
        }
        val middle = (ratio + ratio2) / 2
        val low = maxOf(-ratio, -ratio2, MIN_RATIO - middle)
        val high = minOf(1f - ratio, 1f - ratio2, 1f - MIN_RATIO - middle)
        val held = if (low > high) 0f else shift.coerceIn(low, high)
        return (ratio + held) to (ratio2 + held)
    }

    /**
     * The cut's ratios were its end [atFrom] — the [from] end, else the [to]
     * one — dragged to ([x], [y]), the other end staying where it crosses its
     * side; null when ([x], [y]) is no further from that side than the other
     * end is, where the cut could not run through it.
     */
    fun tiltedTo(atFrom: Boolean, x: Float, y: Float): Pair<Float, Float>? {
        val (a, b) = ends
        val fixed = if (atFrom) b else a
        val ratioOf = if (dir == Dir.ROW) {
            val edge = if (atFrom) span.top else span.bottom
            if ((y - fixed.y) * (edge - fixed.y) <= 0f || abs(y - fixed.y) < 1f) return null
            val at = fixed.x + (x - fixed.x) * (edge - fixed.y) / (y - fixed.y)
            (at - span.left) / span.width
        } else {
            val edge = if (atFrom) span.left else span.right
            if ((x - fixed.x) * (edge - fixed.x) <= 0f || abs(x - fixed.x) < 1f) return null
            val at = fixed.y + (y - fixed.y) * (edge - fixed.x) / (x - fixed.x)
            (at - span.top) / span.height
        }
        if (!ratioOf.isFinite()) return null
        return if (atFrom) cutRatios(ratioOf, ratio2) else cutRatios(ratio, ratioOf)
    }

    /**
     * How far from the line a touch inside [cell] still takes it: as far as
     * the cell's side by it, and a quarter of the cell past that, so the
     * middle half of every cell is the cell's however narrow it is.
     */
    fun reachInto(cell: Shape): Float {
        if (cell.isEmpty()) return 0f
        val distances = cell.map { abs(lineDistance(it)) }
        val near = distances.min()
        return near + (distances.max() - near) / 4
    }

    /** How far ([x], [y]) is from the line, or null when it is beside the line's length rather than across it. */
    fun distance(x: Float, y: Float): Float? {
        val d = to - from
        val len2 = d.x * d.x + d.y * d.y
        if (len2 == 0f) return null
        val t = ((x - from.x) * d.x + (y - from.y) * d.y) / len2
        if (t < 0f || t > 1f) return null
        return abs(lineDistance(Pt(x, y)))
    }

    /** How far [p] is from the line through [from] and [to], to one side or the other. */
    private fun lineDistance(p: Pt): Float {
        val len = (to - from).length
        return if (len == 0f) 0f else side(from, to, p) / len
    }
}

/**
 * Where a cut of [dir] at [ratio] and [ratio2] crosses the sides of [box]:
 * the top and the bottom for a [Dir.ROW] cut, the left and the right for a
 * [Dir.COLUMN] one.
 */
private fun endsOf(dir: Dir, box: Box, ratio: Float, ratio2: Float): Pair<Pt, Pt> = if (dir == Dir.ROW) {
    Pt(box.left + box.width * ratio, box.top) to Pt(box.left + box.width * ratio2, box.bottom)
} else {
    Pt(box.left, box.top + box.height * ratio) to Pt(box.right, box.top + box.height * ratio2)
}

/**
 * Where everything lies on a canvas of a given size: the cells in reading
 * order, each by its [shapes] outline and the [cells] box round it, the
 * cuts, and the pixel sizes.
 */
class CollageLayout(
    val cells: List<Box>,
    val dividers: List<Divider>,
    /** The rounding of each cell's corners, in pixels, before a small cell caps it. */
    val corner: Float,
    /** How far, in pixels, each photo fades over its neighbours past its cell's edges; 0 for none. */
    val blend: Float = 0f,
    /** Each cell's outline: its box's corners while its cuts are straight. */
    val shapes: List<Shape> = cells.map { it.corners },
    /** The gap round and between the cells, in pixels. */
    val gap: Float = 0f,
    /** The overlays as they lie on the canvas, the last on top. */
    val overlays: List<OverlayPlace> = emptyList(),
) {
    /** The overlay under ([x], [y]), the topmost if more are; null when none is. */
    fun overlayAt(x: Float, y: Float): Int? = overlays.indexOfLast { it.contains(x, y) }.takeIf { it >= 0 }

    /**
     * The room [cells][index]'s photo is framed in: the box round the cell,
     * and as far past it as the photo fades over its neighbours, so the photo
     * is there to fade. A gesture moves the photo within it as the drawing
     * does.
     */
    fun frameOf(index: Int): Box = cells[index].inset(-blend)

    /** Whether [cells][index] is a box, not a shape a slanting cut made. */
    fun isBoxAt(index: Int): Boolean = isBox(shapes[index], cells[index])

    /**
     * The room the cells take together, the gap round the edge left out: past
     * it a cell has no neighbour for its photo to fade over.
     */
    val extent: Box
        get() = Box(cells.minOf { it.left }, cells.minOf { it.top }, cells.maxOf { it.right }, cells.maxOf { it.bottom })

    /** The cell under ([x], [y]), or null for a gap. */
    fun cellAt(x: Float, y: Float): Int? = shapes.indexOfFirst { shapeContains(it, x, y) }.takeIf { it >= 0 }

    /** The cell nearest ([x], [y]): the one under it, or across the gap from it. */
    fun nearestCell(x: Float, y: Float): Int? = cellAt(x, y) ?: shapes.indices.minByOrNull { distanceTo(shapes[it], x, y) }

    /**
     * The cut within [reach] of ([x], [y]), the nearest if more are; null when
     * none is. Inside a cell a cut reaches no further than [Divider.reachInto].
     */
    fun dividerAt(x: Float, y: Float, reach: Float): Divider? {
        val inside = cellAt(x, y)?.let { shapes[it] }
        return dividers
            .mapNotNull { d -> d.distance(x, y)?.let { d to it } }
            .filter { (d, distance) -> distance <= reach && (inside == null || distance <= d.reachInto(inside)) }
            .minByOrNull { it.second }
            ?.first
    }

    /** The cut at [path] in the tree, or null when there is none. */
    fun dividerOf(path: List<Boolean>): Divider? = dividers.firstOrNull { it.path == path }

    /** [cells][index]'s corner rounding: [corner], but never more than half its shorter side. */
    fun cornerOf(index: Int): Float = min(corner, min(cells[index].width, cells[index].height) / 2)
}

/**
 * Lays [collage] out on a canvas [width] by [height] pixels. The gap goes
 * round the edge as well as between cells, the same width everywhere: the
 * cuts divide the canvas less half a gap all round, and each cell gives up
 * another half on every side. While the cuts are straight every room is a
 * box and is reckoned as one, to the pixel; a slanting cut makes shapes.
 */
fun layoutOf(collage: Collage, width: Float, height: Float): CollageLayout {
    val short = min(width, height)
    val gap = collage.gap * short
    val cells = mutableListOf<Box>()
    val shapes = mutableListOf<Shape>()
    val dividers = mutableListOf<Divider>()

    fun place(node: Node, room: Shape, box: Box, path: List<Boolean>) {
        val boxed = isBox(room, box)
        when (node) {
            is Leaf -> if (boxed) {
                val cell = box.inset(gap / 2)
                cells += cell
                shapes += cell.corners
            } else {
                val shape = insetShape(room, gap / 2)
                cells += if (shape.isEmpty()) Box(box.centerX, box.centerY, box.centerX, box.centerY) else boundsOf(shape)
                shapes += shape
            }
            is Split -> {
                val (a, b) = endsOf(node.dir, box, node.ratio, node.ratio2)
                if (boxed && node.straight) {
                    val (firstBox, secondBox) = if (node.dir == Dir.ROW) {
                        box.copy(right = a.x) to box.copy(left = a.x)
                    } else {
                        box.copy(bottom = a.y) to box.copy(top = a.y)
                    }
                    dividers += Divider(path, node.dir, box, node.ratio, node.ratio2, a, b)
                    place(node.first, firstBox.corners, firstBox, path + false)
                    place(node.second, secondBox.corners, secondBox, path + true)
                } else {
                    // A cut running down has its first part on its right as it runs, the left
                    // of the canvas; one running across has its first part, the top, on its left.
                    val firstOnRight = node.dir == Dir.ROW
                    val first = clipShape(room, a, b, right = firstOnRight)
                    val second = clipShape(room, a, b, right = !firstOnRight)
                    val (from, to) = segmentIn(room, a, b)
                    dividers += Divider(path, node.dir, box, node.ratio, node.ratio2, from, to)
                    place(node.first, first, boundsOf(first), path + false)
                    place(node.second, second, boundsOf(second), path + true)
                }
            }
        }
    }

    val canvas = Box(0f, 0f, width, height).inset(gap / 2)
    place(collage.root, canvas.corners, canvas, emptyList())
    val overlays = collage.overlays.map { placeOf(it, width, height) }
    return CollageLayout(cells, dividers, collage.corner * short, collage.blend * short, shapes, gap, overlays)
}

/** The part of the line from [a] to [b] that lies in [shape]; the whole of it when the shape is gone. */
private fun segmentIn(shape: Shape, a: Pt, b: Pt): Pair<Pt, Pt> {
    if (shape.size < 3) return a to b
    var t0 = 0f
    var t1 = 1f
    val d = b - a
    for (i in shape.indices) {
        val p = shape[i]
        val q = shape[(i + 1) % shape.size]
        // Inside is where side() is at least 0; along the line it changes by this much per t.
        val at = side(p, q, a)
        val rate = side(p, q, b) - at
        if (rate == 0f) {
            if (at < 0f) return a to a
        } else {
            val t = -at / rate
            if (rate > 0f) t0 = max(t0, t) else t1 = min(t1, t)
        }
    }
    return if (t0 > t1) a to a else (a + d * t0) to (a + d * t1)
}

/** The smallest a cut or a drag may make a cell either way, as a part of the canvas's shorter side. */
const val MIN_CELL = 0.05f

/** How thick the thinnest cell of [collage] is, as a part of the canvas's shorter side; see [thickness]. */
fun Collage.smallestCell(): Float {
    val short = min(ratio, 1f)
    val layout = layoutOf(this, ratio, 1f)
    return layout.shapes.indices.minOf { i ->
        if (layout.isBoxAt(i)) min(layout.cells[i].width, layout.cells[i].height) else thickness(layout.shapes[i])
    } / short
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
