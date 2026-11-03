package xx.photocollage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The long side the editor decodes its photos at: sharp on any phone screen, small enough for nine at once. */
private const val PREVIEW_LONG = 1440

/** How long a preview that would not decode is left before a later change tries it again. */
private const val PREVIEW_RETRY_MS = 10_000L

/** What the editor shows besides the collage itself. */
data class EditorState(
    val collage: Collage = Collage(),
    /** The cell the cell tools act on, or null. */
    val selected: Int? = null,
    /** While a swap waits for its second cell: the first. */
    val swapFrom: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Photos being copied in or a collage being drawn: the editor waits. */
    val busy: Boolean = false,
    val hasDraft: Boolean = false,
    /** What the draft file holds as far as this editor knows; null when there is none or it cannot be read. */
    val draft: Collage? = null,
    /** Photos shared from another app while the collage holds work: they wait for the user to say whether to start anew. */
    val pendingShare: List<Uri>? = null,
)

/** A line for the user, from a string resource and its arguments. */
data class Notice(@param:StringRes val text: Int, val args: List<Any> = emptyList())

/** Something the screen has to do outside the composition: say a line, or hand a file to Share. */
sealed interface EditorEvent {
    data class Say(val notice: Notice) : EditorEvent
    data class Share(val file: File) : EditorEvent
}

class CollageViewModel(app: Application) : AndroidViewModel(app) {

    private val history = History(Collage())

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    /** The imports and renders under way, counted on the main thread: the editor waits while any is, not only the one that ends first. */
    private val jobs = JobCount()

    /** The editor's decoded photos by file name, as far as they are loaded. */
    val previews = mutableStateMapOf<String, Bitmap>()
    private val loading = mutableSetOf<String>()

    /**
     * Photos whose preview would not decode, by when it last failed: tried
     * again at a later change once [PREVIEW_RETRY_MS] has passed, as memory
     * may have come free, but said only once.
     */
    private val failedAt = mutableMapOf<String, Long>()
    private val saidUnreadable = mutableSetOf<String>()

    private val context get() = getApplication<Application>()

    /** The collage left off at, opened at the start; its photos outlive the clean-up. */
    private val leftOff: Collage?

    init {
        // The latest this process has written if any, as the file may not have caught up
        // with it yet, else the file.
        val sessionText = pendingSession.value ?: runCatching { sessionFile(context).readText() }.getOrNull()
        leftOff = sessionText?.let(::parseCollage)
        leftOff?.let(history::reset)
        // Read here, not later: a Share at a cold start asks whether the collage is the
        // draft's straight after this, and an answer of "unknown" would be "no".
        _state.value = EditorState(
            collage = history.current,
            hasDraft = draftFile(context).exists(),
            draft = readCollage(draftFile(context)),
        )
        loadPreviews(history.current)
        startSessionWriter(sessionFile(context))
        viewModelScope.launch { withContext(Dispatchers.IO) { photoLock.withLock { cleanUpOnce() } } }
    }

    /**
     * The photo copies of an earlier run that neither the draft nor the
     * collage left off at names, deleted once per process. Called under
     * [photoLock] here and before every import, so whichever comes first
     * does it, and never after a copy of this run is made.
     */
    private fun cleanUpOnce() =
        forgetPhotosOnce(cleaned, photoDir(context)) { keptPhotos(readCollage(draftFile(context)), leftOff) }

    // ---------- Photos in ----------

    /**
     * Photos the user picked for cell [cell], or shared from another app
     * when [cell] is null. Into a collage with nothing in it yet they make a
     * new layout, one cell each; otherwise they go into the empty cells from
     * [cell] on.
     */
    fun addPhotos(uris: List<Uri>, cell: Int? = null) {
        if (uris.isEmpty()) return
        val current = history.current
        val fresh = current.isEmpty && current.cellCount == 1
        val room = if (fresh) TEMPLATE_COUNTS.last else current.leaves.count { it.photo == null }
        val taken = uris.take(room)
        importing(taken) { photos ->
            val now = history.current
            if (fresh && now.isEmpty) {
                val root = templates(photos.size).first().withPhotos(photos.map { CellPhoto(it) })
                push(now.copy(root = root))
                _state.value = _state.value.copy(selected = null)
                if (uris.size > room) say(Notice(R.string.photos_left_out, listOf(uris.size - room)))
            } else {
                val (filled, left) = now.fillEmpty(cell ?: 0, photos)
                push(filled)
                // The cell was selected to be filled; filled, it is let go.
                _state.value = _state.value.copy(selected = null)
                val out = left + uris.size - taken.size
                if (out > 0) say(Notice(R.string.photos_left_out, listOf(out)))
            }
        }
    }

    /** The photo the user picked to go into cell [cell] in place of what is there. */
    fun replacePhoto(cell: Int, uri: Uri) {
        importing(listOf(uri)) { photos ->
            if (cell < history.current.cellCount) push(history.current.setPhoto(cell, photos.first()))
        }
    }

    /**
     * Copies [uris] in, then hands the ones that could be read to [then] on
     * the main thread. Anything that goes wrong on the way counts them all as
     * unread and leaves the editor free to use again, never a crash.
     */
    private fun importing(uris: List<Uri>, then: (List<Photo>) -> Unit) {
        if (uris.isEmpty()) return
        startJob()
        viewModelScope.launch {
            val photos = try {
                withContext(Dispatchers.IO) {
                    photoLock.withLock {
                        cleanUpOnce()
                        uris.mapNotNull { importPhoto(context, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            } finally {
                endJob()
            }
            if (photos.size < uris.size) say(Notice(R.string.photos_unreadable, listOf(uris.size - photos.size)))
            if (photos.isNotEmpty()) then(photos)
        }
    }

    // ---------- Cells ----------

    /**
     * A tap on cell [index]: the second cell of a swap, or the cell selected —
     * a tap on the selected one lets it go. An empty cell is selected and
     * stays so, the picker it opens being left without a photo, so it can be
     * removed: true is returned for the screen to ask for one.
     */
    fun tapCell(index: Int): Boolean {
        val s = _state.value
        s.swapFrom?.let { from ->
            if (from != index) push(history.current.swapPhotos(from, index))
            _state.value = _state.value.copy(swapFrom = null, selected = null)
            return false
        }
        val leaf = history.current.leaves.getOrNull(index) ?: return false
        if (leaf.photo == null) {
            _state.value = s.copy(selected = index)
            return true
        }
        _state.value = s.copy(selected = if (s.selected == index) null else index)
        return false
    }

    fun deselect() {
        _state.value = _state.value.copy(selected = null, swapFrom = null)
    }

    /** The selected cell waits for a tap on a second, to trade photos with it. */
    fun startSwap() {
        val selected = _state.value.selected ?: return
        _state.value = _state.value.copy(swapFrom = selected)
    }

    fun cancelSwap() {
        _state.value = _state.value.copy(swapFrom = null)
    }

    /** A photo dragged from cell [from] and dropped on cell [to]. */
    fun dropPhoto(from: Int, to: Int) {
        push(history.current.swapPhotos(from, to))
        _state.value = _state.value.copy(selected = null, swapFrom = null)
    }

    fun splitSelected(dir: Dir) = onSelected { c, i -> if (c.canSplit(i, dir)) c.withSplit(i, dir) else c }

    fun removeSelected() {
        val i = _state.value.selected ?: return
        val root = history.current.root.removeLeaf(i) ?: return
        push(history.current.copy(root = root))
        _state.value = _state.value.copy(selected = null)
    }

    fun clearSelected() {
        val i = _state.value.selected ?: return
        push(history.current.setPhoto(i, null))
        _state.value = _state.value.copy(selected = null)
    }

    fun turnSelected() = onSelected { c, i -> c.mapPhoto(i) { it.turned() } }

    private inline fun onSelected(change: (Collage, Int) -> Collage) {
        val i = _state.value.selected ?: return
        push(change(history.current, i))
    }

    // ---------- The whole collage ----------

    /** A ready-made layout, the photos there are given out to its cells in order; any past its end are left out. */
    fun applyTemplate(root: Node) {
        val photos = history.current.photos().size
        push(history.current.withLayout(root))
        _state.value = _state.value.copy(selected = null, swapFrom = null)
        if (photos > root.leafCount) say(Notice(R.string.photos_left_out, listOf(photos - root.leafCount)))
    }

    fun setAspect(aspect: Aspect) = push(history.current.withAspect(aspect))

    fun setLongSide(longSide: Int) {
        val c = history.current
        if (longSide in c.aspect.sizes) push(c.copy(longSide = longSide))
    }

    fun setPreset(preset: Preset) = push(history.current.withPreset(preset))

    /** The way round, shape and size [from] had, as one step; nothing if they are the same now. */
    fun restoreFormat(from: Collage) = push(
        history.current.copy(aspect = from.aspect, landscape = from.landscape, longSide = from.longSide),
    )

    fun setLandscape(landscape: Boolean) = push(history.current.copy(landscape = landscape))

    fun setBackground(color: Int) = push(history.current.copy(background = color))

    /** The gap mid-drag of its slider; [endGesture] makes the drag one step. It stops short of squeezing a cell too small. */
    fun setGap(gap: Float) {
        history.begin()
        val now = history.current
        val next = now.copy(gap = gap.coerceIn(0f, MAX_GAP))
        if (!now.cellsStayBigEnough(next)) return
        history.live(next)
        publish()
    }

    fun setCorner(corner: Float) {
        history.begin()
        history.live(history.current.copy(corner = corner.coerceIn(0f, MAX_CORNER)))
        publish()
    }

    /** How far photos fade over each other, mid-drag of its slider; [endGesture] makes the drag one step. */
    fun setBlend(blend: Float) {
        history.begin()
        history.live(history.current.copy(blend = blend.coerceIn(0f, MAX_BLEND)))
        publish()
    }

    /** An empty collage of the same shape and look; a step back brings the old one back. */
    private fun newCollage() {
        push(history.current.copy(root = Leaf()))
        _state.value = _state.value.copy(selected = null, swapFrom = null)
    }

    /** Whether starting a new collage would lose work, so the user is asked first. */
    fun newCollageLosesWork(): Boolean = history.current.hasWorkBeyond(_state.value.draft)

    /**
     * Photos shared from another app: a new collage of them, at once if the
     * one here holds no work, else once the user says so (see [startNew]).
     */
    fun receiveShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (newCollageLosesWork()) {
            _state.value = _state.value.copy(pendingShare = uris)
        } else {
            newCollage()
            addPhotos(uris)
        }
    }

    /** The shared photos waiting on the question are not taken: the collage stays as it is. */
    fun dropPendingShare() {
        _state.value = _state.value.copy(pendingShare = null)
    }

    /**
     * A new collage in place of this one, after saving this one as the
     * draft when [saveFirst] — and not at all if that fails, the shared
     * photos then still waiting on the question. It is made of those, or left
     * empty, its Choose photos button in the middle.
     */
    fun startNew(saveFirst: Boolean) {
        val shared = _state.value.pendingShare
        _state.value = _state.value.copy(pendingShare = null)
        viewModelScope.launch {
            if (saveFirst && !writeDraft(history.current)) {
                if (shared != null) _state.value = _state.value.copy(pendingShare = shared)
                return@launch
            }
            newCollage()
            if (shared != null) addPhotos(shared)
        }
    }

    // ---------- Gestures ----------

    fun beginGesture() = history.begin()

    /** A cut dragged to [ratio]; it stops short of making a cell too small to touch. */
    fun moveDivider(path: List<Boolean>, ratio: Float) {
        val now = history.current
        val next = now.copy(root = now.root.withRatio(path, ratio))
        if (!now.cellsStayBigEnough(next)) return
        history.live(next)
        publish()
    }

    /** Fingers moving the photo in cell [index], a cell [cellWidth] by [cellHeight] on screen; see [transformed]. */
    fun transformPhoto(
        index: Int,
        cellWidth: Float,
        cellHeight: Float,
        panX: Float,
        panY: Float,
        scale: Float,
        pivotX: Float,
        pivotY: Float,
        rotation: Float,
    ) {
        if (index >= history.current.cellCount) return
        history.live(
            history.current.mapPhoto(index) {
                it.transformed(cellWidth, cellHeight, panX, panY, scale, pivotX, pivotY, rotation)
            },
        )
        publish()
    }

    fun endGesture() {
        history.end()
        publish()
    }

    fun undo() {
        if (history.undo()) afterJump()
    }

    fun redo() {
        if (history.redo()) afterJump()
    }

    /** After a jump through history no cell is where it was: the selection goes. */
    private fun afterJump() {
        _state.value = _state.value.copy(selected = null, swapFrom = null)
        publish()
    }

    private fun push(collage: Collage) {
        history.push(collage)
        val count = collage.cellCount
        val s = _state.value
        _state.value = s.copy(
            selected = s.selected?.takeIf { it < count },
            swapFrom = s.swapFrom?.takeIf { it < count },
        )
        publish()
    }

    /**
     * The history's current value out to the screen, and its photos decoded;
     * kept to open again at the next start unless it is a value mid-gesture.
     */
    private fun publish() {
        val collage = history.current
        _state.value = _state.value.copy(collage = collage, canUndo = history.canUndo, canRedo = history.canRedo)
        loadPreviews(collage)
        if (!history.inGesture) pendingSession.value = collage.toJson()
    }

    private fun loadPreviews(collage: Collage) {
        val shown = collage.photos()
        val names = shown.mapTo(HashSet()) { it.file }
        // Let go of what the collage no longer shows; a step back decodes it again.
        previews.keys.retainAll(names)
        val now = SystemClock.elapsedRealtime()
        shown.filter {
            it.file !in previews && it.file !in loading && retryDue(failedAt[it.file], now, PREVIEW_RETRY_MS)
        }.forEach { photo ->
            loading += photo.file
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    decodeShrinking(PREVIEW_LONG) { decodePhoto(photoFile(context, photo), it) }
                }
                loading -= photo.file
                if (bitmap == null) {
                    failedAt[photo.file] = SystemClock.elapsedRealtime()
                    if (saidUnreadable.add(photo.file)) say(Notice(R.string.photo_unreadable))
                } else {
                    failedAt -= photo.file
                    if (history.current.photos().any { it.file == photo.file }) previews[photo.file] = bitmap
                }
            }
        }
    }

    // ---------- Draft ----------

    fun saveDraft() {
        val collage = history.current
        viewModelScope.launch { writeDraft(collage) }
    }

    /** [collage] as the draft, said either way; whether it was written. */
    private suspend fun writeDraft(collage: Collage): Boolean {
        val text = collage.toJson()
        val ok = withContext(Dispatchers.IO) { runCatching { writeAtomically(draftFile(context), text) }.isSuccess }
        if (ok) _state.value = _state.value.copy(hasDraft = true, draft = collage)
        say(Notice(if (ok) R.string.draft_saved else R.string.draft_save_failed))
        return ok
    }

    /** The saved draft in place of the collage; a step back brings the collage back. */
    fun openDraft() {
        viewModelScope.launch {
            val collage = withContext(Dispatchers.IO) { readCollage(draftFile(context)) }
            if (collage == null) {
                say(Notice(R.string.draft_open_failed))
            } else {
                _state.value = _state.value.copy(draft = collage)
                push(collage)
                _state.value = _state.value.copy(selected = null, swapFrom = null)
            }
        }
    }

    /** The collage [file] holds — the draft or the one left off at — or null when there is none to read. */
    private fun readCollage(file: File): Collage? =
        runCatching { file.readText() }.getOrNull()?.let(::parseCollage)

    /** The collage [text] holds, its photos whose copies are gone left out. */
    private fun parseCollage(text: String): Collage? {
        val dir = photoDir(context)
        return collageFromJson(text) { File(dir, it).isFile }
    }

    // ---------- Out ----------

    /** The collage into the folder Settings names, under [name] as typed; see [jpegFileName]. */
    fun saveImage(name: String) = rendering { bitmap ->
        val folder = AppSettings.saveFolder.value
        when (val saved = saveImage(context, bitmap, folder, jpegFileName(name))) {
            is SaveResult.Saved -> say(Notice(R.string.image_saved, listOf("${folderLabel(folder)}/${saved.name}")))
            // Picked again in Settings, it takes files again: said apart from a failure the user cannot mend.
            SaveResult.FolderUnavailable -> say(Notice(R.string.save_folder_unavailable, listOf(folderLabel(folder))))
            SaveResult.Failed -> say(Notice(R.string.image_save_failed))
        }
    }

    /** The collage handed to whichever app the user picks. */
    fun shareImage() = rendering { bitmap ->
        val file = writeForSharing(context, bitmap)
        if (file == null) say(Notice(R.string.image_save_failed)) else _events.tryEmit(EditorEvent.Share(file))
    }

    /**
     * The collage drawn and handed to [then] off the main thread. Whatever
     * goes wrong on the way ends as a message and an editor free to use
     * again, never a crash.
     */
    private fun rendering(then: (Bitmap) -> Unit) {
        if (_state.value.busy) return
        val collage = history.current
        startJob()
        viewModelScope.launch {
            try {
                val rendered = withContext(Dispatchers.Default) {
                    photoLock.withLock { renderCollage(context, collage) }
                }
                when (rendered) {
                    is Rendered.Done -> try {
                        withContext(Dispatchers.IO) { then(rendered.bitmap) }
                    } finally {
                        rendered.bitmap.recycle()
                    }
                    Rendered.TooLarge -> say(Notice(R.string.image_too_large))
                    is Rendered.Unreadable -> say(Notice(R.string.image_photos_unreadable, listOf(rendered.count)))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(Notice(R.string.image_save_failed))
            } catch (e: OutOfMemoryError) {
                say(Notice(R.string.image_too_large))
            } finally {
                endJob()
            }
        }
    }

    private fun startJob() {
        jobs.start()
        _state.value = _state.value.copy(busy = jobs.busy)
    }

    private fun endJob() {
        jobs.end()
        _state.value = _state.value.copy(busy = jobs.busy)
    }

    private fun say(notice: Notice) {
        _events.tryEmit(EditorEvent.Say(notice))
    }

    private companion object {
        /**
         * Held while photo copies are written, read for a saved image, or
         * cleaned up, so the clean-up can never take a copy just made.
         */
        val photoLock = Mutex()

        /** Whether this process has cleaned up the photo copies yet. */
        val cleaned = AtomicBoolean(false)

        /** The latest text of the collage being edited, for the session file; null until the first step. */
        val pendingSession = MutableStateFlow<String?>(null)

        /**
         * Writes [pendingSession] out, the latest only if it changes faster than
         * it is written. Outlives the view model, so a step taken just before the
         * editor closes is still written.
         */
        private val sessionWriter = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writerStarted = AtomicBoolean(false)

        fun startSessionWriter(file: File) {
            if (!writerStarted.compareAndSet(false, true)) return
            sessionWriter.launch {
                pendingSession.filterNotNull().collect { text -> runCatching { writeAtomically(file, text) } }
            }
        }
    }
}

/** How many pieces of work are under way, each [start] matched by an [end]: busy while any is. */
internal class JobCount {
    private var running = 0

    val busy: Boolean get() = running > 0

    fun start() {
        running++
    }

    fun end() {
        if (running > 0) running--
    }
}
