package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplatesTest {

    @Test
    fun everyLayoutHasTheCellsAskedFor() {
        TEMPLATE_COUNTS.forEach { n ->
            val options = templates(n)
            assertTrue("no layout for $n", options.isNotEmpty())
            options.forEach { assertEquals("a layout for $n", n, it.leafCount) }
        }
    }

    @Test
    fun noTwoLayoutsAlike() {
        TEMPLATE_COUNTS.forEach { n -> assertEquals(templates(n).size, templates(n).distinct().size) }
    }

    @Test
    fun threeHasFourLayoutsThatLookDifferent() {
        // One over two, one beside two, three across, three down.
        val shapes = templates(3).map { root ->
            layoutOf(Collage(root = root, gap = 0f), 600f, 600f).cells.map { it.width to it.height }.sortedBy { it.first }
        }
        assertEquals(4, shapes.size)
        assertEquals(4, shapes.distinct().size)
    }

    @Test
    fun aSquareGridIsOfferedOnce() {
        // The grid, then one large cell beside the rest and above them.
        assertEquals(3, templates(4).size)
        assertEquals(1, templates(1).size)
    }

    @Test
    fun theCellsOfAGridAreEvenlySized() {
        val layout = layoutOf(Collage(root = templates(3).first(), gap = 0f), 120f, 120f)
        // [1, 2]: the top row whole, the bottom one in halves.
        assertEquals(listOf(120f, 60f, 60f), layout.cells.map { it.width })
        assertEquals(listOf(60f, 60f, 60f), layout.cells.map { it.height })
    }

    @Test
    fun aLineOfThreeIsInThirds() {
        // Five as [2, 3]: the second row in thirds.
        val layout = layoutOf(Collage(root = templates(5).first(), gap = 0f), 90f, 90f)
        layout.cells.drop(2).forEach { assertEquals(30f, it.width, 1e-3f) }
        layout.cells.take(2).forEach { assertEquals(45f, it.width, 1e-3f) }
    }

    @Test
    fun countsOutOfRangeAreHeldToIt() {
        assertEquals(9, templates(40).first().leafCount)
        assertEquals(1, templates(0).first().leafCount)
    }
}
