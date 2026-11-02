package xx.photocollage

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How many photos a ready-made layout is offered for. */
val TEMPLATE_COUNTS = 1..9

/** The bigger cell's part in the layouts with one photo larger than the rest. */
private const val FEATURE_RATIO = 0.6f

/**
 * The ready-made layouts for [count] cells, the plainest first: rows of
 * cells, columns of them, then — for three — all three in a line either way,
 * and from four one cell large beside or above the rest. Three in a row with
 * one large would only be the grid again with its line moved a little. No two
 * that look alike: a square grid built either way round is offered once.
 */
fun templates(count: Int): List<Node> {
    val n = count.coerceIn(TEMPLATE_COUNTS)
    val out = mutableListOf(grid(n, Dir.COLUMN), grid(n, Dir.ROW))
    if (n == 3) {
        out += strip(n, Dir.ROW)
        out += strip(n, Dir.COLUMN)
    }
    if (n >= 4) {
        out += Split(Dir.ROW, FEATURE_RATIO, Leaf(), strip(n - 1, Dir.COLUMN))
        out += Split(Dir.COLUMN, FEATURE_RATIO, Leaf(), strip(n - 1, Dir.ROW))
    }
    return out.distinctBy(::cellsOf)
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
