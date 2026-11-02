package xx.photocollage

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** The long side the editor decodes its photos at: sharp on any phone screen, small enough for nine at once. */
private const val PREVIEW_LONG = 1440

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

    /** The editor's decoded photos by file name, as far as they are loaded. */
    val previews = mutableStateMapOf<String, Bitmap>()
    private val loading = mutableSetOf<String>()

    /** Photos that would not decode: said once, not tried again on every change. */
    private val failed = mutableSetOf<String>()

    private val context get() = getApplication<Application>()

    init {
        _state.value = _state.value.copy(hasDraft = draftFile(context).exists())
        viewModelScope.launch(Dispatchers.IO) {
            photoLock.withLock {
                // Once per process: a second editor opened by Share runs beside the first.
                if (cleaned.compareAndSet(false, true)) forgetPhotosExcept(context, draftPhotos())
            }
        }
    }

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
                if (uris.size > room) say(Notice(R.string.photos_left_out, listOf(uris.size - room)))
            } else {
                val (filled, left) = now.fillEmpty(cell ?: 0, photos)
                push(filled)
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

    /** Copies [uris] in, then hands the ones that could be read to [then] on the main thread. */
    private fun importing(uris: List<Uri>, then: (List<Photo>) -> Unit) {
        if (uris.isEmpty()) return
        setBusy(true)
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) {
                photoLock.withLock { uris.mapNotNull { importPhoto(context, it) } }
            }
            setBusy(false)
            if (photos.size < uris.size) say(Notice(R.string.photos_unreadable, listOf(uris.size - photos.size)))
            if (photos.isNotEmpty()) then(photos)
        }
    }

    // ---------- Cells ----------

    /**
     * A tap on cell [index]: the second cell of a swap, or the cell selected —
     * a tap on the selected one lets it go. Returns true when the cell is
     * empty and nothing else took the tap, for the screen to ask for a photo.
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
            _state.value = s.copy(selected = null)
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

    fun splitSelected(dir: Dir) = onSelected { c, i ->
        if (c.cellCount < MAX_CELLS) c.copy(root = c.root.splitLeaf(i, dir)) else c
    }

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
        val photos = history.current.photos()
        push(history.current.copy(root = root.withPhotos(photos.map { CellPhoto(it) })))
        _state.value = _state.value.copy(selected = null, swapFrom = null)
        if (photos.size > root.leafCount) say(Notice(R.string.photos_left_out, listOf(photos.size - root.leafCount)))
    }

    fun setAspect(aspect: Aspect) = push(history.current.withAspect(aspect))

    fun setLongSide(longSide: Int) {
        val c = history.current
        if (longSide in c.aspect.sizes) push(c.copy(longSide = longSide))
    }

    fun setPreset(preset: Preset) = push(history.current.withPreset(preset))

    fun setLandscape(landscape: Boolean) = push(history.current.copy(landscape = landscape))

    fun setBackground(color: Int) = push(history.current.copy(background = color))

    /** The gap mid-drag of its slider; [endGesture] makes the drag one step. */
    fun setGap(gap: Float) {
        history.begin()
        history.live(history.current.copy(gap = gap.coerceIn(0f, MAX_GAP)))
        publish()
    }

    fun setCorner(corner: Float) {
        history.begin()
        history.live(history.current.copy(corner = corner.coerceIn(0f, MAX_CORNER)))
        publish()
    }

    /** An empty collage of the same shape and look; a step back brings the old one back. */
    fun newCollage() {
        push(history.current.copy(root = Leaf()))
        _state.value = _state.value.copy(selected = null, swapFrom = null)
    }

    // ---------- Gestures ----------

    fun beginGesture() = history.begin()

    fun moveDivider(path: List<Boolean>, ratio: Float) {
        history.live(history.current.copy(root = history.current.root.withRatio(path, ratio)))
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
    ) {
        if (index >= history.current.cellCount) return
        history.live(
            history.current.mapPhoto(index) {
                it.transformed(cellWidth, cellHeight, panX, panY, scale, pivotX, pivotY)
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

    /** The history's current value out to the screen, and its photos decoded. */
    private fun publish() {
        val collage = history.current
        _state.value = _state.value.copy(collage = collage, canUndo = history.canUndo, canRedo = history.canRedo)
        loadPreviews(collage)
    }

    private fun loadPreviews(collage: Collage) {
        val shown = collage.photos()
        val names = shown.mapTo(HashSet()) { it.file }
        // Let go of what the collage no longer shows; a step back decodes it again.
        previews.keys.retainAll(names)
        shown.filter { it.file !in previews && it.file !in loading && it.file !in failed }.forEach { photo ->
            loading += photo.file
            viewModelScope.launch {
                val bitmap = withContext(Dispatchers.IO) { decodePhoto(photoFile(context, photo), PREVIEW_LONG) }
                loading -= photo.file
                if (bitmap == null) {
                    failed += photo.file
                    say(Notice(R.string.photo_unreadable))
                } else if (history.current.photos().any { it.file == photo.file }) {
                    previews[photo.file] = bitmap
                }
            }
        }
    }

    // ---------- Draft ----------

    fun saveDraft() {
        val text = history.current.toJson()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { writeAtomically(draftFile(context), text) }.isSuccess }
            if (ok) _state.value = _state.value.copy(hasDraft = true)
            say(Notice(if (ok) R.string.draft_saved else R.string.draft_save_failed))
        }
    }

    /** The saved draft in place of the collage; a step back brings the collage back. */
    fun openDraft() {
        viewModelScope.launch {
            val collage = withContext(Dispatchers.IO) { readDraft() }
            if (collage == null) {
                say(Notice(R.string.draft_open_failed))
            } else {
                push(collage)
                _state.value = _state.value.copy(selected = null, swapFrom = null)
            }
        }
    }

    private fun readDraft(): Collage? {
        val file = draftFile(context)
        if (!file.exists()) return null
        val dir = photoDir(context)
        return runCatching { file.readText() }.getOrNull()?.let { text ->
            collageFromJson(text) { File(dir, it).isFile }
        }
    }

    /** The photo files the saved draft names, which a clean-up must keep. */
    private fun draftPhotos(): Set<String> = readDraft()?.photos()?.mapTo(HashSet()) { it.file }.orEmpty()

    // ---------- Out ----------

    /** The collage into the folder Settings names, at the size it asks for. */
    fun saveImage() = rendering { bitmap ->
        val folder = AppSettings.saveFolder.value
        if (saveImage(context, bitmap, folder)) {
            say(Notice(R.string.image_saved, listOf(folderLabel(folder))))
        } else {
            say(Notice(R.string.image_save_failed))
        }
    }

    /** The collage handed to whichever app the user picks. */
    fun shareImage() = rendering { bitmap ->
        val file = writeForSharing(context, bitmap)
        if (file == null) say(Notice(R.string.image_save_failed)) else _events.tryEmit(EditorEvent.Share(file))
    }

    private fun rendering(then: (Bitmap) -> Unit) {
        if (_state.value.busy) return
        val collage = history.current
        setBusy(true)
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                photoLock.withLock { renderCollage(context, collage) }
            }
            if (bitmap == null) {
                say(Notice(R.string.image_too_large))
            } else {
                withContext(Dispatchers.IO) { then(bitmap) }
                bitmap.recycle()
            }
            setBusy(false)
        }
    }

    private fun setBusy(busy: Boolean) {
        _state.value = _state.value.copy(busy = busy)
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
    }
}
