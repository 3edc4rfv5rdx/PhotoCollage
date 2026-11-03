package xx.photocollage

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * The collage being edited, as the text kept in [file] so the next start
 * opens it again. Written in [scope], the latest only if it changes faster
 * than it is written; one per process (see [sessionStore]), so it outlives an
 * editor and a step taken just before the editor closes is still written.
 */
class SessionStore(private val file: File, scope: CoroutineScope) {

    /** The latest text kept; null until the first. */
    private val pending = MutableStateFlow<String?>(null)

    init {
        scope.launch {
            pending.filterNotNull().collect { text -> runCatching { writeAtomically(file, text) } }
        }
    }

    /** [text] to be written, the newest of what is kept. */
    fun keep(text: String) {
        pending.value = text
    }

    /**
     * The collage left off at, as text: the latest this process kept if any —
     * the file may not have caught up with it yet — else the file's; null
     * when there is neither.
     */
    fun latest(): String? = pending.value ?: runCatching { file.readText() }.getOrNull()
}

private var processSession: SessionStore? = null

/** This process's [SessionStore], made at the first call. */
fun sessionStore(context: Context): SessionStore = synchronized(SessionStore::class) {
    processSession ?: SessionStore(sessionFile(context), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        .also { processSession = it }
}
