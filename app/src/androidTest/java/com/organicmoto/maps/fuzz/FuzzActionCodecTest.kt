package com.organicmoto.maps.fuzz

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves every fuzz action survives the JSON round trip, and that the checked-in
 * seed still decodes.
 *
 * This is the guard for the whole class of bug where an action is added to
 * [FuzzActions] and wired into generation but never into the codec, so a
 * minimized or recorded seed cannot be replayed. It needs no Activity, so it
 * runs as a fast pure-logic test.
 */
@RunWith(AndroidJUnit4::class)
class FuzzActionCodecTest {

    /** One instance of every action, including every field shape. */
    private val samples: List<FuzzAction> = listOf(
        Click(UiTarget.KNOB),
        LongClick(UiTarget.CARD_1),
        TypeText(UiTarget.FROM_FIELD, "Brisbane"),
        // Values that must survive escaping, not just plain ASCII.
        TypeText(UiTarget.TO_FIELD, "line\nbreak \"quoted\" \\slash\t"),
        TypeText(UiTarget.TO_FIELD, ""),
        SwipeCarousel(Direction.LEFT),
        PanMap(Direction.UP),
        TiltMap(increase = true),
        TiltMap(increase = false),
        RotateKnob(-8),
        RotateKnob(24),
        AdjustRoadShare(10),
        AdjustRoadShare(90),
        PressMedia(MediaCommand.PLAY),
        PressMedia(MediaCommand.PREVIOUS),
        AdjustVolume(increase = true),
        AdjustVolume(increase = false),
        SelectPlayer(2),
        DeleteSavedRoute(0),
        RotateDevice(DeviceOrientation.LANDSCAPE_LEFT),
        TapStart,
        StartRide,
        UseCurrentLocation,
        Back,
        OpenSavedRoutes,
        ToggleSettings,
        OpenRouteSettings,
        ApplyRouteSettings,
        ToggleBlockUnpaved,
        OpenVoiceSettings,
        ToggleDarkRideMap,
        ToggleMediaPanel,
        OpenMapsSettings,
        AddComment,
        BackgroundForeground,
    )

    @Test
    fun everyActionRoundTripsThroughTheCodec() {
        samples.forEach { action ->
            assertEquals(
                "round trip changed $action",
                action,
                FuzzActionCodec.decode(action.wireName, fieldsOf(action)),
            )
        }
    }

    @Test
    fun encodeAllEmitsOneObjectPerAction() {
        val document = FuzzActionCodec.encodeAll(samples)
        assertEquals(
            "every action must appear exactly once",
            samples.size,
            Regex("\"type\"").findAll(document).count(),
        )
        assertTrue("the document must be an actions array", document.contains("\"actions\""))
    }

    @Test
    fun theCheckedInSeedStillDecodes() {
        val actions = FuzzSeed.readActions("initial.json")
        assertTrue("the checked-in seed must not be empty", actions.isNotEmpty())
        actions.forEach { action ->
            assertEquals(action, FuzzActionCodec.decode(action.wireName, fieldsOf(action)))
        }
    }

    /**
     * The generator must only ever produce actions the codec understands, so a
     * recorded run is always replayable.
     */
    @Test
    fun generatedActionsAreAllReplayable() {
        val weights = FuzzWeights.all()
        val generated = buildSet {
            repeat(2_000) { seed ->
                add(FuzzRandom(seed.toLong()).nextAction(weights))
            }
        }
        assertTrue("the generator produced no actions", generated.isNotEmpty())
        generated.forEach { action ->
            assertEquals(action, FuzzActionCodec.decode(action.wireName, fieldsOf(action)))
        }
    }

    /** Rebuilds the decode-time argument map from an encoded action. */
    private fun fieldsOf(action: FuzzAction): Map<String, String> = action.args()
        .mapValues { (_, value) -> value.toString() }
}
