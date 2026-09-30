package com.organicmoto.maps.map

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer

/**
 * Pixel and style contracts for the navigation rider marker: the freestanding
 * faceted chevron, its density-aware size, and the layer that rotates it into
 * map space while keeping it upright for the pitched viewport. These catch
 * regressions that semantics cannot see.
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

    private fun rowSpan(image: IntArray, width: Int, y: Int): Int {
        var first = -1
        var last = -1
        for (x in 0 until width) {
            if (Color.alpha(image[y * width + x]) > 160) {
                if (first < 0) first = x
                last = x
            }
        }
        return if (first < 0) 0 else last - first + 1
    }

    @Test
    fun guidanceChevronIsAPointedFacetedArrowWithoutTheOldDisc() {
        val bitmap = buildGuidanceChevronBitmap(2f)
        val width = bitmap.width
        val height = bitmap.height
        val image = bitmapPixels(bitmap)

        // Freestanding: no disc means every bitmap edge midpoint is empty.
        assertTrue(alphaAt(image, width, 0, 0) < 10)
        assertTrue(alphaAt(image, width, width - 1, 0) < 10)
        assertTrue(alphaAt(image, width, 0, height - 1) < 10)
        assertTrue(alphaAt(image, width, width - 1, height - 1) < 10)
        assertTrue(alphaAt(image, width, width / 2, 0) < 10)
        assertTrue(alphaAt(image, width, 0, height / 2) < 10)
        assertTrue(alphaAt(image, width, width - 1, height / 2) < 10)
        // The centre notch keeps the bottom middle free of the opaque faces
        // (only the soft contact shadow may tint it).
        assertTrue(alphaAt(image, width, width / 2, height - 1) < 60)
        assertTrue(
            "a subtle contact shadow must sit below the wings",
            alphaAt(image, width, width / 2, height - 2) in 1..60,
        )

        // Pointed top, swept bottom wings.
        val apexSpan = rowSpan(image, width, (height * 0.12f).toInt())
        val wingSpan = rowSpan(image, width, (height * 0.92f).toInt())
        assertTrue("apex row must be a narrow point", apexSpan > 0 && apexSpan < width / 3)
        assertTrue("wing row must be wide", wingSpan > width * 2 / 3)
        assertTrue("the arrow must widen downward", wingSpan > apexSpan * 2)

        // Faceted faces: cyan-blue left, saturated royal-blue right, dark bevel.
        var cyan = 0
        var royal = 0
        var bevel = 0
        var white = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = argb(image, width, x, y)
                if (Color.alpha(color) < 200) continue
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (red > 230 && green > 230 && blue > 230) white++
                when {
                    red < 120 && green > 130 && blue > 180 -> cyan++
                    red < 120 && green < 110 && blue > 150 -> royal++
                    red < 90 && green < 90 && blue < 140 -> bevel++
                }
            }
        }
        assertTrue("left face must stay cyan-blue", cyan > 60)
        assertTrue("right face must stay royal blue", royal > 60)
        assertTrue("lower bevel facets must stay dark blue", bevel > 40)
        assertEquals("the old white arrow glyph must not return", 0, white)
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
