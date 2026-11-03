package xx.photocollage

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTest {

    @Test
    fun aFreshShareIsNotFromHistory() {
        assertFalse(launchedFromHistory(0))
        assertFalse(launchedFromHistory(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    @Test
    fun aRelaunchFromRecentsIsFromHistory() {
        assertTrue(launchedFromHistory(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY))
        assertTrue(launchedFromHistory(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
