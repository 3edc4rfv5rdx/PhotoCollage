package xx.photocollage

import android.graphics.Bitmap
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
    if (layout.blend <= 0f) return
    val canvasBox = Box(0f, 0f, width, height)
    val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = layout.cells[index]
        val photo = leaf.photo ?: return@forEachIndexed
        if (cell.width <= 0f || cell.height <= 0f) return@forEachIndexed
        val bitmap = bitmapOf(photo.photo) ?: return@forEachIndexed
        photoMatrix(photo, bitmap, layout.frameOf(index), matrix)
        blendBands(cell, layout.blend, canvasBox).forEach { band ->
            val area = RectF(band.box.left, band.box.top, band.box.right, band.box.bottom)
            val layer = canvas.saveLayer(area, null)
            canvas.clipRect(area)
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
 * on [canvas]: none past the canvas's own edge, where there is no neighbour.
 */
internal fun blendBands(cell: Box, blend: Float, canvas: Box): List<BlendBand> = listOf(
    BlendBand(Box(cell.left - blend, cell.top, cell.left, cell.bottom), cell.left, 0f, cell.left - blend, 0f),
    BlendBand(Box(cell.right, cell.top, cell.right + blend, cell.bottom), cell.right, 0f, cell.right + blend, 0f),
    BlendBand(Box(cell.left, cell.top - blend, cell.right, cell.top), 0f, cell.top, 0f, cell.top - blend),
    BlendBand(Box(cell.left, cell.bottom, cell.right, cell.bottom + blend), 0f, cell.bottom, 0f, cell.bottom + blend),
).mapNotNull { band ->
    val b = band.box
    val clipped = Box(max(b.left, canvas.left), max(b.top, canvas.top), min(b.right, canvas.right), min(b.bottom, canvas.bottom))
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
