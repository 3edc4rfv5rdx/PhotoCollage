package xx.photocollage

/** How many steps back are kept; the oldest go first. */
const val HISTORY_LIMIT = 100

/**
 * The value being edited with its steps back and forward. A gesture that
 * changes it many times a second goes in as one step: [begin] when it
 * starts, [live] as it goes, [end] when it is let go. Anything else that
 * ends the gesture first — an undo, a step pushed meanwhile, another gesture
 * let go — leaves the rest of it, if it goes on, as one step more, never a
 * step a frame.
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

    /** Whether a gesture is under way: [current] is then a value mid-gesture, not a step. */
    val inGesture: Boolean get() = gestureStart != null

    /** [value] as the start of editing: no steps back or forward, no gesture. */
    fun reset(value: T) {
        current = value
        back.clear()
        forward.clear()
        gestureStart = null
    }

    /**
     * One whole step: [value] becomes current, the old one a step back.
     * Nothing changes if it is the same. A gesture under way is made its own
     * step first, so the steps stay in the order they were taken.
     */
    fun push(value: T) {
        if (value == current) return
        end()
        record(current)
        current = value
    }

    fun begin() {
        if (gestureStart == null) gestureStart = current
    }

    /** The value mid-gesture: shown, but no step yet. Outside one it begins one, which [end] makes a step. */
    fun live(value: T) {
        begin()
        current = value
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
