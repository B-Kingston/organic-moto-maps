package com.organicmoto.maps.routing.navigation

import com.graphhopper.util.Instruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoiceGuidancePlannerTest {

    private fun snapshot(
        vararg turns: NavigationSnapshot.TurnInfo,
        state: NavigationState = NavigationState.OnRoute,
        remainingDistance: Double = 10_000.0,
    ) = NavigationSnapshot(
        state = state,
        lat = 0.0,
        lon = 0.0,
        bearingDeg = 90.0,
        speedMps = 10.0,
        speedLimitMps = Double.NaN,
        remainingDistanceM = remainingDistance,
        remainingTimeS = 600.0,
        completionPercent = 0,
        paceDeltaS = Double.NaN,
        turn = turns.firstOrNull(),
        upcomingTurns = turns.toList(),
    )

    private fun turn(
        id: Int,
        sign: Int = Instruction.TURN_LEFT,
        road: String,
        distance: Double,
    ) = NavigationSnapshot.TurnInfo(sign, road, distance, maneuverId = id)

    @Test
    fun defaultsFollowTheUserRegionAndKeepMetricDistanceInMetres() {
        assertEquals(
            200.0,
            VoiceGuidanceSettings.defaults(VoiceDistanceUnit.forLocale(Locale.forLanguageTag("en-AU"))).intervalMeters,
            1e-6,
        )
        assertEquals(
            500.0 * 0.3048,
            VoiceGuidanceSettings.defaults(VoiceDistanceUnit.forLocale(Locale.US)).intervalMeters,
            1e-6,
        )
    }

    @Test
    fun firstPromptPreviewsTheConfiguredUpcomingTurnsAndNamesTheirRoads() {
        val planner = VoiceGuidancePlanner()
        val phrase = planner.onSnapshot(
            snapshot(
                turn(10, road = "  River   Road ", distance = 1_500.0),
                turn(22, Instruction.TURN_RIGHT, "Mount Nebo Road", 3_250.0),
                turn(30, Instruction.KEEP_LEFT, "Creek Street", 4_000.0),
            ),
            VoiceGuidanceSettings(previewCount = 2),
            VoiceDistanceUnit.METRIC,
        )

        assertEquals(
            "Next 2 turns. In 1.5 kilometres, turn left onto River Road. Then, after another 1.8 kilometres, turn right onto Mount Nebo Road.",
            phrase,
        )
    }

    @Test
    fun countdownRepeatsAtEachConfiguredDistanceBoundaryWithoutRepeatingOnGpsJitter() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(intervalMeters = 200.0, previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        assertTrue(planner.onSnapshot(snapshot(turn(10, road = "River Road", distance = 1_550.0)), settings, unit)!!.contains("River Road"))

        assertEquals(
            "In 1.4 kilometres, turn left onto River Road.",
            planner.onSnapshot(snapshot(turn(10, road = "River Road", distance = 1_390.0)), settings, unit),
        )
        assertNull(planner.onSnapshot(snapshot(turn(10, road = "River Road", distance = 1_350.0)), settings, unit))
        assertEquals(
            "In 1.2 kilometres, turn left onto River Road.",
            planner.onSnapshot(snapshot(turn(10, road = "River Road", distance = 1_190.0)), settings, unit),
        )
    }

    @Test
    fun countdownReportsTheActualRemainingDistanceAfterALargeGpsJump() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(intervalMeters = 200.0, previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        planner.onSnapshot(snapshot(turn(10, road = "River Road", distance = 650.0)), settings, unit)

        assertEquals(
            "In 50 metres, turn left onto River Road.",
            planner.onSnapshot(snapshot(turn(10, road = "River Road", distance = 45.0)), settings, unit),
        )
    }

    @Test
    fun imperialCountdownUsesTheFiveHundredFootDefaultAndSpeaksRoadName() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings.defaults(VoiceDistanceUnit.IMPERIAL).copy(previewCount = 1)
        val unit = VoiceDistanceUnit.IMPERIAL
        planner.onSnapshot(snapshot(turn(10, road = "Oak Avenue", distance = 1_800.0)), settings, unit)

        val phrase = planner.onSnapshot(
            snapshot(turn(10, road = "Oak Avenue", distance = 1_500.0)),
            settings,
            unit,
        )
        assertEquals("In 4900 feet, turn left onto Oak Avenue.", phrase)
    }

    @Test
    fun arrivalPromptIsSpokenOnceAndFinishedRouteAnnouncesArrivalOnce() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        assertEquals(
            "Next turn. Now, turn left onto Main Street.",
            planner.onSnapshot(snapshot(turn(10, road = "Main Street", distance = 30.0)), settings, unit),
        )
        assertNull(planner.onSnapshot(snapshot(turn(10, road = "Main Street", distance = 15.0)), settings, unit))
        assertEquals(
            "You have arrived at your destination.",
            planner.onSnapshot(snapshot(state = NavigationState.Finished), settings, unit),
        )
        assertNull(planner.onSnapshot(snapshot(state = NavigationState.Finished), settings, unit))
    }

    @Test
    fun routeRebuildPreviewsTheNewNextTurnAndOffRoutePromptIsNotRepeated() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        planner.onSnapshot(snapshot(turn(10, road = "Old Road", distance = 2_000.0)), settings, unit)

        assertEquals(
            "Recalculating the route.",
            planner.onSnapshot(snapshot(state = NavigationState.NeedRebuild), settings, unit),
        )
        assertNull(planner.onSnapshot(snapshot(state = NavigationState.Rebuilding), settings, unit))
        assertEquals(
            "Next turn. In 1 kilometre, turn right onto New Road.",
            planner.onSnapshot(
                snapshot(turn(10, Instruction.TURN_RIGHT, "New Road", 1_000.0)),
                settings,
                unit,
            ),
        )
    }

    @Test
    fun disabledSpeechStaysSilentAndReenablingGivesTheCurrentPreview() {
        val planner = VoiceGuidancePlanner()
        val unit = VoiceDistanceUnit.METRIC
        val snapshot = snapshot(turn(10, road = "Current Road", distance = 800.0))
        assertNull(planner.onSnapshot(snapshot, VoiceGuidanceSettings(enabled = false), unit))
        assertTrue(
            planner.onSnapshot(snapshot, VoiceGuidanceSettings(enabled = true, previewCount = 1), unit)
                ?.contains("Current Road") == true,
        )
    }

    @Test
    fun isolatedUnmatchedFixDoesNotReplayTheSameTurnPreview() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        val currentTurn = turn(10, road = "Current Road", distance = 800.0)
        assertTrue(planner.onSnapshot(snapshot(currentTurn), settings, unit)!!.contains("Current Road"))

        assertNull(planner.onSnapshot(snapshot(state = NavigationState.OnRoute), settings, unit))
        assertNull(planner.onSnapshot(snapshot(currentTurn.copy(distanceM = 750.0)), settings, unit))
    }

    @Test
    fun finalManeuverStartsOneDestinationCueAfterForwardProgress() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        planner.onSnapshot(
            snapshot(turn(10, road = "River Road", distance = 40.0), remainingDistance = 5_000.0),
            settings,
            unit,
        )

        assertNull(planner.onSnapshot(snapshot(remainingDistance = 5_000.0), settings, unit))
        assertNull(planner.onSnapshot(snapshot(remainingDistance = 1_200.0), settings, unit))
        assertEquals(
            "Continue for 900 metres to your destination.",
            planner.onSnapshot(snapshot(remainingDistance = 900.0), settings, unit),
        )
        assertNull(planner.onSnapshot(snapshot(remainingDistance = 850.0), settings, unit))
    }

    @Test
    fun turnlessRouteAnnouncesDestinationNearArrivalOnlyAfterConfirmedProgress() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC

        assertNull(planner.onSnapshot(snapshot(remainingDistance = 900.0), settings, unit))
        assertNull(planner.onSnapshot(snapshot(remainingDistance = 900.0), settings, unit))
        assertEquals(
            "Continue for 850 metres to your destination.",
            planner.onSnapshot(snapshot(remainingDistance = 850.0), settings, unit),
        )
        assertNull(planner.onSnapshot(snapshot(remainingDistance = 800.0), settings, unit))
    }

    @Test
    fun missedFixAfterFinalManeuverDoesNotInventDestinationCue() {
        val planner = VoiceGuidancePlanner()
        val settings = VoiceGuidanceSettings(previewCount = 1)
        val unit = VoiceDistanceUnit.METRIC
        planner.onSnapshot(
            snapshot(turn(10, road = "River Road", distance = 40.0), remainingDistance = 500.0),
            settings,
            unit,
        )

        assertNull(planner.onSnapshot(snapshot(remainingDistance = 500.0), settings, unit))
    }

    @Test
    fun maneuverAndDistanceFormattingCoversTurnsWithoutRoadNamesAndLongDistances() {
        assertEquals("keep right", NavigationSpeechFormat.maneuver(turn(1, Instruction.KEEP_RIGHT, "", 200.0)))
        assertEquals(
            "enter the roundabout, then take the second exit onto High Street",
            NavigationSpeechFormat.maneuver(
                NavigationSnapshot.TurnInfo(
                    Instruction.USE_ROUNDABOUT,
                    "High Street",
                    200.0,
                    maneuverId = 2,
                    roundaboutExitNumber = 2,
                ),
            ),
        )
        assertEquals(
            "at the roundabout, take the second exit onto High Street",
            NavigationSpeechFormat.maneuver(
                NavigationSnapshot.TurnInfo(
                    Instruction.LEAVE_ROUNDABOUT,
                    "High Street",
                    200.0,
                    maneuverId = 3,
                    roundaboutExitNumber = 2,
                ),
            ),
        )
        assertEquals("500 metres", NavigationSpeechFormat.distance(489.0, VoiceDistanceUnit.METRIC))
        assertEquals("1 mile", NavigationSpeechFormat.distance(1_609.344, VoiceDistanceUnit.IMPERIAL))
    }
}
