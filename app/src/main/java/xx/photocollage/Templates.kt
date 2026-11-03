package xx.photocollage

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How many photos a ready-made layout is offered for. */
val TEMPLATE_COUNTS = 1..7

/** The bigger cell's part in the layouts with one photo larger than the rest. */
private const val FEATURE_RATIO = 0.6f

/**
 * The ready-made layouts for [count] cells, the plainest first: the grid; all
 * in a line; from four one cell large beside the rest, and one across the
 * top and one across the bottom of a line of the rest (1-2-1, 1-3-1, …); for
 * an odd count from five, one tall cell between two stacks of the rest
 * (2-1-2, 3-1-3, …). No two that are one another turned or mirrored: the
 * Layout dialog turns and mirrors them itself, so rows of cells and columns
 * of them are offered once. A pinwheel — four round a middle one, each
 * overlapping the next — is no tree of straight cuts, so none is offered.
 */
fun templates(count: Int): List<Node> {
    val n = count.coerceIn(TEMPLATE_COUNTS)
    val out = mutableListOf(grid(n, Dir.COLUMN), grid(n, Dir.ROW))
    if (n >= 3) out += strip(n, Dir.ROW)
    if (n >= 4) {
        out += Split(Dir.ROW, FEATURE_RATIO, Leaf(), strip(n - 1, Dir.COLUMN))
        out += chain(listOf(Leaf(), strip(n - 2, Dir.ROW), Leaf()), Dir.COLUMN)
    }
    if (n >= 5 && n % 2 == 1) {
        val side = n / 2
        out += chain(listOf(strip(side, Dir.COLUMN), Leaf(), strip(side, Dir.COLUMN)), Dir.ROW)
    }
    return out.distinctBy(::lookUpToSymmetry)
}

/**
 * What [root] looks like whichever way it is turned or mirrored: the same for
 * a layout and every one of its eight turned and mirrored forms.
 */
private fun lookUpToSymmetry(root: Node): String {
    val turns = generateSequence(root) { it.turnedClockwise() }.take(4).toList()
    return (turns + turns.map { it.mirrored() }).minOf { cellsOf(it).toString() }
}

/**
 * [root]'s cells on a square of side 1000 with no gap, rounded and sorted:
 * what it looks like, whatever tree it is or order it reads its cells in.
 */
private fun cellsOf(root: Node): List<List<Int>> =
    layoutOf(Collage(root = root, gap = 0f), 1000f, 1000f).cells
        .map { listOf(it.left, it.top, it.right, it.bottom).map(Float::roundToInt) }
        .sortedWith(compareBy({ it[0] }, { it[1] }))

/**
 * [n] cells in lines as near to a square grid as they go, the longer lines
 * last: the lines stacked along [stack], the cells of each across it.
 */
private fun grid(n: Int, stack: Dir): Node {
    val lines = sqrt(n.toFloat()).roundToInt().coerceAtLeast(1)
    val sizes = List(lines) { i -> n / lines + if (i >= lines - n % lines) 1 else 0 }
    val across = if (stack == Dir.COLUMN) Dir.ROW else Dir.COLUMN
    return chain(sizes.map { strip(it, across) }, stack)
}

/** [n] empty cells of equal size in a line along [dir]. */
private fun strip(n: Int, dir: Dir): Node = chain(List(n) { Leaf() }, dir)

/** [parts] of equal size in a line along [dir]: each cut gives its first part an even share of what is left. */
private fun chain(parts: List<Node>, dir: Dir): Node =
    if (parts.size == 1) parts[0] else Split(dir, 1f / parts.size, parts[0], chain(parts.drop(1), dir))

/**
 * This layout turned a quarter clockwise: a cut side by side becomes one
 * above the other, its left part on top; a cut one above the other becomes
 * one side by side, its lower part now on the left.
 */
fun Node.turnedClockwise(): Node = when (this) {
    is Leaf -> this
    is Split -> if (dir == Dir.ROW) {
        Split(Dir.COLUMN, ratio, first.turnedClockwise(), second.turnedClockwise())
    } else {
        Split(Dir.ROW, 1f - ratio, second.turnedClockwise(), first.turnedClockwise())
    }
}

/** This layout mirrored left for right: a cut side by side swaps its parts; one above the other keeps them. */
fun Node.mirrored(): Node = when (this) {
    is Leaf -> this
    is Split -> if (dir == Dir.ROW) {
        Split(Dir.ROW, 1f - ratio, second.mirrored(), first.mirrored())
    } else {
        Split(Dir.COLUMN, ratio, first.mirrored(), second.mirrored())
    }
}
