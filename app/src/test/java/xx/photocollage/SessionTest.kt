package xx.photocollage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file() = File(temp.root, "current.json")

    /** A scope that never runs anything: the file is never written. */
    private fun stopped() = CoroutineScope(Job()).apply { cancel() }

    @Test
    fun nothingKeptAndNoFileIsNothing() {
        assertNull(SessionStore(file(), stopped()).latest())
    }

    @Test
    fun nothingKeptReadsTheFile() {
        file().writeText("earlier")
        assertEquals("earlier", SessionStore(file(), stopped()).latest())
    }

    @Test
    fun whatIsKeptComesBackBeforeTheFileHasIt() {
        file().writeText("earlier")
        val store = SessionStore(file(), stopped())
        store.keep("now")
        assertEquals("now", store.latest())
        assertEquals("earlier", file().readText())
    }

    @Test
    fun whatIsKeptReachesTheFile() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = SessionStore(file(), scope)
            store.keep("first")
            store.keep("second")
            val deadline = System.currentTimeMillis() + 5_000
            while (runCatching { file().readText() }.getOrNull() != "second" && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("second", file().readText())
            assertFalse(File(temp.root, "current.json.tmp").exists())
        } finally {
            scope.cancel()
        }
    }
}
