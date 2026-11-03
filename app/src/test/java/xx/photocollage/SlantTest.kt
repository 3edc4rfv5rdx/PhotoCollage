package xx.photocollage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlantTest {

    private val eps = 1e-3f

    /** A square of 100 cut from 70 along the top to 30 along the bottom, no gap. */
    private fun slanted(gap: Float = 0f) =
        Collage(root = Split(Dir.ROW, 0.7f, Leaf(), Leaf(), 0.3f), gap = gap, aspect = Aspect.SQUARE)

    @Test
    fun aSlantingCutPartsTheRoomAlongItsLine() {
        val layout = layoutOf(slanted(), 100f, 100f)
        assertEquals(0, layout.cellAt(10f, 50f))
        assertEquals(1, layout.cellAt(90f, 50f))
        // At y = 10 the cut is at x = 66, at y = 90 at x = 34.
        assertEquals(0, layout.cellAt(60f, 10f))
        assertEquals(1, layout.cellAt(70f, 10f))
        assertEquals(1, layout.cellAt(40f, 90f))
        assertEquals(0, layout.cellAt(30f, 90f))
        assertBox(Box(0f, 0f, 70f, 100f), layout.cells[0])
        assertBox(Box(30f, 0f, 100f, 100f), layout.cells[1])
        assertFalse(layout.isBoxAt(0))
    }

    @Test
    fun aStraightCutStillMakesBoxes() {
        val layout = layoutOf(Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf())), 100f, 125f)
        assertTrue(layout.isBoxAt(0))
        assertTrue(layout.isBoxAt(1))
    }

    @Test
    fun theGapRunsAlongASlantingCutToo() {
        val layout = layoutOf(slanted(gap = 0.1f), 100f, 100f)
        // The middle of the cut, and 4 px across it either way, lie in the gap.
        assertNull(layout.cellAt(50f, 50f))
        assertNull(layout.cellAt(46f, 50f))
        assertNull(layout.cellAt(54f, 50f))
        assertEquals(0, layout.cellAt(40f, 50f))
        assertEquals(1, layout.cellAt(60f, 50f))
        // And round the edge, as for a box.
        assertNull(layout.cellAt(3f, 50f))
    }

    @Test
    fun theCutIsFoundAlongItsSlant() {
        val layout = layoutOf(slanted(), 100f, 100f)
        val cut = layout.dividers.single()
        assertPt(Pt(70f, 0f), cut.from)
        assertPt(Pt(30f, 100f), cut.to)
        assertNotNull(layout.dividerAt(66f, 10f, 5f))
        assertNull(layout.dividerAt(50f, 10f, 5f))
    }

    @Test
    fun aShiftKeepsTheSlant() {
        val cut = layoutOf(slanted(), 100f, 100f).dividers.single()
        val (a, b) = cut.shiftedTo(60f, 50f)
        assertEquals(0.8f, a, eps)
        assertEquals(0.4f, b, eps)
        // Past the edge, it stops where one end reaches its corner.
        val (c, d) = cut.shiftedTo(200f, 50f)
        assertEquals(1f, c, eps)
        assertEquals(0.6f, d, eps)
    }

    @Test
    fun anEndDraggedTiltsTheCutRoundTheOther() {
        val cut = layoutOf(slanted(), 100f, 100f).dividers.single()
        // The top end dragged through (25, 50), the bottom end staying at 30.
        val (a, b) = cut.tiltedTo(true, 25f, 50f)!!
        assertEquals(0.2f, a, eps)
        assertEquals(0.3f, b, eps)
        // Below the end that stays, the cut cannot run through the finger.
        assertNull(cut.tiltedTo(true, 25f, 100f))
        // Right on its own end, nothing moves.
        val (c, d) = cut.tiltedTo(false, 30f, 100f)!!
        assertEquals(0.7f, c, eps)
        assertEquals(0.3f, d, eps)
    }

    @Test
    fun aCutMayRunCornerToCornerButItsMiddleKeepsOffTheEdges() {
        assertEquals(1f to 0f, cutRatios(1f, 0f))
        assertEquals(1f to 0f, cutRatios(2f, -1f))
        // Both ends near the left: moved right together, the slant kept, till the middle is far enough in.
        val (a, b) = cutRatios(0.1f, 0f)
        assertEquals(MIN_RATIO, (a + b) / 2, 1e-6f)
        assertEquals(0.1f, a - b, 1e-6f)
    }

    @Test
    fun turnsAndMirrorsCarryTheSlant() {
        val root = slanted().root
        assertEquals(root, generateSequence(root) { it.turnedClockwise() }.drop(4).first())
        assertEquals(root, root.mirrored().mirrored())
        // A quarter turn clockwise carries (x, y) to (100 - y, x).
        val turned = layoutOf(slanted().copy(root = root.turnedClockwise()), 100f, 100f)
        assertEquals(0, turned.cellAt(90f, 60f))
        assertEquals(1, turned.cellAt(10f, 60f))
        // A mirror carries (x, y) to (100 - x, y).
        val mirrored = layoutOf(slanted().copy(root = root.mirrored()), 100f, 100f)
        assertEquals(1, mirrored.cellAt(40f, 10f))
        assertEquals(0, mirrored.cellAt(30f, 10f))
    }

    @Test
    fun aSlantedCellWithAPhotoHasItOnBothSides() {
        val photo = CellPhoto(Photo("a.img", 40, 30), zoom = 2f)
        val collage = Collage(root = Leaf(photo), aspect = Aspect.SQUARE)
        val cut = collage.withSlant(0)
        val split = cut.root as Split
        assertFalse(split.straight)
        assertEquals(listOf(refit(photo), refit(photo)), cut.leaves.map { it.photo })
        assertTrue(collage.canSlant(0))
    }

    @Test
    fun aSlantRunsAcrossTheCellsLongerWay() {
        val tall = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()), aspect = Aspect.SQUARE)
        assertEquals(Dir.COLUMN, ((tall.withSlant(1).root as Split).second as Split).dir)
        val wide = Collage(root = Split(Dir.COLUMN, 0.5f, Leaf(), Leaf()), aspect = Aspect.SQUARE)
        assertEquals(Dir.ROW, ((wide.withSlant(0).root as Split).first as Split).dir)
    }

    @Test
    fun pathsLeadToCuts() {
        val root = Split(Dir.ROW, 0.5f, Leaf(), Split(Dir.COLUMN, 0.5f, Leaf(), Leaf()))
        assertEquals(listOf(true, true), root.pathOf(2))
        assertEquals(root.second, root.cutAt(listOf(true)))
        assertNull(root.cutAt(listOf(false)))
        assertEquals(root, root.cutAt(emptyList()))
    }

    @Test
    fun aSlantReadsBackAndAStraightCutWritesNoSecondEnd() {
        val collage = slanted()
        assertEquals(collage, collageFromJson(collage.toJson()) { true })
        val straight = Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf()))
        assertFalse(straight.toJson().contains("ratio2"))
        assertEquals(straight, collageFromJson(straight.toJson()) { true })
        // A hand-edited end past the box is held to it.
        val wild = JSONObject(collage.toJson()).apply { getJSONObject("root").put("ratio2", 5.0) }.toString()
        assertEquals(1f, (collageFromJson(wild) { true }!!.root as Split).ratio2, 1e-6f)
    }

    @Test
    fun aCellIsAsThickAsItIsAtItsThinnest() {
        // Corner to corner: two halves of the square, each a triangle reaching 0.7 from its long side.
        val halves = Collage(root = Split(Dir.ROW, 1f, Leaf(), Leaf(), 0f), gap = 0f, aspect = Aspect.SQUARE)
        assertEquals(0.7071f, halves.smallestCell(), eps)
        // A sliver along the top: 4 thick however long.
        assertEquals(4f, thickness(listOf(Pt(0f, 0f), Pt(100f, 0f), Pt(0f, 4f))), 0.01f)
    }

    @Test
    fun aSlantInASlantedCellMayNotLeaveASliver() {
        // The left half's triangle cut again close to its long side.
        val halves = Collage(root = Split(Dir.ROW, 1f, Leaf(), Leaf(), 0f), gap = 0f, aspect = Aspect.SQUARE)
        val sliver = halves.copy(root = halves.root.mapLeaf(0) { Split(Dir.ROW, 1f, Leaf(), Leaf(), 0.96f) })
        assertTrue(sliver.smallestCell() < MIN_CELL)
        assertFalse(halves.cellsStayBigEnough(sliver))
    }

    @Test
    fun aSquaresCornersRoundAsABoxDoes() {
        val corners = roundedCorners(Box(0f, 0f, 100f, 50f).corners, 10f)
        assertTrue(corners.all { abs(it.radius - 10f) < eps })
        assertPt(Pt(10f, 10f), corners[0].center)
        // Capped at half the shorter side.
        assertTrue(roundedCorners(Box(0f, 0f, 100f, 50f).corners, 40f).all { abs(it.radius - 25f) < eps })
    }

    @Test
    fun aSlantedCellsBandsLieOnlyWhereANeighbourCanBe() {
        val layout = layoutOf(slanted(), 100f, 100f)
        // The left part: only its slanting side faces another cell.
        val bands = shapeBands(layout.shapes[0], 5f, layout.extent)
        assertEquals(1, bands.size)
        assertTrue(bands[0].to.x > bands[0].from.x)
    }

    @Test
    fun aShapeInsetIsThinnerByTwiceIt() {
        val square = Box(0f, 0f, 100f, 100f).corners
        assertEquals(80f, thickness(insetShape(square, 10f)), eps)
        assertTrue(insetShape(square, 60f).isEmpty())
        assertEquals(120f, thickness(insetShape(square, -10f)), eps)
    }

    private fun abs(f: Float) = kotlin.math.abs(f)

    private fun assertPt(expected: Pt, actual: Pt) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    private fun assertBox(expected: Box, actual: Box) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }
}
