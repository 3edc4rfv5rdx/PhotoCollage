package xx.photocollage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobCountTest {

    @Test
    fun busyUntilTheLastJobEnds() {
        val jobs = JobCount()
        assertFalse(jobs.busy)
        jobs.start()
        jobs.start()
        jobs.end()
        assertTrue(jobs.busy)
        jobs.end()
        assertFalse(jobs.busy)
    }

    @Test
    fun anEndTooManyLeavesItIdleNotOwing() {
        val jobs = JobCount()
        jobs.end()
        jobs.start()
        assertTrue(jobs.busy)
        jobs.end()
        assertFalse(jobs.busy)
    }
}
