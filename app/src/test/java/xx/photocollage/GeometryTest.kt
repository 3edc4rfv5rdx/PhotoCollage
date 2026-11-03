package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(50f, divider.from.x, eps)
        assertEquals(0.5f to 0.5f, divider.shiftedTo(50f, 0f))
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

    /** Four columns of a quarter each, no gap: cells 25 wide on a canvas of 100. */
    private fun fourColumns(): Collage {
        val strip = Split(Dir.ROW, 1f / 4, Leaf(), Split(Dir.ROW, 1f / 3, Leaf(), Split(Dir.ROW, 0.5f, Leaf(), Leaf())))
        return Collage(root = strip, gap = 0f, aspect = Aspect.SQUARE)
    }

    @Test
    fun theMiddleOfANarrowCellIsTheCellsWhateverTheReach() {
        val layout = layoutOf(fourColumns(), 100f, 100f)
        // 12.5 from either cut, well within a reach of 20, but in the cell's middle half.
        assertNull(layout.dividerAt(37.5f, 50f, 20f))
        assertEquals(1, layout.cellAt(37.5f, 50f))
        // By its edge the cut still takes the touch.
        assertEquals(Dir.ROW, layout.dividerAt(27f, 50f, 20f)?.dir)
    }

    @Test
    fun aCutThatWouldMakeACellTooSmallIsRefused() {
        // A sixteenth of the side is still a cell; a 32nd is under MIN_CELL.
        var collage = Collage(root = Leaf(), gap = 0f, aspect = Aspect.SQUARE)
        repeat(4) { collage = collage.withSplit(0, Dir.ROW) }
        assertEquals(1f / 16, collage.smallestCell(), eps)
        assertFalse(collage.canSplit(0, Dir.ROW))
        assertTrue(collage.canSplit(0, Dir.COLUMN))
    }

    @Test
    fun aDragMayNotSqueezeACellUnderTheSmallestButMayWidenOneAlreadyThere() {
        val half = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0f, aspect = Aspect.SQUARE)
        // With no gap, the 8 % MIN_RATIO lets a cut go to still leaves a cell big enough.
        assertTrue(half.cellsStayBigEnough(half.copy(root = half.root.withCut(emptyList(), 0.08f, 0.08f))))
        // A gap that leaves 2 % of a cell: no smaller, but wider is allowed.
        val squeezed = half.copy(root = half.root.withCut(emptyList(), 0.08f, 0.08f), gap = 0.06f)
        assertTrue(squeezed.smallestCell() < MIN_CELL)
        assertFalse(squeezed.cellsStayBigEnough(squeezed.copy(gap = 0.07f)))
        assertTrue(squeezed.cellsStayBigEnough(squeezed.copy(root = squeezed.root.withCut(emptyList(), 0.3f, 0.3f))))
    }

    @Test
    fun aPhotoIsFramedPastItsCellByTheBlend() {
        val collage = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0f, blend = 0.05f, aspect = Aspect.SQUARE)
        val layout = layoutOf(collage, 100f, 100f)
        assertEquals(5f, layout.blend, eps)
        assertEquals(Box(-5f, -5f, 55f, 105f), layout.frameOf(0))
        // No blend, no room past the cell.
        assertEquals(layout.cells[0], layoutOf(collage.copy(blend = 0f), 100f, 100f).frameOf(0))
    }

    @Test
    fun blendBandsLieOnlyWhereANeighbourCanBe() {
        val canvas = Box(0f, 0f, 100f, 100f)
        // The left of two halves: only its right edge has a neighbour.
        val bands = blendBands(Box(0f, 0f, 50f, 100f), 5f, canvas)
        assertEquals(listOf(Box(50f, 0f, 55f, 100f)), bands.map { it.box })
        // Fading from the cell's edge outwards.
        assertEquals(50f, bands[0].fromX, eps)
        assertEquals(55f, bands[0].toX, eps)
        // A cell in the middle has a band on every side.
        assertEquals(4, blendBands(Box(30f, 30f, 70f, 70f), 5f, canvas).size)
    }

    @Test
    fun withAGapNoBlendBandLiesInTheMarginRoundTheCells() {
        // 100 wide, gap 10: 10 | 35 | 10 | 35 | 10 — the left cell's left edge is at 10, not 0.
        val collage = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), gap = 0.1f, blend = 0.05f, aspect = Aspect.SQUARE)
        val layout = layoutOf(collage, 100f, 100f)
        assertEquals(Box(10f, 10f, 90f, 90f), layout.extent)
        // Only the side facing the other cell, across the gap.
        val bands = blendBands(layout.cells[0], layout.blend, layout.extent)
        assertEquals(listOf(Box(45f, 10f, 50f, 90f)), bands.map { it.box })
    }
}
