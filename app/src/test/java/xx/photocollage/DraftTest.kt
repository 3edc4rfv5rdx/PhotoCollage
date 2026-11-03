package xx.photocollage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class DraftTest {

    private val collage = Collage(
        root = Split(
            Dir.ROW, 0.4f,
            Leaf(CellPhoto(Photo("a.img", 4000, 3000), zoom = 2f, centerX = 0.3f, centerY = 0.6f, quarter = 1, angle = -12.5f)),
            Split(Dir.COLUMN, 0.7f, Leaf(), Leaf(CellPhoto(Photo("b.img", 10, 20)))),
        ),
        aspect = Aspect.NINE_SIXTEEN,
        landscape = true,
        gap = 0.02f,
        corner = 0.05f,
        background = 0xFF1F3A5F.toInt(),
        longSide = 3840,
    )

    @Test
    fun aDraftReadsBackAsItWasWritten() {
        assertEquals(collage, collageFromJson(collage.toJson()) { true })
    }

    @Test
    fun aMissingPhotoLeavesItsCellEmpty() {
        val back = collageFromJson(collage.toJson()) { it != "a.img" }!!
        assertNull(back.leaves[0].photo)
        assertEquals("b.img", back.leaves[2].photo?.photo?.file)
    }

    @Test
    fun aPathInAPhotoNameIsRefused() {
        val text = collage.toJson().replace("b.img", "../../shared_prefs/x")
        assertNull(collageFromJson(text) { true }!!.leaves[2].photo)
        assertFalse(isPhotoName(".."))
        assertTrue(isPhotoName("abc.img"))
    }

    @Test
    fun numbersOutOfRangeAreBroughtBack() {
        val json = JSONObject(collage.toJson())
        json.put("gap", 5)
        json.getJSONObject("root").put("ratio", -3)
        val back = collageFromJson(json.toString()) { true }!!
        assertEquals(MAX_GAP, back.gap, 1e-6f)
        assertEquals(MIN_RATIO, (back.root as Split).ratio, 1e-6f)
    }

    @Test
    fun aSizeTheShapeDoesNotOfferIsItsOwn() {
        val json = JSONObject(collage.toJson()).put("longSide", 1350)
        assertEquals(Aspect.NINE_SIXTEEN.defaultLong, collageFromJson(json.toString()) { true }!!.longSide)
    }

    @Test
    fun anotherVersionOrGarbageIsNoDraft() {
        assertNull(collageFromJson(collage.toJson().replace("\"version\":1", "\"version\":2")) { true })
        assertNull(collageFromJson("not json") { true })
        assertNull(collageFromJson("{}") { true })
    }

    @Test
    fun theBlendReadsBackAndIsHeldToItsRange() {
        val blended = collage.copy(blend = 0.03f)
        assertEquals(0.03f, collageFromJson(blended.toJson()) { true }!!.blend)
        val wild = JSONObject(blended.toJson()).put("blend", 5.0).toString()
        assertEquals(MAX_BLEND, collageFromJson(wild) { true }!!.blend)
        // A draft from before the blend has none.
        val old = JSONObject(blended.toJson()).apply { remove("blend") }.toString()
        assertEquals(0f, collageFromJson(old) { true }!!.blend)
    }

    @Test
    fun aPhotosSourceReadsBackAndAnOldDraftHasNone() {
        val known = Collage(root = Leaf(CellPhoto(Photo("a.img", 40, 30, source = "DCIM/Camera/IMG_0001.jpg"))))
        assertEquals("DCIM/Camera/IMG_0001.jpg", collageFromJson(known.toJson()) { true }!!.photos().single().source)
        val unknown = Collage(root = Leaf(CellPhoto(Photo("a.img", 40, 30))))
        assertFalse(unknown.toJson().contains("\"source\""))
        assertNull(collageFromJson(unknown.toJson()) { true }!!.photos().single().source)
    }
}
