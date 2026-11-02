package xx.photocollage

/** How many steps back are kept; the oldest go first. */
const val HISTORY_LIMIT = 100

/**
 * The value being edited with its steps back and forward. A gesture that
 * changes it many times a second goes in as one step: [begin] when it
 * starts, [live] as it goes, [end] when it is let go.
 */
class History<T>(initial: T) {
    var current: T = initial
        private set

    private val back = ArrayDeque<T>()
    private val forward = ArrayDeque<T>()

    /** The value a gesture started from, while one is under way. */
    private var gestureStart: T? = null

    val canUndo: Boolean get() = back.isNotEmpty()
    val canRedo: Boolean get() = forward.isNotEmpty()

    /** One whole step: [value] becomes current, the old one a step back. Nothing changes if it is the same. */
    fun push(value: T) {
        if (value == current) return
        record(current)
        current = value
    }

    fun begin() {
        if (gestureStart == null) gestureStart = current
    }

    /** The value mid-gesture: shown, but no step yet. Outside a gesture it is a whole step. */
    fun live(value: T) {
        if (gestureStart == null) push(value) else current = value
    }

    /** The gesture as one step, from where it began; none if it came back where it started. */
    fun end() {
        val start = gestureStart ?: return
        gestureStart = null
        if (start != current) record(start)
    }

    fun undo(): Boolean {
        end()
        val previous = back.removeLastOrNull() ?: return false
        forward.addLast(current)
        current = previous
        return true
    }

    fun redo(): Boolean {
        end()
        val next = forward.removeLastOrNull() ?: return false
        back.addLast(current)
        current = next
        return true
    }

    private fun record(old: T) {
        back.addLast(old)
        if (back.size > HISTORY_LIMIT) back.removeFirst()
        forward.clear()
    }
}
