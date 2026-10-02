package com.organicmoto.maps.map

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Pixel and style contracts for the navigation rider marker: the freestanding
 * rounded arrow, its grayscale road contrast, density-aware size, and heading.
 * These checks catch visible regressions that semantics cannot see.
 */
@RunWith(AndroidJUnit4::class)
class NavPositionMarkerTest {

    private fun bitmapPixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun argb(image: IntArray, width: Int, x: Int, y: Int): Int = image[y * width + x]

    private fun alphaAt(image: IntArray, width: Int, x: Int, y: Int): Int =
        Color.alpha(argb(image, width, x, y))

    @Test
    fun guidanceArrowKeepsItsRearNotchAndRaisedFaces() {
        val bitmap = buildGuidanceChevronBitmap(2f)
        val image = bitmapPixels(bitmap)
        val width = bitmap.width
        val height = bitmap.height
        assertTrue("the tip must be filled", alphaAt(image, width, width / 2, height / 4) > 200)
        val rearY = (height * 0.82f).toInt()
        assertTrue("the left wing must be filled", alphaAt(image, width, width / 4, rearY) > 200)
        assertTrue("the right wing must be filled", alphaAt(image, width, width * 3 / 4, rearY) > 200)
        assertTrue("the rear notch must remain open", alphaAt(image, width, width / 2, rearY) < 100)
        for (x in listOf(0, width - 1)) {
            for (y in listOf(0, height - 1)) {
                assertEquals("no disc may surround the arrow", 0, alphaAt(image, width, x, y))
            }
        }
        val left = argb(image, width, width * 2 / 5, height / 2)
        val right = argb(image, width, width * 3 / 5, height / 2)
        assertTrue("the left face must be brighter", Color.green(left) > Color.green(right) + 50)
        assertTrue("both faces must stay blue", Color.blue(left) > Color.red(left) && Color.blue(right) > Color.red(right))
    }

    @Test
    fun monochromeOutlineSeparatesBothSidesFromAWhiteRoad() {
        val marker = buildGuidanceChevronBitmap(2f, monochrome = true)
        val road = Bitmap.createBitmap(marker.width, marker.height, Bitmap.Config.ARGB_8888)
        road.density = marker.density
        val canvas = android.graphics.Canvas(road)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(marker, 0f, 0f, null)
        val row = marker.height / 2
        val pixels = bitmapPixels(road)
        for (range in listOf(0 until marker.width / 2, marker.width / 2 until marker.width)) {
            assertTrue(
                "each side needs a dark outline against the white road",
                range.any { Color.red(argb(pixels, marker.width, it, row)) < 40 },
            )
        }
        assertTrue("the top face must remain light", Color.red(argb(pixels, marker.width, marker.width * 2 / 5, row)) > 200)
    }

    @Test
    fun monochromeGuidanceArrowUsesOnlyGrayscaleFaces() {
        val bitmap = buildGuidanceChevronBitmap(2f, monochrome = true)
        val image = bitmapPixels(bitmap)
        var brightFace = 0
        var darkFace = 0
        for (pixel in image) {
            if (Color.alpha(pixel) < 200) continue
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            assertTrue("marker must stay grayscale: #${Integer.toHexString(pixel)}", kotlin.math.abs(red - green) <= 2)
            assertTrue("marker must stay grayscale: #${Integer.toHexString(pixel)}", kotlin.math.abs(green - blue) <= 2)
            if (red > 200) brightFace++
            if (red in 50..170) darkFace++
        }
        assertTrue("the light face must remain visible", brightFace > 60)
        assertTrue("the shaded face and bevel must remain visible", darkFace > 60)
        assertNotEquals(NAV_CHEVRON_IMAGE_ID, NAV_CHEVRON_MONO_IMAGE_ID)
    }

    @Test
    fun guidanceChevronScalesWithDisplayDensity() {
        val baseline = buildGuidanceChevronBitmap(1f)
        val phone = buildGuidanceChevronBitmap(2.5f)
        assertTrue(phone.width > baseline.width)
        assertTrue(phone.height > baseline.height)
        assertEquals(2.5f, phone.width.toFloat() / baseline.width.toFloat(), 0.1f)
        assertEquals(2.5f, phone.height.toFloat() / baseline.height.toFloat(), 0.1f)
        // The bitmap density drives MapLibre's pixel ratio, so the marker is
        // the same physical size on every screen.
        assertEquals(160, baseline.density)
        assertEquals(400, phone.density)
        // A broken density must not collapse or explode the marker.
        assertEquals(baseline.width, buildGuidanceChevronBitmap(0f).width)
        assertEquals(baseline.width, buildGuidanceChevronBitmap(Float.NaN).width)
    }

    @Test
    fun followMarkerKeepsItsOriginalDiscLookForNonNavigationTracking() {
        val bitmap = buildFollowMarkerBitmap()
        val image = bitmapPixels(bitmap)
        val center = bitmap.width / 2
        assertEquals(Color.WHITE, argb(image, bitmap.width, center, center))
        // Disc edge is blue; the icon corners stay empty.
        val discPixel = argb(image, bitmap.width, center, 3)
        assertTrue(Color.alpha(discPixel) > 200)
        assertTrue(Color.blue(discPixel) > Color.red(discPixel))
        assertEquals(0, alphaAt(image, bitmap.width, 0, 0))
    }

    @Test
    fun guidanceLayerRotatesWithTheMapAndStaysUprightInTheViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val applicationContext = instrumentation.targetContext.applicationContext
        val guidanceRotation = java.util.concurrent.atomic.AtomicReference<String?>()
        val guidancePitch = java.util.concurrent.atomic.AtomicReference<String?>()
        val allowOverlap = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val ignorePlacement = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val rotateIsExpression = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val rotateJson = java.util.concurrent.atomic.AtomicReference<String?>()
        val guidanceImage = java.util.concurrent.atomic.AtomicReference<String?>()
        val monochromeImage = java.util.concurrent.atomic.AtomicReference<String?>()
        val followImage = java.util.concurrent.atomic.AtomicReference<String?>()
        val followRotation = java.util.concurrent.atomic.AtomicReference<String?>()
        val followRotate = java.util.concurrent.atomic.AtomicReference<Float?>()
        instrumentation.runOnMainSync {
            // Layer construction and property reads check the main thread
            // through MapLibre's ThreadUtils, which only exists once the SDK
            // instance is created.
            org.maplibre.android.MapLibre.getInstance(applicationContext)

            val guidanceLayer = buildNavPositionLayer(NAV_CHEVRON_IMAGE_ID, guidance = true)
            guidanceRotation.set(guidanceLayer.getIconRotationAlignment().value)
            guidancePitch.set(guidanceLayer.getIconPitchAlignment().value)
            guidanceImage.set(guidanceLayer.getIconImage().value)
            monochromeImage.set(
                buildNavPositionLayer(NAV_CHEVRON_MONO_IMAGE_ID, guidance = true)
                    .getIconImage().value,
            )
            allowOverlap.set(guidanceLayer.getIconAllowOverlap().value)
            ignorePlacement.set(guidanceLayer.getIconIgnorePlacement().value)
            val rotate = guidanceLayer.getIconRotate()
            rotateIsExpression.set(rotate.isExpression)
            rotateJson.set(rotate.expression.toString())

            // Ordinary location tracking keeps the legacy screen-upright disc.
            val followLayer = buildNavPositionLayer(NAV_FOLLOW_IMAGE_ID, guidance = false)
            followImage.set(followLayer.getIconImage().value)
            followRotation.set(followLayer.getIconRotationAlignment().value)
            followRotate.set(followLayer.getIconRotate().value)
        }

        assertEquals(Property.ICON_ROTATION_ALIGNMENT_MAP, guidanceRotation.get())
        assertEquals(Property.ICON_PITCH_ALIGNMENT_VIEWPORT, guidancePitch.get())
        assertEquals(NAV_CHEVRON_IMAGE_ID, guidanceImage.get())
        assertEquals(NAV_CHEVRON_MONO_IMAGE_ID, monochromeImage.get())
        assertEquals(true, allowOverlap.get())
        assertEquals(true, ignorePlacement.get())
        assertEquals(true, rotateIsExpression.get())
        assertTrue(
            "icon-rotate must consume the marker bearing property: ${rotateJson.get()}",
            rotateJson.get()?.contains(NAV_POSITION_BEARING_PROPERTY) == true,
        )
        assertFalse(
            "viewport rotation would detach the chevron from the road heading",
            guidanceRotation.get() == Property.ICON_ROTATION_ALIGNMENT_VIEWPORT,
        )

        assertEquals(NAV_FOLLOW_IMAGE_ID, followImage.get())
        assertEquals(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT, followRotation.get())
        assertEquals(0f, followRotate.get() ?: Float.NaN, 0f)
    }
}
