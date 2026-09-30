package com.organicmoto.maps.routing.navigation

import com.graphhopper.util.Instruction
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Distance units used for spoken guidance and its adjustable countdown cadence. */
enum class VoiceDistanceUnit(
    val metresPerUnit: Double,
    val singularName: String,
    val pluralName: String,
    val intervalStep: Int,
    val minimumInterval: Int,
    val maximumInterval: Int,
) {
    METRIC(1.0, "metre", "metres", intervalStep = 50, minimumInterval = 100, maximumInterval = 1_000),
    IMPERIAL(0.3048, "foot", "feet", intervalStep = 100, minimumInterval = 100, maximumInterval = 2_000);

    fun toMetres(value: Int): Double = value * metresPerUnit

    fun fromMetres(metres: Double): Int = (metres / metresPerUnit).roundToInt()

    fun intervalLabel(metres: Double): String {
        val value = fromMetres(metres).coerceAtLeast(1)
        val unitName = if (value == 1) singularName else pluralName
        return "$value $unitName"
    }

    /** Uses the user's region for a useful default while keeping distances editable. */
    companion object {
        fun forLocale(locale: Locale): VoiceDistanceUnit =
            if (locale.country.uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) IMPERIAL else METRIC

        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
    }
}

/** User-adjustable voice guidance preferences. Distances are always stored in metres. */
data class VoiceGuidanceSettings(
    val enabled: Boolean = true,
    val intervalMeters: Double = 200.0,
    /** Number of upcoming turns included in each maneuver preview (1..5). */
    val previewCount: Int = 2,
) {
    companion object {
        fun defaults(unit: VoiceDistanceUnit): VoiceGuidanceSettings = VoiceGuidanceSettings(
            intervalMeters = when (unit) {
                VoiceDistanceUnit.METRIC -> 200.0
                VoiceDistanceUnit.IMPERIAL -> 500.0 * VoiceDistanceUnit.IMPERIAL.metresPerUnit
            },
        )
    }
}

/**
 * Pure, per-fix voice prompt planner. It emits a turn preview when a maneuver
 * becomes current, then one countdown at each configured distance boundary.
 * Far turns are previewed immediately but countdowns begin within ten steps.
 */
class VoiceGuidancePlanner {
    private var enabled = false
    private var lastState = NavigationState.Idle
    private var currentManeuver: String? = null
    private var lastAnnouncedBucket: Int? = null
    private var lastIntervalMeters = Double.NaN
    private var lastRemainingDistanceMeters: Double? = null
    private var arrivalAnnounced = false
    private var destinationAnnounced = false
    private var rerouteAnnounced = false

    /** Returns a single natural spoken phrase, or null when no new prompt is due. */
    fun onSnapshot(
        snapshot: NavigationSnapshot,
        settings: VoiceGuidanceSettings,
        unit: VoiceDistanceUnit,
    ): String? {
        if (!settings.enabled) {
            if (enabled) resetProgress()
            enabled = false
            lastState = snapshot.state
            return null
        }
        if (!enabled) {
            enabled = true
            resetProgress()
        }

        when (snapshot.state) {
            NavigationState.Idle -> {
                resetProgress()
                lastState = snapshot.state
                return null
            }
            NavigationState.Finished -> {
                val shouldAnnounce = lastState != NavigationState.Finished
                lastState = snapshot.state
                return if (shouldAnnounce) "You have arrived at your destination." else null
            }
            NavigationState.NeedRebuild -> {
                val shouldAnnounce = !rerouteAnnounced
                rerouteAnnounced = true
                lastState = snapshot.state
                return if (shouldAnnounce) "Recalculating the route." else null
            }
            NavigationState.Rebuilding -> {
                val shouldAnnounce = !rerouteAnnounced
                rerouteAnnounced = true
                lastState = snapshot.state
                return if (shouldAnnounce) "Recalculating the route." else null
            }
            NavigationState.NotStarted -> {
                lastState = snapshot.state
                return null
            }
            NavigationState.OnRoute -> Unit
        }

        if (lastState == NavigationState.NeedRebuild || lastState == NavigationState.Rebuilding) {
            currentManeuver = null
            lastAnnouncedBucket = null
            lastRemainingDistanceMeters = null
            arrivalAnnounced = false
            destinationAnnounced = false
            rerouteAnnounced = false
        }

        val interval = settings.intervalMeters.takeIf { it.isFinite() && it > 0.0 } ?: 200.0
        val turns = snapshot.upcomingTurns.ifEmpty { listOfNotNull(snapshot.turn) }
        val turn = turns.firstOrNull()
        if (turn == null) {
            // A missed GPS projection temporarily has no turn. Keep the last
            // turn identity so the next good fix cannot replay its preview.
            lastIntervalMeters = interval
            val remainingDistance = snapshot.remainingDistanceM.coerceAtLeast(0.0)
            val previousRemainingDistance = lastRemainingDistanceMeters
            val madeProgress = previousRemainingDistance != null &&
                previousRemainingDistance - remainingDistance > MIN_DESTINATION_PROGRESS_METRES
            val shouldAnnounceDestination = !destinationAnnounced &&
                remainingDistance > ARRIVAL_PROMPT_METRES &&
                madeProgress &&
                remainingDistance <= DESTINATION_CUE_HORIZON_METRES
            lastRemainingDistanceMeters = remainingDistance
            lastState = snapshot.state
            if (!shouldAnnounceDestination) return null
            destinationAnnounced = true
            return NavigationSpeechFormat.destination(remainingDistance, unit)
        }

        val maneuverKey = turn.maneuverId.takeIf { it >= 0 }?.let { "id:$it" }
            ?: "${turn.sign}:${turn.streetName.trim()}"
        val distance = turn.distanceM.coerceAtLeast(0.0)
        val remainingDistance = snapshot.remainingDistanceM.coerceAtLeast(0.0)
        val horizon = interval * MAX_COUNTDOWN_INTERVALS
        val changedManeuver = maneuverKey != currentManeuver
        val changedInterval = interval != lastIntervalMeters
        lastState = snapshot.state

        if (changedManeuver) {
            currentManeuver = maneuverKey
            lastIntervalMeters = interval
            lastRemainingDistanceMeters = remainingDistance
            lastAnnouncedBucket = if (distance <= horizon) ceil(distance / interval).toInt() else null
            arrivalAnnounced = distance <= ARRIVAL_PROMPT_METRES
            return preview(turns, settings.previewCount, unit)
        }

        lastRemainingDistanceMeters = remainingDistance

        if (changedInterval) {
            lastIntervalMeters = interval
            lastAnnouncedBucket = if (distance <= horizon) ceil(distance / interval).toInt() else null
        }

        if (distance <= ARRIVAL_PROMPT_METRES) {
            if (arrivalAnnounced) return null
            arrivalAnnounced = true
            lastAnnouncedBucket = 0
            return "Now, ${NavigationSpeechFormat.maneuver(turn)}."
        }
        arrivalAnnounced = false
        if (distance > horizon) return null

        val currentBucket = ceil(distance / interval).toInt()
        val previousBucket = lastAnnouncedBucket
        if (previousBucket == null) {
            lastAnnouncedBucket = currentBucket
            return countdown(turn, distance, unit)
        }
        if (currentBucket >= previousBucket) return null

        lastAnnouncedBucket = currentBucket
        return if (currentBucket <= 0) {
            "Now, ${NavigationSpeechFormat.maneuver(turn)}."
        } else {
            countdown(turn, distance, unit)
        }
    }

    /** Clears route progress after guidance ends or the route is replaced. */
    fun reset() {
        enabled = false
        resetProgress()
        lastState = NavigationState.Idle
    }

    private fun resetProgress() {
        currentManeuver = null
        lastAnnouncedBucket = null
        lastIntervalMeters = Double.NaN
        lastRemainingDistanceMeters = null
        arrivalAnnounced = false
        destinationAnnounced = false
        rerouteAnnounced = false
    }

    private fun preview(
        turns: List<NavigationSnapshot.TurnInfo>,
        requestedCount: Int,
        unit: VoiceDistanceUnit,
    ): String? {
        val upcoming = turns.take(requestedCount.coerceIn(1, MAX_PREVIEW_COUNT))
        if (upcoming.isEmpty()) return null
        val intro = if (upcoming.size == 1) "Next turn." else "Next ${upcoming.size} turns."
        val descriptions = upcoming.mapIndexed { index, turn ->
            val action = NavigationSpeechFormat.maneuver(turn)
            when {
                index > 0 -> {
                    val previousDistance = upcoming[index - 1].distanceM.coerceAtLeast(0.0)
                    val distanceAfterPrevious = (turn.distanceM - previousDistance).coerceAtLeast(0.0)
                    if (distanceAfterPrevious <= ARRIVAL_PROMPT_METRES) {
                        "Then, shortly after, $action"
                    } else {
                        "Then, after another ${NavigationSpeechFormat.distance(distanceAfterPrevious, unit)}, $action"
                    }
                }
                turn.distanceM <= ARRIVAL_PROMPT_METRES -> "Now, $action"
                else -> "In ${NavigationSpeechFormat.distance(turn.distanceM, unit)}, $action"
            }
        }
        return "$intro ${descriptions.joinToString(". ")}."
    }

    private fun countdown(
        turn: NavigationSnapshot.TurnInfo,
        distanceMeters: Double,
        unit: VoiceDistanceUnit,
    ): String = "In ${NavigationSpeechFormat.distance(distanceMeters, unit)}, " +
        "${NavigationSpeechFormat.maneuver(turn)}."

    private companion object {
        const val MAX_COUNTDOWN_INTERVALS = 10
        const val MAX_PREVIEW_COUNT = 5
        const val ARRIVAL_PROMPT_METRES = 40.0
        const val DESTINATION_CUE_HORIZON_METRES = 1_000.0
        const val MIN_DESTINATION_PROGRESS_METRES = 1.0
    }
}

/** English speech strings, kept independent from Compose for invariant tests. */
object NavigationSpeechFormat {
    fun destination(metres: Double, unit: VoiceDistanceUnit): String =
        "Continue for ${distance(metres, unit)} to your destination."

    fun maneuver(turn: NavigationSnapshot.TurnInfo): String {
        val action = when (turn.sign) {
            Instruction.U_TURN_UNKNOWN,
            Instruction.U_TURN_LEFT,
            Instruction.U_TURN_RIGHT,
            -> "make a U-turn"
            Instruction.TURN_SHARP_LEFT -> "turn sharp left"
            Instruction.TURN_LEFT -> "turn left"
            Instruction.TURN_SLIGHT_LEFT -> "turn slight left"
            Instruction.KEEP_LEFT -> "keep left"
            Instruction.TURN_SHARP_RIGHT -> "turn sharp right"
            Instruction.TURN_RIGHT -> "turn right"
            Instruction.TURN_SLIGHT_RIGHT -> "turn slight right"
            Instruction.KEEP_RIGHT -> "keep right"
            Instruction.USE_ROUNDABOUT -> turn.roundaboutExitNumber
                ?.takeIf { it > 0 }
                ?.let { "enter the roundabout, then take the ${ordinal(it)} exit" }
                ?: "enter the roundabout"
            Instruction.LEAVE_ROUNDABOUT -> turn.roundaboutExitNumber
                ?.takeIf { it > 0 }
                ?.let { "at the roundabout, take the ${ordinal(it)} exit" }
                ?: "exit the roundabout"
            Instruction.FERRY -> "take the ferry"
            else -> "continue straight"
        }
        val road = turn.streetName.trim().replace(WHITESPACE, " ")
        return if (road.isEmpty()) action else "$action onto $road"
    }

    fun distance(metres: Double, unit: VoiceDistanceUnit): String {
        val safe = metres.coerceAtLeast(0.0)
        return when (unit) {
            VoiceDistanceUnit.METRIC -> when {
                safe < 1_000.0 -> {
                    val rounded = (safe / 50.0).roundToInt() * 50
                    if (rounded <= 0) "nearby" else "$rounded metres"
                }
                else -> formattedLongDistance(safe / 1_000.0, "kilometre", "kilometres")
            }
            VoiceDistanceUnit.IMPERIAL -> {
                val feet = safe / VoiceDistanceUnit.IMPERIAL.metresPerUnit
                if (feet < FEET_PER_MILE) {
                    val rounded = (feet / 100.0).roundToInt() * 100
                    if (rounded <= 0) "nearby" else "$rounded feet"
                } else {
                    formattedLongDistance(feet / FEET_PER_MILE, "mile", "miles")
                }
            }
        }
    }

    private fun formattedLongDistance(value: Double, singular: String, plural: String): String {
        val roundedTenths = (value * 10.0).roundToInt() / 10.0
        val whole = roundedTenths.toInt()
        val display = if (roundedTenths == whole.toDouble()) whole.toString() else "%.1f".format(Locale.US, roundedTenths)
        return "$display ${if (roundedTenths == 1.0) singular else plural}"
    }

    private fun ordinal(value: Int): String {
        val word = when (value) {
            1 -> "first"
            2 -> "second"
            3 -> "third"
            4 -> "fourth"
            5 -> "fifth"
            6 -> "sixth"
            7 -> "seventh"
            8 -> "eighth"
            9 -> "ninth"
            10 -> "tenth"
            11 -> "eleventh"
            12 -> "twelfth"
            13 -> "thirteenth"
            else -> null
        }
        if (word != null) return word
        val suffix = when (value % 100) {
            11, 12, 13 -> "th"
            else -> when (value % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$value$suffix"
    }

    private val WHITESPACE = Regex("\\s+")

    private const val FEET_PER_MILE = 5_280.0
}
