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

/** How long a preview that would not decode is left before a later change tries it again. */
private const val PREVIEW_RETRY_MS = 10_000L

/** What the editor shows besides the collage itself. */
data class EditorState(
    val collage: Collage = Collage(),
    /** The cell the cell tools act on, or null. */
    val selected: Int? = null,
    /** While a swap waits for its second cell: the first. */
    val swapFrom: Int? = null,
    /** The cut whose ends are shown, to tilt it by: its path in the tree, or null. Never with a cell selected. */
    val cut: List<Boolean>? = null,
    /** The overlay selected, to move, size and turn and for the cell tools to act on, or null. Never with a cell or a cut. */
    val overlay: Int? = null,
    /**
     * The overlays shown only as outlines, touches going through them to the
     * cells under them: the editor's alone, so a saved image still has them.
     */
    val overlaysHidden: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Photos being copied in or a collage being drawn: the editor waits. */
    val busy: Boolean = false,
    /** Every saved draft, the one written last first. */
    val drafts: List<DraftInfo> = emptyList(),
    /** The draft this collage is kept as — opened from or saved to — which Save draft writes over; null while it is none yet. */
    val draftId: String? = null,
    /** What that draft holds as far as this editor knows; null when there is none or it cannot be read. */
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

    private val session = sessionStore(app)

    /** Whether the app keeps a copy of the photo by this file name: a draft or session naming one it lost leaves its cell empty. */
    private val photoExists: (String) -> Boolean = photoDir(app).let { dir -> { name -> File(dir, name).isFile } }

    private val draftStore = DraftStore(draftDir(app), photoExists)

    /** The collage left off at, opened at the start; its photos outlive the clean-up. */
    private val leftOff: Collage?

    init {
        val adopted = draftStore.adopt(oldDraftFile(app))
        val text = session.latest()
        leftOff = text?.let { collageFromJson(it, photoExists) }
        leftOff?.let(history::reset)
        // The session names the draft its collage is kept as; one of the time before there
        // were several names none, and the old single draft is its draft only if it is the same.
        val named = text?.let(::sessionDraftId)
        val linked = named ?: adopted?.takeIf { leftOff != null && draftStore.read(it) == leftOff }
        // Read here, not later: a Share at a cold start asks whether the collage is its
        // draft's straight after this, and an answer of "unknown" would be "no".
        val draft = linked?.let(draftStore::read)
        _state.value = EditorState(
            collage = history.current,
            drafts = draftStore.list(),
            // A draft deleted, or never written, keeps nothing: Save draft makes a new one.
            draftId = linked.takeIf { draft != null },
            draft = draft,
        )
        if (_state.value.draftId != named) keepSession()
        loadPreviews(history.current)
        viewModelScope.launch { withContext(Dispatchers.IO) { photoLock.withLock { cleanUpOnce() } } }
    }

    /**
     * The photo copies of an earlier run that neither a draft nor the
     * collage left off at names, deleted once per process. Called under
     * [photoLock] here and before every import, so whichever comes first
     * does it, and never after a copy of this run is made.
     */
    private fun cleanUpOnce() =
        forgetPhotosOnce(cleaned, photoDir(context)) {
            keptPhotos(*draftStore.list().map { draftStore.read(it.id) }.toTypedArray(), leftOff)
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
        val fresh = current.cellsEmpty && current.cellCount == 1
        val room = if (fresh) TEMPLATE_COUNTS.last else current.leaves.count { it.photo == null }
        val taken = uris.take(room)
        importing(taken) { photos ->
            val now = history.current
            if (fresh && now.cellsEmpty) {
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
     * the main thread, saying how many were too large and how many could not
     * be read. Anything that goes wrong on the way counts them all as unread
     * and leaves the editor free to use again, never a crash.
     */
    private fun importing(uris: List<Uri>, then: (List<Photo>) -> Unit) {
        if (uris.isEmpty()) return
        startJob()
        viewModelScope.launch {
            val results = try {
                withContext(Dispatchers.IO) {
                    photoLock.withLock {
                        cleanUpOnce()
                        uris.map { importPhoto(context, it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            } finally {
                endJob()
            }
            val photos = results.filterIsInstance<Imported.Done>().map { it.photo }
            val tooLarge = results.count { it == Imported.TooLarge }
            val unreadable = uris.size - photos.size - tooLarge
            if (tooLarge > 0) say(Notice(R.string.photos_too_large, listOf(tooLarge)))
            if (unreadable > 0) say(Notice(R.string.photos_unreadable, listOf(unreadable)))
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
            _state.value = s.copy(selected = index, cut = null, overlay = null)
            return true
        }
        _state.value = s.copy(selected = if (s.selected == index) null else index, cut = null, overlay = null)
        return false
    }

    /** A tap on the cut at [path]: its ends shown, to tilt it by, or hidden again if they were; no cell stays selected. */
    fun tapCut(path: List<Boolean>) {
        if (history.current.root.cutAt(path) == null) return
        val s = _state.value
        _state.value = s.copy(cut = if (s.cut == path) null else path, selected = null, swapFrom = null, overlay = null)
    }

    fun deselect() {
        _state.value = _state.value.copy(selected = null, swapFrom = null, cut = null, overlay = null)
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

    /** The selected cell cut by a slanting cut (see [withSlant]), whose ends are then shown to tilt it by. */
    fun slantSelected() {
        val i = _state.value.selected ?: return
        val now = history.current
        if (!now.canSlant(i)) return
        push(now.withSlant(i))
        // The cell's way in the tree is the new cut's.
        _state.value = _state.value.copy(selected = null, cut = now.root.pathOf(i))
    }

    private inline fun onSelected(change: (Collage, Int) -> Collage) {
        val i = _state.value.selected ?: return
        push(change(history.current, i))
    }

    // ---------- The whole collage ----------

    /** A ready-made layout, the photos there are given out to its cells in order; any past its end are left out. */
    fun applyTemplate(root: Node) {
        // The cells' photos: the overlays keep theirs.
        val photos = history.current.leaves.count { it.photo != null }
        push(history.current.withLayout(root))
        // A cut's way in the old tree may lead to another cut in the new one.
        _state.value = _state.value.copy(selected = null, swapFrom = null, cut = null)
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

    /** An empty collage of the same shape and look, no overlays on it, kept as no draft yet. */
    private fun newCollage() = replaceCollage(history.current.copy(root = Leaf(), overlays = emptyList()), draftId = null)

    /**
     * [collage] in place of the one here, kept as draft [draftId] holding
     * [draft], with no step back: a step would bring back the collage of
     * another draft, or of none, which Save draft would then write over this
     * one's. The user is asked before work is lost instead (see
     * [collageLosesWork]).
     */
    private fun replaceCollage(collage: Collage, draftId: String?, draft: Collage? = null) {
        history.reset(collage)
        _state.value = _state.value.copy(
            selected = null,
            swapFrom = null,
            cut = null,
            overlay = null,
            draftId = draftId,
            draft = draft,
        )
        publish()
    }

    /** Whether another collage in place of this one would lose work, so the user is asked first. */
    fun collageLosesWork(): Boolean = history.current.hasWorkBeyond(_state.value.draft)

    /**
     * Photos shared from another app: a new collage of them, at once if the
     * one here holds no work, else once the user says so (see [startNew]).
     */
    fun receiveShared(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (collageLosesWork()) {
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
     * A new collage in place of this one, after saving this one as its
     * draft when [saveFirst] — and not at all if that fails, the shared
     * photos then still waiting on the question. It is made of those, or left
     * empty, its Choose photos button in the middle.
     */
    fun startNew(saveFirst: Boolean) {
        val shared = _state.value.pendingShare
        _state.value = _state.value.copy(pendingShare = null)
        viewModelScope.launch {
            if (saveFirst && !writeDraft(asNew = false)) {
                if (shared != null) _state.value = _state.value.copy(pendingShare = shared)
                return@launch
            }
            newCollage()
            if (shared != null) addPhotos(shared)
        }
    }

    // ---------- Overlays ----------

    /** A new empty overlay of [shape] in the middle, on top and selected; none past [MAX_OVERLAYS]. */
    fun addOverlay(shape: OverlayShape) {
        val now = history.current
        if (now.overlays.size >= MAX_OVERLAYS) return
        push(now.withOverlay(Overlay(shape)))
        // Shown again: a new one hidden at once would be no use.
        _state.value = _state.value.copy(
            overlay = now.overlays.size,
            selected = null,
            swapFrom = null,
            cut = null,
            overlaysHidden = false,
        )
    }

    /** The overlays hidden, to work on the cells under them, or shown again; a hidden one is no longer selected. */
    fun toggleOverlays() {
        val s = _state.value
        _state.value = s.copy(overlaysHidden = !s.overlaysHidden, overlay = if (s.overlaysHidden) s.overlay else null)
    }

    /**
     * A tap on overlay [index]: selected, or let go if it was. An empty one is
     * selected and stays so, and true is returned for the screen to ask for a
     * photo, as for an empty cell.
     */
    fun tapOverlay(index: Int): Boolean {
        val overlay = history.current.overlays.getOrNull(index) ?: return false
        val s = _state.value
        val empty = overlay.photo == null
        val next = if (empty || s.overlay != index) index else null
        _state.value = s.copy(overlay = next, selected = null, swapFrom = null, cut = null)
        return empty
    }

    /** Overlay [index] moved, mid-drag, so its middle is at ([x], [y]) as parts of the canvas; [endGesture] makes the drag one step. */
    fun moveOverlay(index: Int, x: Float, y: Float) = liveOverlay(index) { it.movedTo(x, y) }

    /** Overlay [index] sized and turned, mid-drag; see [sizedTo]. */
    fun sizeOverlay(index: Int, size: Float, angle: Float) = liveOverlay(index) { it.sizedTo(size, angle) }

    /** The photo in overlay [index] moved by fingers over it, in its own unturned frame; see [transformed]. */
    fun transformOverlayPhoto(
        index: Int,
        frameWidth: Float,
        frameHeight: Float,
        panX: Float,
        panY: Float,
        scale: Float,
        pivotX: Float,
        pivotY: Float,
        rotation: Float,
    ) = liveOverlay(index) { overlay ->
        overlay.copy(
            photo = overlay.photo?.transformed(frameWidth, frameHeight, panX, panY, scale, pivotX, pivotY, rotation),
        )
    }

    private fun liveOverlay(index: Int, change: (Overlay) -> Overlay) {
        if (index >= history.current.overlays.size) return
        history.live(history.current.mapOverlay(index, change))
        publish()
    }

    /** The photo the user picked for overlay [index], in place of what is there. */
    fun setOverlayPhoto(index: Int, uri: Uri) {
        importing(listOf(uri)) { photos ->
            push(history.current.mapOverlay(index) { it.copy(photo = CellPhoto(photos.first())) })
        }
    }

    /** The selected overlay as [shape], its photo framed afresh for it. */
    fun reshapeOverlay(shape: OverlayShape) = onOverlay { c, i ->
        c.mapOverlay(i) { it.copy(shape = shape, photo = it.photo?.let(::refit)) }
    }

    fun turnOverlayPhoto() = onOverlay { c, i -> c.mapOverlay(i) { it.copy(photo = it.photo?.turned()) } }

    fun clearOverlay() = onOverlay { c, i -> c.mapOverlay(i) { it.copy(photo = null) } }

    fun removeOverlay() {
        val i = _state.value.overlay ?: return
        push(history.current.withoutOverlay(i))
        _state.value = _state.value.copy(overlay = null)
    }

    /** The selected overlay one place up the stack, or down for a negative [by]; it stays selected. */
    fun restackOverlay(by: Int) {
        val i = _state.value.overlay ?: return
        val (next, to) = history.current.restacked(i, by)
        push(next)
        _state.value = _state.value.copy(overlay = to)
    }

    private inline fun onOverlay(change: (Collage, Int) -> Collage) {
        val i = _state.value.overlay ?: return
        push(change(history.current, i))
    }

    // ---------- Gestures ----------

    fun beginGesture() = history.begin()

    /** A cut dragged to cross its box at [ratio] and [ratio2]; it stops short of making a cell too small to touch. */
    fun moveCut(path: List<Boolean>, ratio: Float, ratio2: Float) {
        val now = history.current
        val next = now.copy(root = now.root.withCut(path, ratio, ratio2))
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

    /** After a jump through history no cell, cut or overlay is where it was: the selection goes. */
    private fun afterJump() {
        _state.value = _state.value.copy(selected = null, swapFrom = null, cut = null, overlay = null)
        publish()
    }

    private fun push(collage: Collage) {
        history.push(collage)
        val count = collage.cellCount
        val s = _state.value
        _state.value = s.copy(
            selected = s.selected?.takeIf { it < count },
            swapFrom = s.swapFrom?.takeIf { it < count },
            cut = s.cut?.takeIf { collage.root.cutAt(it) != null },
            overlay = s.overlay?.takeIf { it < collage.overlays.size },
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
        keepSession()
    }

    /** The collage, and the draft it is kept as, to open again at the next start; not a value mid-gesture. */
    private fun keepSession() {
        if (!history.inGesture) session.keep(sessionText(history.current, _state.value.draftId))
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

    // ---------- Drafts ----------

    /** The collage as its draft: over the one it is kept as, or as a new one when [asNew] or it is none yet. */
    fun saveDraft(asNew: Boolean = false) {
        viewModelScope.launch { writeDraft(asNew) }
    }

    /** See [saveDraft]; said either way, and whether it was written. */
    private suspend fun writeDraft(asNew: Boolean): Boolean {
        val collage = history.current
        val before = _state.value.draftId
        val id = before.takeUnless { asNew } ?: newDraftId()
        // Kept as it at once: a second Save draft before this one is written writes over the
        // same draft instead of making another.
        _state.value = _state.value.copy(draftId = id)
        val ok = withContext(Dispatchers.IO) { draftStore.write(id, collage) }
        val s = _state.value
        // Unless another collage took this one's place meanwhile, which is no draft of it.
        if (s.draftId == id) {
            _state.value = if (ok) s.copy(draft = collage) else s.copy(draftId = before)
            keepSession()
        }
        refreshDrafts()
        say(Notice(if (ok) R.string.draft_saved else R.string.draft_save_failed))
        return ok
    }

    /**
     * Draft [id] in place of the collage, after saving the collage as its
     * draft when [saveFirst] — and not at all if that fails.
     */
    fun openDraft(id: String, saveFirst: Boolean) {
        viewModelScope.launch {
            if (saveFirst && !writeDraft(asNew = false)) return@launch
            val collage = withContext(Dispatchers.IO) { draftStore.read(id) }
            if (collage == null) {
                say(Notice(R.string.draft_open_failed))
                refreshDrafts()
            } else {
                replaceCollage(collage, draftId = id, draft = collage)
            }
        }
    }

    /** Draft [id] gone; the collage kept as it, if any, is then kept as none, and Save draft makes a new one. */
    fun deleteDraft(id: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { draftStore.delete(id) }
            if (!ok) {
                say(Notice(R.string.draft_delete_failed))
            } else if (_state.value.draftId == id) {
                _state.value = _state.value.copy(draftId = null, draft = null)
                keepSession()
            }
            refreshDrafts()
        }
    }

    /** Draft [id] drawn [longSide] pixels on its long side, to be looked at; null when it cannot be read or drawn. */
    suspend fun draftPreview(id: String, longSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { draftStore.read(id)?.let { renderPreview(context, it, longSide) } }.getOrNull()
    }

    private suspend fun refreshDrafts() {
        val drafts = withContext(Dispatchers.IO) { draftStore.list() }
        _state.value = _state.value.copy(drafts = drafts)
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
