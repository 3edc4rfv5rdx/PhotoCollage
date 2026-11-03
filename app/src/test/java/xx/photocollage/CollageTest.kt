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
        val root = threeCells().root.withCut(listOf(true), 0.99f, 0.99f) as Split
        assertEquals(1f - MIN_RATIO, (root.second as Split).ratio, 1e-6f)
        val left = threeCells().root.withCut(emptyList(), -1f, -1f) as Split
        assertEquals(MIN_RATIO, left.ratio, 1e-6f)
    }

    @Test
    fun aPathToNoCutChangesNothing() {
        val collage = threeCells()
        assertEquals(collage.root, collage.root.withCut(listOf(false, true), 0.3f, 0.3f))
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

    @Test
    fun presetsComeOutAtTheirStandard() {
        fun size(p: Preset) = Collage().withPreset(p).savedSize()
        assertEquals(1920 to 1080, size(Preset.FULL_HD))
        assertEquals(3840 to 2160, size(Preset.UHD))
        assertEquals(1080 to 1920, size(Preset.STORIES))
        assertEquals(1080 to 1350, size(Preset.INSTAGRAM_PORTRAIT))
        assertEquals(1800 to 1200, size(Preset.PHOTO_10X15))
        assertEquals(2480 to 3508, size(Preset.A4_300))
    }

    @Test
    fun aPresetIsFoundFromWhatItSets() {
        Preset.entries.forEach { assertEquals(it, Collage().withPreset(it).preset()) }
        // Full HD turned upright is Stories; a square is the same either way round.
        assertEquals(Preset.STORIES, Collage().withPreset(Preset.FULL_HD).copy(landscape = false).preset())
        assertEquals(Preset.INSTAGRAM_SQUARE, Collage().withPreset(Preset.INSTAGRAM_SQUARE).copy(landscape = true).preset())
        assertNull(Collage(aspect = Aspect.NINE_SIXTEEN, longSide = 2560, landscape = false).preset())
    }

    @Test
    fun aCollageStartsAtItsShapesOwnSize() {
        assertEquals(Aspect.FOUR_FIVE.defaultLong, Collage().longSide)
        assertEquals(1920, Collage(aspect = Aspect.NINE_SIXTEEN).longSide)
    }

    @Test
    fun anotherShapeTakesItsOwnSize() {
        val collage = Collage(aspect = Aspect.NINE_SIXTEEN, longSide = 3840)
        assertEquals(Aspect.A4.defaultLong, collage.withAspect(Aspect.A4).longSide)
        // The same shape again keeps the size picked for it.
        assertEquals(3840, collage.withAspect(Aspect.NINE_SIXTEEN).longSide)
    }

    @Test
    fun workBeyondTheDraftIsAPhotoTheDraftDoesNotHold() {
        val work = threeCells()
        assertTrue(work.hasWorkBeyond(null))
        assertFalse(work.hasWorkBeyond(work))
        assertTrue(work.mapPhoto(0) { it.turned() }.hasWorkBeyond(work))
        // No photos, nothing to lose, draft or not.
        assertFalse(Collage(root = Split(Dir.ROW, 0.5f, Leaf(), Leaf())).hasWorkBeyond(null))
    }

    @Test
    fun aCollageReadBackFromItsTextIsNoWorkBeyondItself() {
        // The draft and the collage left off at are compared after a trip through their files.
        val tilted = threeCells().mapPhoto(0) { it.copy(zoom = 1.37f, centerX = 0.41f, angle = -12.3f) }
        val read = collageFromJson(tilted.toJson()) { true }
        assertFalse(tilted.hasWorkBeyond(read))
    }

    @Test
    fun keptPhotosAreEveryPhotoAnyCollageNames() {
        val draft = Collage(root = Leaf(CellPhoto(c)))
        assertEquals(setOf("a.img", "b.img", "c.img"), keptPhotos(draft, threeCells()))
        assertEquals(setOf("c.img"), keptPhotos(draft, null))
        assertEquals(emptySet<String>(), keptPhotos(null, null))
    }

    @Test
    fun aNewLayoutKeepsEachPhotosQuarterTurnAndFramesItAfresh() {
        val turned = threeCells().mapPhoto(0) { it.turned().copy(zoom = 2f, angle = 15f) }
        val laid = turned.withLayout(Split(Dir.COLUMN, 0.5f, Leaf(), Leaf()))
        val first = laid.leaves[0].photo!!
        assertEquals(a, first.photo)
        assertEquals(1, first.quarter)
        assertEquals(1f, first.zoom)
        assertEquals(0f, first.angle)
        assertEquals(b, laid.leaves[1].photo?.photo)
    }

    @Test
    fun theBackgroundSquareIsTwelveByTwelveDistinctOpaqueColours() {
        assertEquals(BACKGROUND_SHADES, BACKGROUND_SQUARE.size)
        BACKGROUND_SQUARE.forEach { assertEquals(BACKGROUND_HUES + 1, it.size) }
        val all = BACKGROUND_SQUARE.flatten()
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.all { it ushr 24 == 0xFF })
        // White at the top of the grey, black at its foot: the default is on offer.
        assertEquals(DEFAULT_BACKGROUND, BACKGROUND_SQUARE.first().last())
        assertEquals(0xFF000000.toInt(), BACKGROUND_SQUARE.last().last())
    }

    @Test
    fun hslGivesThePrimariesAndGrey() {
        assertEquals(0xFFFF0000.toInt(), hslToArgb(0f, 1f, 0.5f))
        assertEquals(0xFF00FF00.toInt(), hslToArgb(120f, 1f, 0.5f))
        assertEquals(0xFF0000FF.toInt(), hslToArgb(240f, 1f, 0.5f))
        assertEquals(0xFF808080.toInt(), hslToArgb(0f, 0f, 0.5f))
    }
}
