package com.organicmoto.maps.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry

/** Native map gestures, positioned through semantics, shared by camera and fuzz tests. */
object MapGestureRobot {
    private data class TiltGesture(
        val downTime: Long,
        val points: Array<Offset>,
        val stepY: Float,
        val increase: Boolean,
        var pointerCount: Int = 1,
    )

    private var heldGesture: TiltGesture? = null

    fun performTwoFingerTilt(rule: ComposeTestRule, increase: Boolean) {
        startTwoFingerTilt(rule, increase)
        finishTwoFingerTilt(rule, increase)
    }

    /** Leaves both fingers down so tests can inspect the active follow pause. */
    fun startTwoFingerTilt(rule: ComposeTestRule, increase: Boolean) {
        check(heldGesture == null) { "A map gesture is already held" }
        val node = rule.onNodeWithContentDescription("Map").fetchSemanticsNode()
        val origin = node.positionOnScreen
        val width = node.size.width.toFloat()
        val height = node.size.height.toFloat()
        val gesture = TiltGesture(
            downTime = SystemClock.uptimeMillis(),
            points = arrayOf(
                origin + Offset(width * 0.40f, height * 0.44f),
                origin + Offset(width * 0.60f, height * 0.44f),
            ),
            stepY = height * 0.18f * (if (increase) -1f else 1f) / (MOVE_SEGMENTS * 2),
            increase = increase,
        )
        heldGesture = gesture
        try {
            send(gesture, MotionEvent.ACTION_DOWN)
            gesture.pointerCount = 2
            send(gesture, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            SystemClock.sleep(80L)
            moveHalf(gesture)
        } catch (failure: Throwable) {
            cancel(gesture)
            throw failure
        }
    }

    fun finishTwoFingerTilt(rule: ComposeTestRule, increase: Boolean) {
        val gesture = checkNotNull(heldGesture) { "No map gesture is held" }
        try {
            check(gesture.increase == increase) { "Map gesture direction changed while held" }
            moveHalf(gesture)
            send(gesture, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT))
            gesture.pointerCount = 1
            send(gesture, MotionEvent.ACTION_UP)
            heldGesture = null
        } catch (failure: Throwable) {
            cancel(gesture)
            throw failure
        }
    }

    private fun moveHalf(gesture: TiltGesture) {
        repeat(MOVE_SEGMENTS) {
            SystemClock.sleep(16L)
            for (index in gesture.points.indices) {
                gesture.points[index] += Offset(0f, gesture.stepY)
            }
            send(gesture, MotionEvent.ACTION_MOVE)
        }
    }

    private fun cancel(gesture: TiltGesture) {
        runCatching { send(gesture, MotionEvent.ACTION_CANCEL) }
        heldGesture = null
    }

    private fun send(gesture: TiltGesture, action: Int) {
        val event = MotionEvent.obtain(
            gesture.downTime, SystemClock.uptimeMillis(), action, gesture.pointerCount,
            Array(gesture.pointerCount) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            },
            Array(gesture.pointerCount) { index ->
                MotionEvent.PointerCoords().apply {
                    x = gesture.points[index].x
                    y = gesture.points[index].y
                    // Compose 1.7.6 leaves pressure at zero. MapLibre rejects
                    // its 0/0 pressure ratio before recognizing a shove.
                    pressure = 1f
                    size = 1f
                }
            },
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true)) {
                "Native map touch injection failed for action $action"
            }
        } finally {
            event.recycle()
        }
    }

    private const val MOVE_SEGMENTS = 12
}
