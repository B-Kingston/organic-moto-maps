package com.organicmoto.maps.ui

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.organicmoto.maps.FollowCameraLockedKey
import com.organicmoto.maps.FollowCameraSuspendedKey
import com.organicmoto.maps.GuidanceActiveKey
import com.organicmoto.maps.MainActivity
import com.organicmoto.maps.RouteUiStateKey
import com.organicmoto.maps.VisualRouteSnapshot
import com.organicmoto.maps.map.OfflineBasemapRule
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Native MapLibre camera regression coverage with its basemap installed before
 * Activity launch. It proves a real two-pointer shove tilts the map, the lock
 * stays active during inspection, and a repeated gesture restarts the full
 * cooldown before guidance restores its normal 58 degree camera.
 */
@RunWith(AndroidJUnit4::class)
class CameraFollowGestureOnDeviceTest {

    private val offlineBasemap = OfflineBasemapRule()
    private val permission = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(offlineBasemap)
        .around(permission)
        .around(compose)

    @Before
    fun dismissStartupSetup() = compose.dismissMediaStartupPrompt()

    @Test(timeout = 360_000)
    fun twoFingerTiltStaysPausedThroughRepeatedGestureThenGuidanceRecentres() {
        awaitMapPrerequisites()
        compose.onNodeWithContentDescription("From").performTextInput("-27.4698,153.0251")
        compose.onNodeWithContentDescription("To").performTextInput("-27.4570,153.0350")
        compose.onNodeWithText("START").performClick()
        compose.waitUntil(300_000) {
            hasValue(RouteUiStateKey, "success")
        }
        compose.onNodeWithText("RIDE").performClick()
        compose.onNodeWithText("END").assertExists()
        compose.waitUntil(30_000) { hasValue(GuidanceActiveKey, true) }

        val rider = -27.4698 to 153.0251
        injectDebugFix(rider.first, rider.second)
        val lockedCamera = waitForCamera("locked camera at rider") { probe ->
            probe.optBoolean("guidance") && probe.optBoolean("cameraLocked") &&
                cameraNear(probe, rider.first, rider.second) &&
                abs(probe.optDouble("tilt", 0.0) - GUIDANCE_TILT_DEGREES) <= 1.0
        }
        compose.waitUntil(10_000) { hasValue(FollowCameraLockedKey, true) }
        val autoAdvanceBeforeGesture = compose.mainClock.autoAdvance
        compose.mainClock.autoAdvance = false
        try {
            var increaseTilt = true
            var angled = performTiltShove(increaseTilt)
            if (abs(angled.camera.optDouble("tilt", GUIDANCE_TILT_DEGREES) - GUIDANCE_TILT_DEGREES) < 2.0) {
                increaseTilt = false
                angled = performTiltShove(increaseTilt)
            }
            assertTrue(
                "a two-pointer vertical shove must change native camera tilt; before=$lockedCamera after=${angled.camera}",
                abs(angled.camera.optDouble("tilt", GUIDANCE_TILT_DEGREES) - GUIDANCE_TILT_DEGREES) >= 2.0,
            )
            assertTrue("the rider lock must stay active while the map is tilted", angled.lockedWhileHeld)
            assertTrue("the follow pause must be active while the gesture is held", angled.suspendedWhileHeld)

            // Advance to 850 ms into the first cooldown, then begin a second
            // native shove. Its held pointers cross the first deadline while
            // gestureActive keeps follow suspended.
            compose.mainClock.advanceTimeBy(850L)
            val firstTiltStillVisible = readCamera() ?: error("camera probe disappeared during follow pause")
            assertTrue("the first gesture may not recenter before its pause ends", firstTiltStillVisible.optBoolean("followSuspended"))
            assertTrue(
                "the manually tilted camera must remain visible before cooldown expiry",
                abs(firstTiltStillVisible.optDouble("tilt", 0.0) - GUIDANCE_TILT_DEGREES) >= 2.0,
            )

            MapGestureRobot.startTwoFingerTilt(compose, increaseTilt)
            compose.mainClock.advanceTimeByFrame()
            compose.waitUntil(10_000) { hasValue(FollowCameraSuspendedKey, true) }
            Thread.sleep(1_800L)
            val heldGestureCamera = readCamera() ?: error("camera probe disappeared while pointers were held")
            assertTrue("follow must remain suspended for the entire held gesture", hasValue(FollowCameraSuspendedKey, true))
            assertTrue(
                "the prior inspection angle must remain visible while pointers are held",
                abs(heldGestureCamera.optDouble("tilt", 0.0) - GUIDANCE_TILT_DEGREES) >= 2.0,
            )
            val lockedWhileHeld = hasValue(FollowCameraLockedKey, true)
            val suspendedWhileHeld = hasValue(FollowCameraSuspendedKey, true)
            val beforeSecondReleaseAt = heldGestureCamera.optLong("createdAtMillis")
            MapGestureRobot.finishTwoFingerTilt(compose, increaseTilt)
            compose.mainClock.advanceTimeByFrame()
            val secondReleaseSnapshot = pollCameraSnapshot(beforeSecondReleaseAt) { it.optBoolean("followSuspended") }
            assertTrue(
                "release should publish a fresh suspended-camera snapshot; " +
                    "lockWhileHeld=$lockedWhileHeld suspendedWhileHeld=$suspendedWhileHeld camera=${readCamera()}",
                secondReleaseSnapshot != null,
            )

            // Cross the first deadline in test time, while remaining 650 ms
            // inside the second gesture's restarted 1.5 s grace period.
            compose.mainClock.advanceTimeBy(850L)
            val secondCooldown = readCamera() ?: error("camera probe disappeared during repeated pause")
            assertTrue(
                "the second gesture must restart the full cooldown after the first deadline",
                secondCooldown.optBoolean("followSuspended"),
            )
            assertTrue(
                "guidance must not snap back while the second cooldown is active",
                abs(secondCooldown.optDouble("tilt", 0.0) - GUIDANCE_TILT_DEGREES) >= 2.0,
            )

            // The Compose test clock, rather than a semantics idle wait,
            // explicitly advances beyond the new deadline. Camera JSON is
            // then polled with real time while MapLibre completes its tween.
            compose.mainClock.advanceTimeBy(700L)
            val restored = pollCamera(15_000L) { probe ->
                !probe.optBoolean("followSuspended") && cameraNear(probe, rider.first, rider.second) &&
                    abs(probe.optDouble("tilt", 0.0) - GUIDANCE_TILT_DEGREES) <= 1.0
            }
            assertTrue("guidance must recenter to 58 degrees after cooldown; latest=${readCamera()}", restored != null)
        } finally {
            compose.mainClock.autoAdvance = autoAdvanceBeforeGesture
        }
        compose.onNodeWithText("END").performClick()
    }

    private fun awaitMapPrerequisites() = CameraMapPrerequisites.awaitReady(compose) {
        readCamera()?.toString()
    }

    private data class TiltShoveObservation(
        val camera: JSONObject,
        val lockedWhileHeld: Boolean,
        val suspendedWhileHeld: Boolean,
    )

    private fun performTiltShove(increase: Boolean): TiltShoveObservation {
        val previousSnapshotAt = readCamera()?.optLong("createdAtMillis") ?: 0L
        MapGestureRobot.startTwoFingerTilt(compose, increase)
        compose.mainClock.advanceTimeByFrame()
        val lockedWhileHeld = hasValue(FollowCameraLockedKey, true)
        val suspendedWhileHeld = hasValue(FollowCameraSuspendedKey, true)
        val heldCamera = readCamera()
        MapGestureRobot.finishTwoFingerTilt(compose, increase)
        compose.mainClock.advanceTimeByFrame()

        // Avoid Compose's idle-synchronizing wait here: it may advance the
        // test clock across the whole debounce before native camera output is
        // inspected. Polling the debug probe uses real elapsed time only.
        val camera = pollCameraSnapshot(previousSnapshotAt) { it.optBoolean("followSuspended") }
            ?: error(
                "MapLibre did not publish a fresh paused camera after the shove; " +
                    "lockWhileHeld=$lockedWhileHeld suspendedWhileHeld=$suspendedWhileHeld " +
                    "heldCamera=$heldCamera latestCamera=${readCamera()} " +
                    "lockAfterRelease=${hasValue(FollowCameraLockedKey, true)} " +
                    "suspendedAfterRelease=${hasValue(FollowCameraSuspendedKey, true)}",
            )
        return TiltShoveObservation(camera, lockedWhileHeld, suspendedWhileHeld)
    }

    private fun pollCameraSnapshot(
        newerThanMillis: Long,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            val probe = readCamera()
            if (probe != null && probe.optLong("createdAtMillis") > newerThanMillis && predicate(probe)) {
                return probe
            }
            Thread.sleep(40L)
        }
        return null
    }

    private fun pollCamera(timeoutMs: Long, predicate: (JSONObject) -> Boolean): JSONObject? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val probe = readCamera()
            if (probe != null && predicate(probe)) return probe
            Thread.sleep(40L)
        }
        return null
    }

    private fun injectDebugFix(lat: Double, lon: Double) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = SystemClock.elapsedRealtimeNanos()
        context.sendBroadcast(
            Intent(VisualRouteSnapshot.FIX_ACTION)
                .setPackage(context.packageName)
                .putExtra("request_id", id.toString())
                .putExtra("lat", lat.toString())
                .putExtra("lon", lon.toString())
                .putExtra("speed", 0f),
        )
        val ack = File(context.cacheDir, "visual-fix-ack.json")
        compose.waitUntil(15_000) {
            runCatching { JSONObject(ack.readText()).optLong("requestId") == id }
                .getOrDefault(false)
        }
    }

    private fun readCamera(): JSONObject? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, VisualRouteSnapshot.CAMERA_FILE_NAME)
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun waitForCamera(description: String, predicate: (JSONObject) -> Boolean): JSONObject {
        compose.waitUntil(30_000) { readCamera()?.let(predicate) == true }
        return readCamera() ?: error("$description: camera probe is missing")
    }

    private fun cameraNear(probe: JSONObject, lat: Double, lon: Double): Boolean =
        abs(probe.optDouble("lat", Double.POSITIVE_INFINITY) - lat) <= 0.0002 &&
            abs(probe.optDouble("lon", Double.POSITIVE_INFINITY) - lon) <= 0.0002

    private fun hasValue(key: androidx.compose.ui.semantics.SemanticsPropertyKey<Boolean>, value: Boolean): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(key, value), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun hasValue(key: androidx.compose.ui.semantics.SemanticsPropertyKey<String>, value: String): Boolean =
        compose.onAllNodes(SemanticsMatcher.expectValue(key, value), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val GUIDANCE_TILT_DEGREES = 58.0
    }
}
