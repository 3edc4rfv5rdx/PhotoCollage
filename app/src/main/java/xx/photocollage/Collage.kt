package xx.photocollage

/**
 * A collage: a canvas of a given shape cut into cells by a tree of straight
 * cuts, a photo in each cell or none, and the look of the gaps between them.
 * Immutable, so a step back is the previous value and nothing else.
 */
data class Collage(
    val root: Node = Leaf(),
    val aspect: Aspect = Aspect.FOUR_FIVE,
    /** The long side across instead of up; meaningless for a square. */
    val landscape: Boolean = false,
    /** The gap round and between the cells, as a part of the canvas's shorter side. */
    val gap: Float = DEFAULT_GAP,
    /** The rounding of each cell's corners, as a part of the canvas's shorter side. */
    val corner: Float = 0f,
    /** The colour behind the cells, which is also the colour of the gaps, as ARGB. */
    val background: Int = DEFAULT_BACKGROUND,
) {
    /** Width over height of the canvas. */
    val ratio: Float
        get() = if (landscape) aspect.long / aspect.short else aspect.short / aspect.long

    val cellCount: Int get() = root.leafCount

    val leaves: List<Leaf> get() = root.leaves()

    val isEmpty: Boolean get() = leaves.all { it.photo == null }
}

/** The canvas shapes on offer, each by its short and long side. */
enum class Aspect(val short: Float, val long: Float) {
    SQUARE(1f, 1f),
    FOUR_FIVE(4f, 5f),
    THREE_FOUR(3f, 4f),
    TWO_THREE(2f, 3f),
    NINE_SIXTEEN(9f, 16f),
    A4(210f, 297f),
}

/** Which way a cut lays its two parts: [ROW] side by side, [COLUMN] one above the other. */
enum class Dir { ROW, COLUMN }

sealed interface Node {
    val leafCount: Int
}

/** A cell, with the photo in it or none. */
data class Leaf(val photo: CellPhoto? = null) : Node {
    override val leafCount: Int get() = 1
}

/**
 * A straight cut: [first] takes [ratio] of the space along [dir], [second]
 * the rest.
 */
data class Split(val dir: Dir, val ratio: Float, val first: Node, val second: Node) : Node {
    override val leafCount: Int = first.leafCount + second.leafCount
}

/**
 * A photo the app keeps a copy of: [file] is its name in the app's photo
 * folder, [width] and [height] its size upright — EXIF turn applied.
 */
data class Photo(val file: String, val width: Int, val height: Int)

/**
 * A photo as it sits in its cell. [zoom] is 1 when it just covers the cell,
 * more when closer; [centerX] and [centerY] are the point of the photo at
 * the cell's middle, as parts of its turned width and height; [quarter] is
 * how many quarter turns clockwise it is shown at.
 */
data class CellPhoto(
    val photo: Photo,
    val zoom: Float = 1f,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val quarter: Int = 0,
) {
    /** Width over height as shown, after [quarter]. */
    val shownRatio: Float
        get() = if (quarter % 2 == 0) photo.width.toFloat() / photo.height else photo.height.toFloat() / photo.width
}

const val DEFAULT_GAP = 0.015f
const val MAX_GAP = 0.06f
const val MAX_CORNER = 0.12f
const val MAX_ZOOM = 6f

/** The smallest part of a cut either side may be squeezed to. */
const val MIN_RATIO = 0.08f

/** How many cells a collage may be cut into. */
const val MAX_CELLS = 16

const val DEFAULT_BACKGROUND = 0xFFFFFFFF.toInt()

/** The background colours on offer, as ARGB: whites and greys first, then soft colours, then dark ones. */
val BACKGROUNDS: List<Int> = listOf(
    0xFFFFFFFF, 0xFFF3EEE4, 0xFFD9D9D9, 0xFF8A8A8A,
    0xFFF8D7DA, 0xFFFFE9A8, 0xFFD4EDDA, 0xFFCFE2F3,
    0xFF7A1F2B, 0xFF1F3A5F, 0xFF2E4A2E, 0xFF000000,
).map { it.toInt() }

// ---------- Tree ----------

/** The cells in reading order: a cut's first part before its second. */
fun Node.leaves(): List<Leaf> = buildList { collectLeaves(this@leaves, this) }

private fun collectLeaves(node: Node, into: MutableList<Leaf>) {
    when (node) {
        is Leaf -> into += node
        is Split -> {
            collectLeaves(node.first, into)
            collectLeaves(node.second, into)
        }
    }
}

/** This tree with the [index]th cell, in [leaves] order, replaced by what [change] makes of it. */
fun Node.mapLeaf(index: Int, change: (Leaf) -> Node): Node {
    require(index in 0 until leafCount) { "cell $index of $leafCount" }
    return when (this) {
        is Leaf -> change(this)
        is Split -> if (index < first.leafCount) {
            copy(first = first.mapLeaf(index, change))
        } else {
            copy(second = second.mapLeaf(index - first.leafCount, change))
        }
    }
}

/** This tree with the photos given out to its cells in order: [photos][i] into cell i, nothing past their end. */
fun Node.withPhotos(photos: List<CellPhoto?>): Node {
    var next = 0
    fun fill(node: Node): Node = when (node) {
        is Leaf -> Leaf(photos.getOrNull(next++))
        is Split -> node.copy(first = fill(node.first), second = fill(node.second))
    }
    return fill(this)
}

/** The [index]th cell cut in two along [dir]: its photo stays in the first half, the second is empty. */
fun Node.splitLeaf(index: Int, dir: Dir): Node =
    mapLeaf(index) { Split(dir, 0.5f, Leaf(it.photo?.let(::refit)), Leaf()) }

/**
 * This tree without its [index]th cell: the cut that held it is replaced by its
 * other part, which takes the room. Null when the cell is the only one.
 */
fun Node.removeLeaf(index: Int): Node? {
    require(index in 0 until leafCount) { "cell $index of $leafCount" }
    return when (this) {
        is Leaf -> null
        is Split -> if (index < first.leafCount) {
            val rest = first.removeLeaf(index)
            if (rest == null) second else copy(first = rest)
        } else {
            val rest = second.removeLeaf(index - first.leafCount)
            if (rest == null) first else copy(second = rest)
        }
    }
}

/**
 * The cut at [path] — firsts and seconds from the root, false for first —
 * moved to [ratio], kept off the edges by [MIN_RATIO]. A path that leads to
 * no cut leaves the tree as it is.
 */
fun Node.withRatio(path: List<Boolean>, ratio: Float): Node {
    if (this !is Split) return this
    if (path.isEmpty()) return copy(ratio = ratio.coerceIn(MIN_RATIO, 1f - MIN_RATIO))
    val rest = path.drop(1)
    return if (path[0]) copy(second = second.withRatio(rest, ratio)) else copy(first = first.withRatio(rest, ratio))
}

/** [cell] with its framing put back to the whole photo, as for a cell of another shape. */
fun refit(cell: CellPhoto): CellPhoto = cell.copy(zoom = 1f, centerX = 0.5f, centerY = 0.5f)

/** The collage with the photos of cells [a] and [b] traded, each framed afresh for its new cell. */
fun Collage.swapPhotos(a: Int, b: Int): Collage {
    if (a == b) return this
    val all = leaves
    val pa = all[a].photo?.let(::refit)
    val pb = all[b].photo?.let(::refit)
    return copy(root = root.mapLeaf(a) { Leaf(pb) }.mapLeaf(b) { Leaf(pa) })
}

/** The collage with [change] applied to the photo in cell [index]; an empty cell stays as it is. */
fun Collage.mapPhoto(index: Int, change: (CellPhoto) -> CellPhoto): Collage =
    copy(root = root.mapLeaf(index) { leaf -> Leaf(leaf.photo?.let(change)) })

/** The collage with [photo] put in cell [index], whatever was there. */
fun Collage.setPhoto(index: Int, photo: Photo?): Collage =
    copy(root = root.mapLeaf(index) { Leaf(photo?.let { CellPhoto(it) }) })

/**
 * The collage with [photos] put into the empty cells, starting at [from] and
 * going round in reading order. Returns it with how many photos found no cell.
 */
fun Collage.fillEmpty(from: Int, photos: List<Photo>): Pair<Collage, Int> {
    val count = cellCount
    val order = (0 until count).map { (from + it) % count }.filter { leaves[it].photo == null }
    var result = this
    order.zip(photos).forEach { (cell, photo) -> result = result.setPhoto(cell, photo) }
    return result to (photos.size - order.size).coerceAtLeast(0)
}

/** Every photo the collage shows, in cell order. */
fun Collage.photos(): List<Photo> = leaves.mapNotNull { it.photo?.photo }
