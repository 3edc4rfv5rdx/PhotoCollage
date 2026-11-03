package xx.photocollage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.withSave

/** What an empty cell is filled with, on screen and in a saved image alike. */
const val EMPTY_CELL_COLOR = 0xFFC8C8C8.toInt()

/**
 * Draws [collage] on [canvas], [width] by [height] pixels from its origin —
 * the one drawing both the editor and a saved image use, so what is saved is
 * what was seen. [bitmapOf] gives each photo as far as it is loaded; a cell
 * whose photo is not is drawn empty. [onDrawn] gets each bitmap once it is
 * on the canvas, for a caller that decoded it for this drawing alone to let
 * it go before the next. An empty cell is filled with [emptyColor].
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
    collage.leaves.forEachIndexed { index, leaf ->
        val cell = layout.cells[index]
        if (cell.width <= 0f || cell.height <= 0f) return@forEachIndexed
        val rect = RectF(cell.left, cell.top, cell.right, cell.bottom)
        val radius = layout.cornerOf(index)
        val photo = leaf.photo
        val bitmap = photo?.let { bitmapOf(it.photo) }
        if (photo == null || bitmap == null) {
            paint.color = emptyColor
            canvas.drawRoundRect(rect, radius, radius, paint)
            return@forEachIndexed
        }
        canvas.withSave {
            path.reset()
            path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            clipPath(path)
            photoMatrix(photo.clamped(cell.width, cell.height), bitmap, cell, matrix)
            drawBitmap(bitmap, matrix, paint)
        }
        onDrawn(bitmap)
    }
}

/**
 * Sets [out] to put [bitmap] where [cell]'s framing says: turned by its
 * quarters, scaled to its shown size, its centre point at the cell's middle.
 */
private fun photoMatrix(cell: CellPhoto, bitmap: Bitmap, box: Box, out: Matrix) {
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
