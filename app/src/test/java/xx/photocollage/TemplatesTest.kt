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
    fun threeIsOneOverTwoAndThreeInALine() {
        // One beside two is one over two turned; three down is three side by side turned.
        val shapes = templates(3).map { root -> boxes(root).map { (l, t, r, b) -> r - l to b - t }.sortedBy { it.first } }
        assertEquals(listOf(listOf(50 to 50, 50 to 50, 100 to 50), listOf(33 to 100, 33 to 100, 33 to 100)), shapes)
    }

    @Test
    fun noLayoutIsAnotherTurnedOrMirrored() {
        TEMPLATE_COUNTS.forEach { n ->
            val options = templates(n)
            options.forEachIndexed { i, a ->
                val forms = generateSequence(a) { it.turnedClockwise() }.take(4).toList().let { it + it.map(Node::mirrored) }
                options.drop(i + 1).forEach { b ->
                    assertTrue("two alike for $n", forms.none { boxes(it).toSet() == boxes(b).toSet() })
                }
            }
        }
    }

    @Test
    fun howManyLayoutsEachCountOffers() {
        assertEquals(1, templates(1).size)
        // Rows and columns of two are one turned.
        assertEquals(1, templates(2).size)
        // The grid, a line, one large beside the rest, 1-2-1.
        assertEquals(4, templates(4).size)
        // And 2-1-2 for an odd count.
        assertEquals(5, templates(5).size)
    }

    @Test
    fun fiveHasOneThreeOneAndTwoOneTwo() {
        val looks = templates(5).map { boxes(it).toSet() }
        // A whole row on top and at the bottom, three between.
        assertTrue(
            setOf(listOf(0, 0, 100, 33), listOf(0, 33, 33, 67), listOf(33, 33, 67, 67), listOf(67, 33, 100, 67), listOf(0, 67, 100, 100))
                in looks,
        )
        // Two stacked, one tall, two stacked.
        assertTrue(
            setOf(listOf(0, 0, 33, 50), listOf(0, 50, 33, 100), listOf(33, 0, 67, 100), listOf(67, 0, 100, 50), listOf(67, 50, 100, 100))
                in looks,
        )
    }

    @Test
    fun aMirrorSwapsLeftForRightAndKeepsTopAndBottom() {
        val root = Split(Dir.ROW, 0.6f, Leaf(), Split(Dir.COLUMN, 0.5f, Leaf(), Leaf()))
        assertEquals(
            setOf(listOf(40, 0, 100, 100), listOf(0, 0, 40, 50), listOf(0, 50, 40, 100)),
            boxes(root.mirrored()).toSet(),
        )
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
        assertEquals(7, templates(40).first().leafCount)
        assertEquals(1, templates(0).first().leafCount)
    }

    private fun boxes(root: Node) =
        layoutOf(Collage(root = root, gap = 0f, aspect = Aspect.SQUARE), 100f, 100f).cells
            .map { listOf(it.left, it.top, it.right, it.bottom).map { v -> Math.round(v) } }

    @Test
    fun aTurnPutsTheLeftPartOnTopAndTheLowerOnTheLeft() {
        // One large at the left, two above each other at the right.
        val root = Split(Dir.ROW, 0.6f, Leaf(), Split(Dir.COLUMN, 0.5f, Leaf(), Leaf()))
        // Turned: the large one across the top, the lower of the two now at the bottom left.
        assertEquals(
            setOf(listOf(0, 0, 100, 60), listOf(0, 60, 50, 100), listOf(50, 60, 100, 100)),
            boxes(root.turnedClockwise()).toSet(),
        )
    }

    @Test
    fun fourTurnsComeBackRound() {
        templates(5).forEach { root ->
            var turned = root
            repeat(4) { turned = turned.turnedClockwise() }
            assertEquals(boxes(root), boxes(turned))
        }
    }
}
