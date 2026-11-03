package xx.photocollage

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The forms an overlay can take. [rounds] when the collage's corner rounding
 * rounds its corners: a circle and a heart have none to round.
 */
enum class OverlayShape(val rounds: Boolean) {
    CIRCLE(false),
    SQUARE(true),
    TRIANGLE(true),
    DIAMOND(true),
    HEXAGON(true),
    STAR(true),
    HEART(false),
}

/**
 * A photo in a shape of its own on top of the cells. [centerX] and [centerY]
 * are where its middle is, as parts of the canvas's width and height;
 * [size] the side of the square it fits in, as a part of the canvas's
 * shorter side; [angle] the degrees clockwise it is turned by, photo and
 * all, within ±180.
 */
data class Overlay(
    val shape: OverlayShape,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val size: Float = DEFAULT_OVERLAY,
    val angle: Float = 0f,
    val photo: CellPhoto? = null,
)

/** How many overlays a collage may have. */
const val MAX_OVERLAYS = 6

/** The size a new overlay starts at, and the least and most one may have; see [Overlay.size]. */
const val DEFAULT_OVERLAY = 0.4f
const val MIN_OVERLAY = 0.1f
const val MAX_OVERLAY = 1.2f

/** How many points a circle's and a heart's outlines are drawn with: smooth at any size a collage is saved at. */
private const val CURVE_POINTS = 120

/**
 * [shape]'s outline in a square of side 1 round the origin, clockwise on the
 * screen, y down; the circle and the heart as many short sides.
 */
fun outlineOf(shape: OverlayShape): List<Pt> = OUTLINES.getValue(shape)

/** Every shape's outline, made once: a layout asks for them at every frame of a gesture. */
private val OUTLINES: Map<OverlayShape, List<Pt>> by lazy { OverlayShape.entries.associateWith(::makeOutline) }

private fun makeOutline(shape: OverlayShape): List<Pt> = when (shape) {
    OverlayShape.CIRCLE -> List(CURVE_POINTS) { i -> polar(0.5f, i * 2 * PI / CURVE_POINTS) }
    OverlayShape.SQUARE -> Box(-0.5f, -0.5f, 0.5f, 0.5f).corners
    // Each side 1, its foot along the bottom, centred top to bottom.
    OverlayShape.TRIANGLE -> listOf(Pt(0f, -0.433f), Pt(0.5f, 0.433f), Pt(-0.5f, 0.433f))
    OverlayShape.DIAMOND -> listOf(Pt(0f, -0.5f), Pt(0.5f, 0f), Pt(0f, 0.5f), Pt(-0.5f, 0f))
    OverlayShape.HEXAGON -> List(6) { i -> polar(0.5f, -PI / 2 + i * PI / 3) }
    // Five points, the first straight up; the inner corners at 0.38 of the outer reach.
    OverlayShape.STAR -> centred(List(10) { i -> polar(if (i % 2 == 0) 0.5f else 0.19f, -PI / 2 + i * PI / 5) })
    OverlayShape.HEART -> heart()
}

/** The point [radius] from the origin at [angle] radians clockwise from the right. */
private fun polar(radius: Float, angle: Double) = Pt((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())

/** The classic heart curve, fitted to the square of side 1 and centred in it, clockwise. */
private fun heart(): List<Pt> {
    val raw = List(CURVE_POINTS) { i ->
        val t = i * 2 * PI / CURVE_POINTS
        val x = 16 * sin(t).pow(3)
        val y = 13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t)
        // y up in the formula, down on the screen: turned over, the heart stands upright.
        Pt(x.toFloat(), (-y).toFloat())
    }
    val box = boundsOf(raw)
    return centred(raw.map { it * (1f / maxOf(box.width, box.height)) })
}

/** [points] moved so the box round them has its middle at the origin. */
private fun centred(points: List<Pt>): List<Pt> {
    val box = boundsOf(points)
    return points.map { Pt(it.x - box.centerX, it.y - box.centerY) }
}

/**
 * An overlay as it lies on a canvas: [center] and [side] in pixels, turned
 * [angle] degrees; [outline] its shape on the canvas, and [frame] the box its
 * photo is framed in, round [center] before it is turned.
 */
class OverlayPlace(val center: Pt, val side: Float, val angle: Float, val outline: List<Pt>, val frame: Box) {

    /** [p] on the canvas as seen from the overlay: from its centre, turned back by its angle. */
    fun toLocal(p: Pt): Pt = rotate(p - center, -angle)

    /** [p] from the overlay's centre, unturned, back on the canvas. */
    fun toCanvas(p: Pt): Pt = center + rotate(p, angle)

    /** Where the handle that sizes and turns it lies: the frame's lower right corner, turned with it. */
    val handle: Pt get() = toCanvas(Pt(frame.right, frame.bottom))

    /**
     * The handle where a finger can reach it within [room] — the canvas, kept
     * off its edge: the frame's lower right corner, else the first of its
     * other corners that is in [room], else the lower right one brought in to
     * [room]'s nearest point. Sizing and turning go by the distance and the
     * angle from [center], so any of them does.
     */
    fun handleWithin(room: Box): Pt {
        val corners = listOf(
            Pt(frame.right, frame.bottom),
            Pt(frame.left, frame.bottom),
            Pt(frame.right, frame.top),
            Pt(frame.left, frame.top),
        ).map(::toCanvas)
        return corners.firstOrNull { room.contains(it.x, it.y) }
            ?: handle.let { Pt(it.x.coerceIn(room.left, room.right), it.y.coerceIn(room.top, room.bottom)) }
    }

    /**
     * The room its photo is framed in, round [center] before it is turned:
     * [frame], and as far past it as a [blend] in pixels softens its edge, so
     * the photo is there to fade.
     */
    fun photoFrame(blend: Float): Box = frame.inset(-blend)

    /** Whether ([x], [y]) is in its shape. */
    fun contains(x: Float, y: Float): Boolean = insidePolygon(outline, x, y)
}

/** [overlay] laid on a canvas [width] by [height] pixels; see [OverlayPlace]. */
fun placeOf(overlay: Overlay, width: Float, height: Float): OverlayPlace {
    val side = overlay.size * min(width, height)
    val center = Pt(overlay.centerX * width, overlay.centerY * height)
    val local = outlineOf(overlay.shape).map { it * side }
    val outline = local.map { center + rotate(it, overlay.angle) }
    return OverlayPlace(center, side, overlay.angle, outline, boundsOf(local))
}

/** [p] turned [degrees] clockwise on the screen round the origin. */
internal fun rotate(p: Pt, degrees: Float): Pt {
    if (degrees == 0f) return p
    val rad = Math.toRadians(degrees.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return Pt(p.x * c - p.y * s, p.x * s + p.y * c)
}

/** Whether ([x], [y]) is inside [polygon], convex or not: an even count of its sides crossed going right is out. */
internal fun insidePolygon(polygon: List<Pt>, x: Float, y: Float): Boolean {
    var inside = false
    var j = polygon.size - 1
    for (i in polygon.indices) {
        val a = polygon[i]
        val b = polygon[j]
        if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
        j = i
    }
    return inside
}

/** The angle, in degrees clockwise from the right, of [p] seen from the origin. */
internal fun angleOf(p: Pt): Float = Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble())).toFloat()

// ---------- The collage's overlays ----------

/** [overlay] put on top, the overlays there kept; none more than [MAX_OVERLAYS]. */
fun Collage.withOverlay(overlay: Overlay): Collage =
    if (overlays.size >= MAX_OVERLAYS) this else copy(overlays = overlays + overlay)

/** The collage with [change] made to overlay [index]; an index of none changes nothing. */
fun Collage.mapOverlay(index: Int, change: (Overlay) -> Overlay): Collage =
    if (index !in overlays.indices) this else copy(overlays = overlays.mapIndexed { i, o -> if (i == index) change(o) else o })

/** The collage without overlay [index]. */
fun Collage.withoutOverlay(index: Int): Collage =
    if (index !in overlays.indices) this else copy(overlays = overlays.filterIndexed { i, _ -> i != index })

/** Overlay [index] moved [by] places up the stack — down for a negative [by] — as far as it goes; its new index. */
fun Collage.restacked(index: Int, by: Int): Pair<Collage, Int> {
    if (index !in overlays.indices) return this to index
    val to = (index + by).coerceIn(overlays.indices)
    val list = overlays.toMutableList()
    list.add(to, list.removeAt(index))
    return copy(overlays = list) to to
}

/** [overlay] moved so its middle is at ([x], [y]), as parts of the canvas, kept on the canvas. */
fun Overlay.movedTo(x: Float, y: Float): Overlay = copy(centerX = x.coerceIn(0f, 1f), centerY = y.coerceIn(0f, 1f))

/** [overlay] at [size], held between [MIN_OVERLAY] and [MAX_OVERLAY], turned to [angle]. */
fun Overlay.sizedTo(size: Float, angle: Float): Overlay =
    copy(size = size.coerceIn(MIN_OVERLAY, MAX_OVERLAY), angle = normalizedAngle(angle))
