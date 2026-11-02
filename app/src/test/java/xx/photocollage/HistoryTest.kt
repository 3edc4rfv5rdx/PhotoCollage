package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    @Test
    fun undoAndRedoWalkTheSteps() {
        val h = History(0)
        h.push(1)
        h.push(2)
        assertTrue(h.undo())
        assertEquals(1, h.current)
        assertTrue(h.redo())
        assertEquals(2, h.current)
        assertFalse(h.redo())
    }

    @Test
    fun aNewStepDropsTheStepsForward() {
        val h = History(0)
        h.push(1)
        h.undo()
        h.push(5)
        assertFalse(h.canRedo)
        h.undo()
        assertEquals(0, h.current)
    }

    @Test
    fun theSameValueIsNoStep() {
        val h = History(0)
        h.push(0)
        assertFalse(h.canUndo)
    }

    @Test
    fun aGestureIsOneStep() {
        val h = History(0)
        h.begin()
        h.live(1)
        h.live(2)
        h.live(3)
        h.end()
        assertEquals(3, h.current)
        h.undo()
        assertEquals(0, h.current)
        assertFalse(h.canUndo)
    }

    @Test
    fun aGestureThatComesBackIsNoStep() {
        val h = History(0)
        h.begin()
        h.live(4)
        h.live(0)
        h.end()
        assertFalse(h.canUndo)
    }

    @Test
    fun anUndoMidGestureEndsItFirst() {
        val h = History(0)
        h.begin()
        h.live(7)
        assertTrue(h.undo())
        assertEquals(0, h.current)
        assertTrue(h.redo())
        assertEquals(7, h.current)
    }

    @Test
    fun liveOutsideAGestureIsAWholeStep() {
        val h = History(0)
        h.live(1)
        assertTrue(h.canUndo)
    }

    @Test
    fun theOldestStepsGoFirst() {
        val h = History(0)
        repeat(HISTORY_LIMIT + 10) { h.push(it + 1) }
        var undone = 0
        while (h.undo()) undone++
        assertEquals(HISTORY_LIMIT, undone)
        assertEquals(10, h.current)
    }
}
