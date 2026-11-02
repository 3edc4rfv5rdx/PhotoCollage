package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeometryTest {

    private val eps = 1e-3f

    @Test
    fun theGapIsTheSameRoundTheEdgeAndBetweenCells() {
        // 100 wide, gap 10: 10 | 35 | 10 | 35 | 10.
        val collage = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0.1f)
        val layout = layoutOf(collage, 100f, 200f)
        val (left, right) = layout.cells
        assertEquals(10f, left.left, eps)
        assertEquals(45f, left.right, eps)
        assertEquals(55f, right.left, eps)
        assertEquals(90f, right.right, eps)
        assertEquals(10f, left.top, eps)
        assertEquals(190f, left.bottom, eps)
    }

    @Test
    fun theDividerLiesInTheMiddleOfTheGap() {
        val collage = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0.1f)
        val divider = layoutOf(collage, 100f, 200f).dividers.single()
        assertEquals(50f, divider.at, eps)
        assertEquals(0.5f, divider.ratioAt(50f, 0f), eps)
        assertEquals(emptyList<Boolean>(), divider.path)
    }

    @Test
    fun dividerAtFindsTheNearestCutWithinReach() {
        val root = Split(Dir.COLUMN, 0.5f, Leaf(), Split(Dir.ROW, 0.5f, Leaf(), Leaf()))
        val layout = layoutOf(Collage(root = root, gap = 0f), 100f, 100f)
        assertEquals(Dir.COLUMN, layout.dividerAt(20f, 52f, 5f)?.dir)
        // The inner cut runs only through the lower half.
        assertEquals(listOf(true), layout.dividerAt(51f, 80f, 5f)?.path)
        assertNull(layout.dividerAt(51f, 20f, 5f))
        assertNull(layout.dividerAt(20f, 30f, 5f))
    }

    @Test
    fun aDropOnAGapGoesToTheNearestCell() {
        val collage = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0.1f)
        val layout = layoutOf(collage, 100f, 200f)
        assertNull(layout.cellAt(50f, 100f))
        assertEquals(1, layout.nearestCell(53f, 100f))
        assertEquals(0, layout.nearestCell(47f, 100f))
    }

    @Test
    fun cornersAreCappedAtHalfTheShorterSide() {
        // 20 px of rounding asked for on cells 10 px wide.
        val collage = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0f, corner = 1f)
        val layout = layoutOf(collage, 20f, 1000f)
        assertEquals(5f, layout.cornerOf(0), eps)
    }

    @Test
    fun fitCanvasKeepsTheRatio() {
        assertEquals(80f to 100f, fitCanvas(0.8f, 200f, 100f))
        assertEquals(100f to 125f, fitCanvas(0.8f, 100f, 400f))
    }

    @Test
    fun atZoomOneTheWidePhotoJustCoversASquareCell() {
        val cell = CellPhoto(Photo("a", 400, 200))
        assertEquals(200f to 100f, cell.shownSize(100f, 100f))
    }

    @Test
    fun aQuarterTurnSwapsTheShownSides() {
        val cell = CellPhoto(Photo("a", 400, 200), quarter = 1)
        assertEquals(0.5f, cell.shownRatio, eps)
        assertEquals(100f to 200f, cell.shownSize(100f, 100f))
    }

    @Test
    fun clampKeepsThePhotoOverTheWholeCell() {
        // Shown 200 wide in a 100 cell: the centre can go from 0.25 to 0.75.
        val cell = CellPhoto(Photo("a", 400, 200), centerX = 0f, centerY = 0.9f)
        val clamped = cell.clamped(100f, 100f)
        assertEquals(0.25f, clamped.centerX, eps)
        assertEquals(0.5f, clamped.centerY, eps)
    }

    @Test
    fun clampKeepsTheZoomInRange() {
        val cell = CellPhoto(Photo("a", 100, 100), zoom = 0.2f)
        assertEquals(1f, cell.clamped(10f, 10f).zoom, eps)
        assertEquals(MAX_ZOOM, cell.copy(zoom = 50f).clamped(10f, 10f).zoom, eps)
    }

    @Test
    fun aPanMovesThePhotoWithTheFinger() {
        // Shown 200 wide in a 100 cell; a finger 20 px to the right shows 0.1 more of the left.
        val moved = CellPhoto(Photo("a", 400, 200)).transformed(100f, 100f, 20f, 0f, 1f, 0f, 0f)
        assertEquals(0.4f, moved.centerX, eps)
    }

    @Test
    fun aZoomKeepsThePointUnderTheFingers() {
        val cell = CellPhoto(Photo("a", 100, 100))
        val pivotX = 30f
        val pivotY = -20f
        val zoomed = cell.transformed(100f, 100f, 0f, 0f, 2f, pivotX, pivotY)
        assertEquals(2f, zoomed.zoom, eps)
        val (w0, h0) = cell.shownSize(100f, 100f)
        val (w1, h1) = zoomed.shownSize(100f, 100f)
        assertEquals(cell.centerX + pivotX / w0, zoomed.centerX + pivotX / w1, eps)
        assertEquals(cell.centerY + pivotY / h0, zoomed.centerY + pivotY / h1, eps)
    }

    @Test
    fun aTurnStartsTheFramingAfresh() {
        val turned = CellPhoto(Photo("a", 1, 1), zoom = 3f, centerX = 0.1f, quarter = 3).turned()
        assertEquals(CellPhoto(Photo("a", 1, 1), quarter = 0), turned)
    }

    @Test
    fun aTiltedPhotoCoversTheTiltedCellsCorners() {
        // A square cell 100 wide turned 45 degrees spans 141 in the photo's frame.
        val cell = CellPhoto(Photo("a", 100, 100), angle = 45f)
        val (w, h) = cell.shownSize(100f, 100f)
        assertEquals(141.42f, w, 0.01f)
        assertEquals(141.42f, h, 0.01f)
        // At zoom 1 there is no room to move it either way.
        val clamped = cell.copy(centerX = 0.1f, centerY = 0.9f).clamped(100f, 100f)
        assertEquals(0.5f, clamped.centerX, eps)
        assertEquals(0.5f, clamped.centerY, eps)
    }

    @Test
    fun aTurnOfTheFingersKeepsThePointUnderThem() {
        val cell = CellPhoto(Photo("a", 100, 100), zoom = 4f)
        val px = 20f
        val py = 10f
        val turned = cell.transformed(100f, 100f, 0f, 0f, 1f, px, py, rotation = 30f)
        assertEquals(30f, turned.angle, eps)
        val (w0, h0) = cell.shownSize(100f, 100f)
        val (w1, h1) = turned.shownSize(100f, 100f)
        val rad = Math.toRadians(30.0)
        val qx = (px * Math.cos(rad) + py * Math.sin(rad)).toFloat()
        val qy = (-px * Math.sin(rad) + py * Math.cos(rad)).toFloat()
        assertEquals(cell.centerX + px / w0, turned.centerX + qx / w1, eps)
        assertEquals(cell.centerY + py / h0, turned.centerY + qy / h1, eps)
    }

    @Test
    fun anglesStayWithinHalfATurn() {
        assertEquals(-170f, normalizedAngle(190f), eps)
        assertEquals(180f, normalizedAngle(-180f), eps)
        assertEquals(10f, normalizedAngle(370f), eps)
    }

    @Test
    fun aQuarterTurnStraightensATilt() {
        assertEquals(0f, CellPhoto(Photo("a", 1, 1), angle = 17f).turned().angle, eps)
    }
}
