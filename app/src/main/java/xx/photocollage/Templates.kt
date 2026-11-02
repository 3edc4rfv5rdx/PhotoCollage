package xx.photocollage

import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How many photos a ready-made layout is offered for. */
val TEMPLATE_COUNTS = 1..9

/** The bigger cell's part in the layouts with one photo larger than the rest. */
private const val FEATURE_RATIO = 0.6f

/**
 * The ready-made layouts for [count] cells, the plainest first: rows of
 * cells, columns of them, then one cell large beside or above the rest.
 * No two alike.
 */
fun templates(count: Int): List<Node> {
    val n = count.coerceIn(TEMPLATE_COUNTS)
    val out = mutableListOf(grid(n, Dir.COLUMN))
    // A square grid looks the same either way round; only its trees differ.
    val side = sqrt(n.toFloat()).roundToInt()
    if (side * side != n) out += grid(n, Dir.ROW)
    if (n >= 3) {
        out += Split(Dir.ROW, FEATURE_RATIO, Leaf(), strip(n - 1, Dir.COLUMN))
        out += Split(Dir.COLUMN, FEATURE_RATIO, Leaf(), strip(n - 1, Dir.ROW))
    }
    return out.distinct()
}

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
