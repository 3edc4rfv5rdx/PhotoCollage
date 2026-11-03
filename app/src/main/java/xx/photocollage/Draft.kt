package xx.photocollage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** The draft's format; a file of another one is not read. */
private const val DRAFT_VERSION = 1

/**
 * The collage as the text of its draft, or of the session file it is left
 * off at. Photos are named, not held: their copies stay in the app's photo
 * folder, which keeps every one either file names.
 */
fun Collage.toJson(): String = json().toString()

/** The collage as a JSON object, for the session file to add its own to; see [toJson]. */
internal fun Collage.json(): JSONObject = JSONObject()
    .put("version", DRAFT_VERSION)
    .put("aspect", aspect.name)
    .put("landscape", landscape)
    .put("gap", gap.toDouble())
    .put("corner", corner.toDouble())
    .put("blend", blend.toDouble())
    .put("background", background)
    .put("longSide", longSide)
    .put("root", nodeJson(root))
    .apply { if (overlays.isNotEmpty()) put("overlays", JSONArray(overlays.map(::overlayJson))) }

private fun overlayJson(overlay: Overlay): JSONObject = JSONObject()
    .put("shape", overlay.shape.name)
    .put("x", overlay.centerX.toDouble())
    .put("y", overlay.centerY.toDouble())
    .put("size", overlay.size.toDouble())
    .put("angle", overlay.angle.toDouble())
    .apply { overlay.photo?.let { put("photo", cellJson(it)) } }

private fun nodeJson(node: Node): JSONObject = when (node) {
    is Leaf -> JSONObject().apply { node.photo?.let { put("photo", cellJson(it)) } }
    is Split -> JSONObject()
        .put("dir", node.dir.name)
        .put("ratio", node.ratio.toDouble())
        .apply { if (!node.straight) put("ratio2", node.ratio2.toDouble()) }
        .put("first", nodeJson(node.first))
        .put("second", nodeJson(node.second))
}

private fun cellJson(cell: CellPhoto): JSONObject = JSONObject()
    .put("file", cell.photo.file)
    .put("width", cell.photo.width)
    .put("height", cell.photo.height)
    .put("source", cell.photo.source)
    .put("zoom", cell.zoom.toDouble())
    .put("centerX", cell.centerX.toDouble())
    .put("centerY", cell.centerY.toDouble())
    .put("quarter", cell.quarter)
    .put("angle", cell.angle.toDouble())

/**
 * The collage a draft's [text] holds, or null when it is no draft of this
 * version. A photo [exists] says is gone leaves its cell empty, and every
 * number is brought back in range, so a hand-edited file cannot break the
 * editor.
 */
fun collageFromJson(text: String, exists: (String) -> Boolean): Collage? = runCatching {
    val json = JSONObject(text)
    if (json.getInt("version") != DRAFT_VERSION) return null
    Collage(
        root = nodeFrom(json.getJSONObject("root"), exists),
        aspect = enumValues<Aspect>().firstOrNull { it.name == json.optString("aspect") } ?: Aspect.FOUR_FIVE,
        landscape = json.optBoolean("landscape", false),
        gap = json.optDouble("gap", DEFAULT_GAP.toDouble()).toFloat().coerceIn(0f, MAX_GAP),
        corner = json.optDouble("corner", 0.0).toFloat().coerceIn(0f, MAX_CORNER),
        blend = json.optDouble("blend", 0.0).toFloat().coerceIn(0f, MAX_BLEND),
        background = json.optInt("background", DEFAULT_BACKGROUND),
        overlays = overlaysFrom(json.optJSONArray("overlays"), exists),
    ).let { collage ->
        // A size the shape does not offer — another version's, or a hand edit — is the shape's own.
        val long = json.optInt("longSide")
        collage.copy(longSide = long.takeIf { it in collage.aspect.sizes } ?: collage.aspect.defaultLong)
    }
}.getOrNull()

private fun nodeFrom(json: JSONObject, exists: (String) -> Boolean): Node {
    if (!json.has("dir")) return Leaf(json.optJSONObject("photo")?.let { cellFrom(it, exists) })
    val ratio = json.getDouble("ratio").toFloat()
    // A straight cut, as every cut was before one could slant, has no second end of its own.
    val (a, b) = cutRatios(ratio, json.optDouble("ratio2", ratio.toDouble()).toFloat())
    return Split(
        dir = Dir.valueOf(json.getString("dir")),
        ratio = a,
        first = nodeFrom(json.getJSONObject("first"), exists),
        second = nodeFrom(json.getJSONObject("second"), exists),
        ratio2 = b,
    )
}

/** The overlays [array] holds, none for a draft of the time before there were any; one of a shape this version does not know is left out. */
private fun overlaysFrom(array: JSONArray?, exists: (String) -> Boolean): List<Overlay> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val json = array.optJSONObject(i) ?: return@mapNotNull null
        val shape = enumValues<OverlayShape>().firstOrNull { it.name == json.optString("shape") } ?: return@mapNotNull null
        Overlay(
            shape = shape,
            centerX = json.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
            centerY = json.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
            size = json.optDouble("size", DEFAULT_OVERLAY.toDouble()).toFloat().coerceIn(MIN_OVERLAY, MAX_OVERLAY),
            angle = normalizedAngle(json.optDouble("angle", 0.0).toFloat()),
            photo = json.optJSONObject("photo")?.let { cellFrom(it, exists) },
        )
    }.take(MAX_OVERLAYS)
}

private fun cellFrom(json: JSONObject, exists: (String) -> Boolean): CellPhoto? {
    val file = json.getString("file")
    val width = json.getInt("width")
    val height = json.getInt("height")
    if (!isPhotoName(file) || width <= 0 || height <= 0 || !exists(file)) return null
    return CellPhoto(
        photo = Photo(file, width, height, json.optString("source").takeIf { json.has("source") && it.isNotBlank() }),
        zoom = json.optDouble("zoom", 1.0).toFloat().coerceIn(1f, MAX_ZOOM),
        centerX = json.optDouble("centerX", 0.5).toFloat().coerceIn(0f, 1f),
        centerY = json.optDouble("centerY", 0.5).toFloat().coerceIn(0f, 1f),
        quarter = json.optInt("quarter", 0).mod(4),
        angle = normalizedAngle(json.optDouble("angle", 0.0).toFloat()),
    )
}

/**
 * Whether [name] could be one of the app's photo copies: a plain name, no
 * path in it, so a draft cannot point the app at a file outside its folder.
 */
fun isPhotoName(name: String): Boolean =
    name.isNotEmpty() && name.none { it == '/' || it == '\\' } && name != "." && name != ".."

/** A saved draft: its [id], and when it was last written, in milliseconds since the epoch. */
data class DraftInfo(val id: String, val savedAt: Long)

private val DRAFT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Whether [id] is one [newDraftId] could have made: no path in it, so no file outside the drafts' folder is named. */
fun isDraftId(id: String): Boolean = DRAFT_ID.matches(id)

/** The id of a draft not saved before. */
fun newDraftId(): String = UUID.randomUUID().toString()

/**
 * The drafts saved by hand, one file each in [dir], by id. A photo
 * [photoExists] says is gone leaves its cell empty as a draft is read.
 */
class DraftStore(private val dir: File, private val photoExists: (String) -> Boolean) {

    private fun fileOf(id: String) = File(dir, "$id$DRAFT_EXT")

    /** Every draft there is, readable or not, the one written last first. */
    fun list(): List<DraftInfo> = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(DRAFT_EXT) && isDraftId(it.name.removeSuffix(DRAFT_EXT)) }
        .map { DraftInfo(it.name.removeSuffix(DRAFT_EXT), it.lastModified()) }
        .sortedWith(compareByDescending<DraftInfo> { it.savedAt }.thenBy { it.id })

    /** The collage draft [id] holds, or null when there is none or it cannot be read. */
    fun read(id: String): Collage? {
        if (!isDraftId(id)) return null
        return runCatching { fileOf(id).readText() }.getOrNull()?.let { collageFromJson(it, photoExists) }
    }

    /** [collage] as draft [id], over what it held if anything, whole or not at all; whether it was written. */
    fun write(id: String, collage: Collage): Boolean = isDraftId(id) && runCatching {
        dir.mkdirs()
        writeAtomically(fileOf(id), collage.toJson())
    }.isSuccess

    /** Draft [id] gone; whether it is, a draft that was never there being as gone as one deleted. */
    fun delete(id: String): Boolean = isDraftId(id) && fileOf(id).let { it.delete() || !it.exists() }

    /**
     * The draft in [old] — the one file of the time when there was a single
     * draft — moved in as one of these; its id, or null when there was none
     * to move.
     */
    fun adopt(old: File): String? {
        if (!old.isFile) return null
        dir.mkdirs()
        val id = newDraftId()
        return id.takeIf { old.renameTo(fileOf(it)) }
    }

    private companion object {
        const val DRAFT_EXT = ".json"
    }
}

/** Writes [text] to [file] whole or not at all: into a file beside it first, then moved over. */
fun writeAtomically(file: File, text: String) {
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
        temp.delete()
        error("Could not replace ${file.name}")
    }
}
