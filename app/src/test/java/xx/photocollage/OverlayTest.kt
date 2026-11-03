package xx.photocollage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTest {

    private val eps = 1e-3f
    private val photo = CellPhoto(Photo("o.img", 40, 30))

    @Test
    fun everyShapeFitsItsSquareAndHoldsItsMiddle() {
        OverlayShape.entries.forEach { shape ->
            val outline = outlineOf(shape)
            val box = boundsOf(outline)
            assertTrue("$shape too wide", box.width <= 1f + eps && box.height <= 1f + eps)
            assertTrue("$shape not centred", kotlin.math.abs(box.centerX) < 0.01f && kotlin.math.abs(box.centerY) < 0.01f)
            assertTrue("$shape misses its middle", insidePolygon(outline, 0f, 0f))
        }
    }

    @Test
    fun aStarHasGapsBetweenItsPoints() {
        val star = outlineOf(OverlayShape.STAR)
        // Straight up is a point; between two points, as far out, is not the star.
        assertTrue(insidePolygon(star, 0f, -0.4f))
        assertFalse(insidePolygon(star, 0.3f, -0.3f))
    }

    @Test
    fun anOverlayLiesWhereItsMiddleAndSizeSay() {
        // A canvas 200 by 100: the shorter side 100, so a size of 0.4 is 40 across.
        val place = placeOf(Overlay(OverlayShape.SQUARE, centerX = 0.25f, centerY = 0.5f, size = 0.4f), 200f, 100f)
        assertEquals(Pt(50f, 50f), place.center)
        assertEquals(40f, place.side, eps)
        assertTrue(place.contains(69f, 69f))
        assertFalse(place.contains(71f, 50f))
        assertEquals(Pt(70f, 70f), place.handle)
    }

    @Test
    fun aTurnedOverlayTurnsItsOutlineAndHandle() {
        val place = placeOf(Overlay(OverlayShape.SQUARE, size = 0.4f, angle = 45f), 100f, 100f)
        // Turned 45°, the square reaches 20·√2 straight right of its middle.
        assertTrue(place.contains(77f, 50f))
        assertFalse(place.contains(70f, 70f))
        // The handle turned with it: from the lower right corner to straight below.
        assertEquals(50f, place.handle.x, eps)
        assertEquals(50f + 20f * 1.4142f, place.handle.y, 0.01f)
        // And back: the handle seen from the overlay is its frame's corner.
        val local = place.toLocal(place.handle)
        assertEquals(20f, local.x, eps)
        assertEquals(20f, local.y, eps)
    }

    @Test
    fun theTopmostOverlayTakesATouch() {
        val collage = Collage(aspect = Aspect.SQUARE)
            .withOverlay(Overlay(OverlayShape.CIRCLE))
            .withOverlay(Overlay(OverlayShape.SQUARE, size = 0.2f))
        val layout = layoutOf(collage, 100f, 100f)
        assertEquals(1, layout.overlayAt(50f, 50f))
        assertEquals(0, layout.overlayAt(50f, 35f))
        assertNull(layout.overlayAt(5f, 5f))
    }

    @Test
    fun noMoreThanTheMostOverlays() {
        var collage = Collage()
        repeat(MAX_OVERLAYS + 2) { collage = collage.withOverlay(Overlay(OverlayShape.HEART)) }
        assertEquals(MAX_OVERLAYS, collage.overlays.size)
    }

    @Test
    fun restackingMovesAnOverlayAsFarAsItGoes() {
        val shapes = listOf(OverlayShape.CIRCLE, OverlayShape.SQUARE, OverlayShape.STAR)
        val collage = Collage(overlays = shapes.map { Overlay(it) })
        val (up, to) = collage.restacked(0, 1)
        assertEquals(1, to)
        assertEquals(listOf(OverlayShape.SQUARE, OverlayShape.CIRCLE, OverlayShape.STAR), up.overlays.map { it.shape })
        val (top, at) = collage.restacked(2, 1)
        assertEquals(2, at)
        assertEquals(collage, top)
    }

    @Test
    fun sizesAndPlacesAreHeldInRange() {
        val overlay = Overlay(OverlayShape.CIRCLE)
        assertEquals(MAX_OVERLAY, overlay.sizedTo(5f, 0f).size, eps)
        assertEquals(MIN_OVERLAY, overlay.sizedTo(0f, 0f).size, eps)
        assertEquals(-90f, overlay.sizedTo(0.5f, 270f).angle, eps)
        assertEquals(1f, overlay.movedTo(2f, -1f).centerX, eps)
        assertEquals(0f, overlay.movedTo(2f, -1f).centerY, eps)
    }

    @Test
    fun anOverlaysPhotoCountsAsWorkAndIsKept() {
        val collage = Collage(overlays = listOf(Overlay(OverlayShape.CIRCLE, photo = photo)))
        assertTrue(collage.cellsEmpty)
        assertFalse(collage.isEmpty)
        assertTrue(collage.hasWorkBeyond(null))
        assertEquals(setOf("o.img"), keptPhotos(collage))
        // An empty overlay is no work.
        assertTrue(Collage(overlays = listOf(Overlay(OverlayShape.CIRCLE))).isEmpty)
    }

    @Test
    fun overlaysReadBack() {
        val collage = Collage(
            overlays = listOf(
                Overlay(OverlayShape.STAR, 0.3f, 0.7f, 0.5f, 30f, photo),
                Overlay(OverlayShape.HEART),
            ),
        )
        assertEquals(collage, collageFromJson(collage.toJson()) { true })
        assertFalse(Collage().toJson().contains("overlays"))
        assertEquals(emptyList<Overlay>(), collageFromJson(Collage().toJson()) { true }!!.overlays)
    }

    @Test
    fun aStrangeOverlayIsLeftOutOrHeldInRange() {
        val json = JSONObject(Collage(overlays = listOf(Overlay(OverlayShape.CIRCLE), Overlay(OverlayShape.SQUARE))).toJson())
        val list = json.getJSONArray("overlays")
        list.getJSONObject(0).put("shape", "SPIRAL")
        list.getJSONObject(1).put("size", 9.0).put("x", -3.0)
        val back = collageFromJson(json.toString()) { true }!!.overlays
        assertEquals(1, back.size)
        assertEquals(MAX_OVERLAY, back[0].size, eps)
        assertEquals(0f, back[0].centerX, eps)
    }

    @Test
    fun anOverlaysPhotoWhoseCopyIsGoneLeavesItEmpty() {
        val collage = Collage(overlays = listOf(Overlay(OverlayShape.CIRCLE, photo = photo)))
        assertNull(collageFromJson(collage.toJson()) { false }!!.overlays.single().photo)
    }

    @Test
    fun aLayoutKeepsTheOverlays() {
        val collage = Collage(overlays = listOf(Overlay(OverlayShape.CIRCLE, photo = photo)))
        assertEquals(collage.overlays, collage.withLayout(templates(3).first()).overlays)
    }
}
