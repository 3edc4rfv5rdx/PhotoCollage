package xx.photocollage

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.withSave
import kotlin.math.max
import kotlin.math.min

/** What an empty cell is filled with, on screen and in a saved image alike. */
const val EMPTY_CELL_COLOR = 0xFFC8C8C8.toInt()

/**
 * Draws [collage] on [canvas], [width] by [height] pixels from its origin —
 * the one drawing both the editor and a saved image use, so what is saved is
 * what was seen. [bitmapOf] gives each photo as far as it is loaded; a cell
 * whose photo is not is drawn empty. [onDrawn] gets each bitmap once it is
 * on the canvas, for a caller that decoded it for this drawing alone to let
 * it go before the next. An empty cell is filled with [emptyColor].
 *
 * With a [Collage.blend], the photos are drawn whole first, then each again
 * in a band past every edge of its cell, fading out over the neighbour: a
 * second pass, as a later cell's photo would cover an earlier one's band,
 * so [bitmapOf] and [onDrawn] are called twice for each photo then.
 */
fun drawCollage(
    canvas: Canvas,
    collage: Collage,
    width: Float,
    height: Float,
    layout: CollageLayout = layoutOf(collage, width, height),
    onDrawn: (Bitmap) -> Unit = {},
    emptyColor: Int = EMPTY_CELL_COLOR,
    bitmapOf: (Photo) -> Bitmap?,
) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // A rectangle, not drawColor: on screen the canvas is the whole window's,
    // and drawColor would paint over everything drawn on it before.
    paint.color = collage.background
    canvas.drawRect(0f, 0f, width, height, paint)
    val path = Path()
    val matrix = Matrix()
    // For a square cell's fill: no smoothing, see below.
    val flat = Paint()
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = layout.cells[index]
        if (cell.width <= 0f || cell.height <= 0f) return@forEachIndexed
        val rect = RectF(cell.left, cell.top, cell.right, cell.bottom)
        val radius = layout.cornerOf(index)
        val photo = leaf.photo
        val bitmap = photo?.let { bitmapOf(it.photo) }
        if (!layout.isBoxAt(index)) {
            shapePath(drawnShape(layout, index), layout.corner, path)
            if (photo == null || bitmap == null) {
                paint.color = emptyColor
                canvas.drawPath(path, paint)
            } else {
                canvas.withSave {
                    clipPath(path)
                    photoMatrix(photo, bitmap, layout.frameOf(index), matrix)
                    drawBitmap(bitmap, matrix, paint)
                }
                onDrawn(bitmap)
            }
            return@forEachIndexed
        }
        // Square corners are cut without smoothing: two cells meet on a fraction of a pixel,
        // and two smoothed edges there would each cover it part way, the background
        // showing through as a hairline even with no gap.
        val square = radius <= 0f
        if (photo == null || bitmap == null) {
            if (square) {
                canvas.drawRect(rect, flat.apply { color = emptyColor })
            } else {
                paint.color = emptyColor
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            return@forEachIndexed
        }
        canvas.withSave {
            if (square) {
                clipRect(rect)
            } else {
                path.reset()
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
                clipPath(path)
            }
            photoMatrix(photo, bitmap, layout.frameOf(index), matrix)
            drawBitmap(bitmap, matrix, paint)
        }
        onDrawn(bitmap)
    }
    if (layout.blend <= 0f) {
        drawOverlays(canvas, collage, layout, emptyColor, onDrawn, bitmapOf)
        return
    }
    val extent = layout.extent
    val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = layout.cells[index]
        val photo = leaf.photo ?: return@forEachIndexed
        if (cell.width <= 0f || cell.height <= 0f) return@forEachIndexed
        val bitmap = bitmapOf(photo.photo) ?: return@forEachIndexed
        photoMatrix(photo, bitmap, layout.frameOf(index), matrix)
        if (!layout.isBoxAt(index)) {
            drawShapeBands(canvas, layout, index, bitmap, matrix, paint, mask, path, extent)
            onDrawn(bitmap)
            return@forEachIndexed
        }
        // With rounded corners, the bands end on the cell's rounding carried out by the
        // blend, not square past it: the fade goes round the cell as its outline does.
        val radius = layout.cornerOf(index)
        if (radius > 0f) {
            val around = layout.frameOf(index)
            path.reset()
            path.addRoundRect(
                RectF(around.left, around.top, around.right, around.bottom),
                radius + layout.blend,
                radius + layout.blend,
                Path.Direction.CW,
            )
        }
        blendBands(cell, layout.blend, extent).forEach { band ->
            val area = RectF(band.box.left, band.box.top, band.box.right, band.box.bottom)
            val layer = canvas.saveLayer(area, null)
            canvas.clipRect(area)
            if (radius > 0f) canvas.clipPath(path)
            canvas.drawBitmap(bitmap, matrix, paint)
            // Half the photo at its cell's edge, where the neighbour's band gives the other
            // half, none at the band's far side.
            mask.shader = LinearGradient(
                band.fromX, band.fromY, band.toX, band.toY,
                BLEND_EDGE_ALPHA, 0, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(area, mask)
            canvas.restoreToCount(layer)
        }
        onDrawn(bitmap)
    }
    drawOverlays(canvas, collage, layout, emptyColor, onDrawn, bitmapOf)
}

/**
 * The overlays over the cells, the last on top. Each stands off what is under
 * it by a ring of the background as wide as the gap; its corners round with
 * the cells' where its shape has any; with a blend its edge is soft, half
 * the blend inside it and half out. [bitmapOf] and [onDrawn] are called once
 * for each, an empty one filled with [emptyColor].
 */
private fun drawOverlays(
    canvas: Canvas,
    collage: Collage,
    layout: CollageLayout,
    emptyColor: Int,
    onDrawn: (Bitmap) -> Unit,
    bitmapOf: (Photo) -> Bitmap?,
) {
    if (collage.overlays.isEmpty()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = collage.background
        strokeWidth = layout.gap * 2
    }
    // The soft edge: the shape blurred, laid as a whole layer over the photo's to keep it only
    // where the shape is — a layer, since a path drawn straight in would leave the photo past its reach.
    val feather = if (layout.blend > 0f) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(layout.blend, BlurMaskFilter.Blur.NORMAL) }
    } else {
        null
    }
    val keepWhereShape = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    val path = Path()
    val matrix = Matrix()
    collage.overlays.forEachIndexed { index, overlay ->
        val place = layout.overlays.getOrNull(index) ?: return@forEachIndexed
        shapePath(place.outline, if (overlay.shape.rounds) layout.corner else 0f, path)
        // The stroke lies half inside: the photo covers that half.
        if (layout.gap > 0f) canvas.drawPath(path, ring)
        val photo = overlay.photo
        val bitmap = photo?.let { bitmapOf(it.photo) }
        if (photo == null || bitmap == null) {
            paint.color = emptyColor
            canvas.drawPath(path, paint)
            return@forEachIndexed
        }
        photoMatrix(photo, bitmap, place.photoFrame(layout.blend), matrix)
        matrix.postRotate(place.angle)
        matrix.postTranslate(place.center.x, place.center.y)
        if (feather == null) {
            canvas.withSave {
                clipPath(path)
                drawBitmap(bitmap, matrix, paint)
            }
        } else {
            val reach = boundsOf(place.outline).inset(-layout.blend * 2)
            val area = RectF(reach.left, reach.top, reach.right, reach.bottom)
            val layer = canvas.saveLayer(area, null)
            canvas.drawBitmap(bitmap, matrix, paint)
            canvas.saveLayer(area, keepWhereShape)
            canvas.drawPath(path, feather)
            canvas.restoreToCount(layer)
        }
        onDrawn(bitmap)
    }
}

/**
 * The fade bands of cell [index], a shape a slanting cut made, its photo
 * [bitmap] placed by [matrix]: one past each of its sides, within [extent],
 * fading out away from the side; with rounded corners, within the shape's
 * rounding carried out by the blend, as a box's bands are.
 */
private fun drawShapeBands(
    canvas: Canvas,
    layout: CollageLayout,
    index: Int,
    bitmap: Bitmap,
    matrix: Matrix,
    paint: Paint,
    mask: Paint,
    path: Path,
    extent: Box,
) {
    val shape = layout.shapes[index]
    val round = layout.corner > 0f
    val around = Path()
    if (round) shapePath(insetShape(shape, -layout.blend), layout.corner + layout.blend, around)
    shapeBands(shape, layout.blend, extent).forEach { band ->
        val area = RectF(band.box.left, band.box.top, band.box.right, band.box.bottom)
        val layer = canvas.saveLayer(area, null)
        canvas.clipRect(area)
        shapePath(band.corners, 0f, path)
        canvas.clipPath(path)
        if (round) canvas.clipPath(around)
        canvas.drawBitmap(bitmap, matrix, paint)
        mask.shader = LinearGradient(
            band.from.x, band.from.y, band.to.x, band.to.y,
            BLEND_EDGE_ALPHA, 0, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(area, mask)
        canvas.restoreToCount(layer)
    }
}

/**
 * A band past one side of a shape, where its photo fades over the
 * neighbour: [corners] the band itself, [box] the part of the box round it
 * within the room the cells take, the fade running from [from] on the side
 * to [to] at the band's far edge.
 */
internal data class ShapeBand(val corners: Shape, val box: Box, val from: Pt, val to: Pt)

/**
 * The bands [blend] pixels wide past each side of [shape], as far as they
 * lie within [extent]: none on a side along the collage's edge, where there
 * is no neighbour, as for a box's.
 */
internal fun shapeBands(shape: Shape, blend: Float, extent: Box): List<ShapeBand> = shape.indices.mapNotNull { i ->
    val a = shape[i]
    val b = shape[(i + 1) % shape.size]
    val out = inward(a, b) * -blend
    val corners = listOf(a, b, b + out, a + out)
    val all = boundsOf(corners)
    val box = Box(max(all.left, extent.left), max(all.top, extent.top), min(all.right, extent.right), min(all.bottom, extent.bottom))
    if (box.width <= 0f || box.height <= 0f) null else ShapeBand(corners, box, a, a + out)
}

/**
 * Cell [index]'s shape as it is drawn: with no gap, half a pixel larger all
 * round, so where two smoothed slanting sides meet each covers the other
 * rather than letting the background through between them.
 */
private fun drawnShape(layout: CollageLayout, index: Int): Shape =
    if (layout.gap < 1f) insetShape(layout.shapes[index], -SEAM_OVERLAP) else layout.shapes[index]

/** How far, in pixels, a shape reaches past its outline with no gap; see [drawnShape]. */
private const val SEAM_OVERLAP = 0.5f

/** [shape] as a path into [out], its corners rounded by [radius] as [roundedCorners] does it. */
fun shapePath(shape: Shape, radius: Float, out: Path = Path()): Path {
    out.reset()
    if (shape.size < 3) return out
    roundedCorners(shape, radius).forEachIndexed { i, c ->
        if (i == 0) out.moveTo(c.start.x, c.start.y) else out.lineTo(c.start.x, c.start.y)
        if (c.radius > 0f) {
            val oval = RectF(c.center.x - c.radius, c.center.y - c.radius, c.center.x + c.radius, c.center.y + c.radius)
            out.arcTo(oval, c.startAngle, c.sweep, false)
        }
    }
    out.close()
    return out
}

/** A photo's alpha at its cell's edge in a blend band: half, so two facing bands meet even. */
private const val BLEND_EDGE_ALPHA = 0x80000000.toInt()

/**
 * A band past one edge of a cell, where its photo fades over the neighbour:
 * [box] on the canvas, the fade running from ([fromX], [fromY]) at the cell's
 * edge to ([toX], [toY]) at the band's far side.
 */
internal data class BlendBand(val box: Box, val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

/**
 * The bands [blend] pixels wide past each edge of [cell], as far as they lie
 * within [extent], the room the cells take together: none on a side that
 * faces the collage's edge, where there is no neighbour — with a gap, a band
 * there would lie in the margin round the cells.
 */
internal fun blendBands(cell: Box, blend: Float, extent: Box): List<BlendBand> = listOf(
    BlendBand(Box(cell.left - blend, cell.top, cell.left, cell.bottom), cell.left, 0f, cell.left - blend, 0f),
    BlendBand(Box(cell.right, cell.top, cell.right + blend, cell.bottom), cell.right, 0f, cell.right + blend, 0f),
    BlendBand(Box(cell.left, cell.top - blend, cell.right, cell.top), 0f, cell.top, 0f, cell.top - blend),
    BlendBand(Box(cell.left, cell.bottom, cell.right, cell.bottom + blend), 0f, cell.bottom, 0f, cell.bottom + blend),
).mapNotNull { band ->
    val b = band.box
    val clipped = Box(max(b.left, extent.left), max(b.top, extent.top), min(b.right, extent.right), min(b.bottom, extent.bottom))
    if (clipped.width <= 0f || clipped.height <= 0f) null else band.copy(box = clipped)
}

/**
 * Sets [out] to put [bitmap] where [photo]'s framing in [box] says — kept
 * covering it, see [clamped]: turned by its quarters, scaled to its shown
 * size, its centre point at the box's middle.
 */
private fun photoMatrix(photo: CellPhoto, bitmap: Bitmap, box: Box, out: Matrix) {
    val cell = photo.clamped(box.width, box.height)
    val (shownW, shownH) = cell.shownSize(box.width, box.height)
    out.setRotate(cell.quarter * 90f)
    val turned = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    out.mapRect(turned)
    out.postTranslate(-turned.left, -turned.top)
    out.postScale(shownW / turned.width(), shownH / turned.height())
    out.postTranslate(box.centerX - cell.centerX * shownW, box.centerY - cell.centerY * shownH)
    // The tilt, round the cell's middle, where the framing put the photo's centre point.
    if (cell.angle != 0f) out.postRotate(cell.angle, box.centerX, box.centerY)
}
