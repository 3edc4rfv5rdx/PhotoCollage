package xx.photocollage

import org.json.JSONObject
import java.io.File

/** The draft's format; a file of another one is not read. */
private const val DRAFT_VERSION = 1

/**
 * The collage as the text of its draft, or of the session file it is left
 * off at. Photos are named, not held: their copies stay in the app's photo
 * folder, which keeps every one either file names.
 */
fun Collage.toJson(): String = JSONObject()
    .put("version", DRAFT_VERSION)
    .put("aspect", aspect.name)
    .put("landscape", landscape)
    .put("gap", gap.toDouble())
    .put("corner", corner.toDouble())
    .put("background", background)
    .put("longSide", longSide)
    .put("root", nodeJson(root))
    .toString()

private fun nodeJson(node: Node): JSONObject = when (node) {
    is Leaf -> JSONObject().apply { node.photo?.let { put("photo", cellJson(it)) } }
    is Split -> JSONObject()
        .put("dir", node.dir.name)
        .put("ratio", node.ratio.toDouble())
        .put("first", nodeJson(node.first))
        .put("second", nodeJson(node.second))
}

private fun cellJson(cell: CellPhoto): JSONObject = JSONObject()
    .put("file", cell.photo.file)
    .put("width", cell.photo.width)
    .put("height", cell.photo.height)
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
        background = json.optInt("background", DEFAULT_BACKGROUND),
    ).let { collage ->
        // A size the shape does not offer — another version's, or a hand edit — is the shape's own.
        val long = json.optInt("longSide")
        collage.copy(longSide = long.takeIf { it in collage.aspect.sizes } ?: collage.aspect.defaultLong)
    }
}.getOrNull()

private fun nodeFrom(json: JSONObject, exists: (String) -> Boolean): Node {
    if (!json.has("dir")) return Leaf(json.optJSONObject("photo")?.let { cellFrom(it, exists) })
    return Split(
        dir = Dir.valueOf(json.getString("dir")),
        ratio = json.getDouble("ratio").toFloat().coerceIn(MIN_RATIO, 1f - MIN_RATIO),
        first = nodeFrom(json.getJSONObject("first"), exists),
        second = nodeFrom(json.getJSONObject("second"), exists),
    )
}

private fun cellFrom(json: JSONObject, exists: (String) -> Boolean): CellPhoto? {
    val file = json.getString("file")
    val width = json.getInt("width")
    val height = json.getInt("height")
    if (!isPhotoName(file) || width <= 0 || height <= 0 || !exists(file)) return null
    return CellPhoto(
        photo = Photo(file, width, height),
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

/** Writes [text] to [file] whole or not at all: into a file beside it first, then moved over. */
fun writeAtomically(file: File, text: String) {
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(text)
    if (!temp.renameTo(file)) {
        temp.delete()
        error("Could not replace ${file.name}")
    }
}
