package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageTest {

    private val a = Photo("a.img", 400, 300)
    private val b = Photo("b.img", 300, 400)
    private val c = Photo("c.img", 100, 100)

    private fun threeCells() = Collage(root = Split(Dir.ROW, 0.5f, Leaf(CellPhoto(a)), Split(Dir.COLUMN, 0.5f, Leaf(CellPhoto(b)), Leaf())))

    @Test
    fun leavesAreInReadingOrder() {
        assertEquals(listOf(a, b), threeCells().photos())
        assertEquals(3, threeCells().cellCount)
    }

    @Test
    fun splitKeepsThePhotoInTheFirstHalf() {
        val root = threeCells().root.splitLeaf(0, Dir.COLUMN)
        assertEquals(4, root.leafCount)
        assertEquals(a, root.leaves()[0].photo?.photo)
        assertNull(root.leaves()[1].photo)
    }

    @Test
    fun removingACellGivesItsRoomToTheOtherPart() {
        val collage = threeCells()
        val root = collage.root.removeLeaf(1)!!
        assertEquals(2, root.leafCount)
        assertEquals(listOf(a), root.leaves().mapNotNull { it.photo?.photo })
        // The sibling took the cut's place: a single cut at the root now.
        assertTrue(root is Split && root.second is Leaf)
    }

    @Test
    fun removingTheFirstCellOfACut() {
        val root = threeCells().root.removeLeaf(0)!!
        assertEquals(Split(Dir.COLUMN, 0.5f, Leaf(CellPhoto(b)), Leaf()), root)
    }

    @Test
    fun theOnlyCellCannotBeRemoved() {
        assertNull(Leaf().removeLeaf(0))
    }

    @Test
    fun swapTradesPhotosAndRefitsThem() {
        val zoomed = threeCells().mapPhoto(0) { it.copy(zoom = 3f, centerX = 0.2f) }
        val swapped = zoomed.swapPhotos(0, 1)
        assertEquals(CellPhoto(b), swapped.leaves[0].photo)
        assertEquals(CellPhoto(a), swapped.leaves[1].photo)
    }

    @Test
    fun swapWithAnEmptyCellMovesThePhoto() {
        val moved = threeCells().swapPhotos(0, 2)
        assertNull(moved.leaves[0].photo)
        assertEquals(a, moved.leaves[2].photo?.photo)
    }

    @Test
    fun fillEmptyStartsAtTheCellAndGoesRound() {
        val collage = Collage(root = templates(4).first())
        val (filled, left) = collage.setPhoto(1, a).fillEmpty(2, listOf(b, c))
        assertEquals(listOf(null, a, b, c), filled.leaves.map { it.photo?.photo })
        assertEquals(0, left)
    }

    @Test
    fun fillEmptyCountsWhatDidNotFit() {
        val (filled, left) = Collage(root = templates(2).first()).fillEmpty(1, listOf(a, b, c))
        assertEquals(listOf(b, a), filled.leaves.map { it.photo?.photo })
        assertEquals(1, left)
    }

    @Test
    fun withPhotosLeavesExtraCellsEmpty() {
        val root = templates(3).first().withPhotos(listOf(CellPhoto(a)))
        assertEquals(listOf(a, null, null), root.leaves().map { it.photo?.photo })
    }

    @Test
    fun ratioIsKeptOffTheEdges() {
        val root = threeCells().root.withRatio(listOf(true), 0.99f) as Split
        assertEquals(1f - MIN_RATIO, (root.second as Split).ratio, 1e-6f)
        val left = threeCells().root.withRatio(emptyList(), -1f) as Split
        assertEquals(MIN_RATIO, left.ratio, 1e-6f)
    }

    @Test
    fun aPathToNoCutChangesNothing() {
        val collage = threeCells()
        assertEquals(collage.root, collage.root.withRatio(listOf(false, true), 0.3f))
    }

    @Test
    fun ratioFollowsTheOrientation() {
        assertEquals(0.8f, Collage(aspect = Aspect.FOUR_FIVE).ratio, 1e-6f)
        assertEquals(1.25f, Collage(aspect = Aspect.FOUR_FIVE, landscape = true).ratio, 1e-6f)
        assertEquals(1f, Collage(aspect = Aspect.SQUARE, landscape = true).ratio, 1e-6f)
    }

    @Test
    fun emptyMeansNoPhotoAnywhere() {
        assertTrue(Collage(root = templates(4).first()).isEmpty)
        assertFalse(threeCells().isEmpty)
    }
}
