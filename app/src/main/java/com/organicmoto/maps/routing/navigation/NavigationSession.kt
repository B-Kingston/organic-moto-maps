package com.organicmoto.maps.routing.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guidance session state machine, modelled on Organic Maps' RoutingSession
 * with the fields that matter for offline motorcycle guidance.
 */
enum class NavigationState {
    /** No route is loaded. */
    Idle,

    /** Route loaded, guidance not started (planning or waiting at origin). */
    NotStarted,

    /** Following the route. */
    OnRoute,

    /** Rider strayed; a rebuild from the last good position is required. */
    NeedRebuild,

    /** A rebuild is in flight; matcher state frozen until it lands. */
    Rebuilding,

    /** Rider reached the finish tolerance. */
    Finished,
}

/** One guidance snapshot for the UI: pure data, cheap to emit per fix. */
data class NavigationSnapshot(
    val state: NavigationState,
    /** Snapped position on the route (or raw fix when unmatched). */
    val lat: Double,
    val lon: Double,
    /** Bearing to display: route bearing when matched, else raw GPS heading. NaN when unknown. */
    val bearingDeg: Double,
    /** Live speed in metres per second; NaN when unknown. */
    val speedMps: Double,
    /** Speed limit of the current segment in m/s; NaN when unknown. */
    val speedLimitMps: Double,
    /** Remaining distance along the route in metres. */
    val remainingDistanceM: Double,
    /** Remaining model travel time in seconds. */
    val remainingTimeS: Double,
    /** Route completion percent, monotonic across reroutes. */
    val completionPercent: Int,
    /**
     * Live motorsport-style pace delta in seconds against the plan: actual
     * elapsed riding time minus the model time the plan budgeted for the
     * distance covered so far. Positive = slower than plan (losing time),
     * negative = ahead of plan. NaN until enough movement exists to measure,
     * and frozen while stationary or rebuilding.
     */
    val paceDeltaS: Double,
    /** Next turn, or null when none remains. */
    val turn: TurnInfo?,
) {
    data class TurnInfo(
        /** GraphHopper instruction sign; see [com.graphhopper.util.Instruction]. */
        val sign: Int,
        /** Street name for the maneuver; may be empty. */
        val streetName: String,
        /** Distance to the maneuver in metres. */
        val distanceM: Double,
    )
}

/** Fix from the engine noting a session transition that requires a rebuild. */
data class RebuildRequest(
    /** Position to rebuild from: the last good snapped position. */
    val lat: Double,
    val lon: Double,
    /** Estimated rider heading in degrees, NaN when unknown. */
    val headingDeg: Double,
)

/**
 * The guidance engine, ported in spirit from Organic Maps' RoutingSession:
 *
 *  - a forward-window matcher keeps the rider pinned to the route polyline;
 *  - matched positions adopt the route-segment bearing so the navigation
 *    arrow is stable at low speed (GPS bearing jitters when stopped);
 *  - off-route detection is hysteretic: a counter grows by 1 per fix when the
 *    rider is slow or stationary (GPS drifts while parked at a light) and by
 *    2 when moving; a rebuild fires only past [kOnRouteMissedCount];
 *  - completion percent accumulates distance covered on superseded routes so
 *    it never jumps backwards after a reroute;
 *  - remaining time interpolates inside the current segment using the same
 *    per-edge model times the planner quoted, so ETA stays consistent.
 *
 * Pure JVM: callers feed fixes via [onFix] and observe [snapshot]. The engine
 * never routes itself; it emits [onRebuildNeeded] and the wiring layer owns
 * the actual rebuild call.
 */
class NavigationSession(
    private val settings: Settings = Settings(),
) {
    data class Settings(
        /** Below this speed (m/s) the off-route counter grows by 1, not 2. */
        val minSpeedForFastMissMps: Double = 3.0 / 3.6,
        /** Snap tolerance in metres; fixes farther than this miss the route. */
        val matchingThresholdM: Double = 50.0,
        /** Below this speed (m/s) the pace delta stops updating (GPS/stop noise). */
        val deltaIdleSpeedMps: Double = 1.0,
        /** EMA smoothing weight of a fresh sample in the pace delta (0..1). */
        val deltaEmaAlpha: Double = 0.25,
        /** Movement distance that must accrue before the delta publishes. */
        val deltaMinDistanceM: Double = 150.0,
        /** GPS accuracy inflates the snap tolerance up to this cap (metres). */
        val maxAccuracyInflationM: Double = 30.0,
        /** Forward projection window in segments. */
        val projectionWindowSegments: Int = 40,
        /** Finish tolerance in metres. */
        val finishToleranceM: Double = 20.0,
        /** Minimum displayed ETA in seconds; below this the ETA floors. */
        val minimumEtaS: Double = 60.0,
    )

    private val _snapshot = MutableStateFlow(initialSnapshot())
    val snapshot: StateFlow<NavigationSnapshot> = _snapshot.asStateFlow()

    private var track: RouteTrack? = null
    private var matcher: PolylineMatcher? = null

    // Pace-delta bookkeeping: elapsed wall time vs the plan's model time for
    // the same distance. Only moving samples update it; EMA smooths GPS noise.
    private var deltaWallClockS: Double = 0.0
    private var deltaModelS: Double = 0.0
    private var lastDeltaFixTimestampMs: Long = Long.MIN_VALUE
    private var lastDeltaOffsetM: Double = 0.0
    private var paceDeltaS: Double = Double.NaN
    private var lastDeltaBankedOffsetM: Double = 0.0
    private var state = NavigationState.Idle
    private var moveAwayCounter = 0
    private var lastMissDistanceM = 0.0
    private var lastGoodLat = Double.NaN
    private var lastGoodLon = Double.NaN
    private var passedDistanceOnSupersededRoutesM = 0.0
    private var lastCompletionPercent = 0
    private val direction = DirectionAccumulator()
    private var onRebuildNeeded: ((RebuildRequest) -> Unit)? = null

    /** Registers the rebuild callback; call before the first [onFix]. */
    fun setRebuildListener(listener: (RebuildRequest) -> Unit) {
        onRebuildNeeded = listener
    }

    /** Loads (or reloads) the followed route. Resets matcher state but keeps accumulated completion. */
    fun startRoute(track: RouteTrack) {
        this.track = track
        matcher = PolylineMatcher(track)
        moveAwayCounter = 0
        lastMissDistanceM = 0.0
        lastGoodLat = Double.NaN
        lastGoodLon = Double.NaN
        state = NavigationState.NotStarted
        resetPaceDelta()
        resetSnapshot()
        publish()
    }

    /** Wipes the visible snapshot to a blank slate for the current state. */
    private fun resetSnapshot() {
        _snapshot.value = NavigationSnapshot(
            state = state,
            lat = Double.NaN,
            lon = Double.NaN,
            bearingDeg = Double.NaN,
            speedMps = Double.NaN,
            speedLimitMps = Double.NaN,
            remainingDistanceM = 0.0,
            remainingTimeS = 0.0,
            completionPercent = 0,
            paceDeltaS = Double.NaN,
            turn = null,
        )
    }

    /** Begins following after [startRoute]. */
    fun beginFollowing() {
        if (state == NavigationState.NotStarted) state = NavigationState.OnRoute
    }

    /** Stops guidance and drops the route. */
    fun stop() {
        track = null
        matcher = null
        state = NavigationState.Idle
        resetPaceDelta()
        resetSnapshot()
        publish()
    }

    /**
     * Applies a fresh route after a rebuild. Distance already covered on the
     * replaced route is banked into completion so the percent stays monotonic.
     */
    fun applyRebuiltRoute(track: RouteTrack, coveredDistanceM: Double) {
        passedDistanceOnSupersededRoutesM += coveredDistanceM
        lastCompletionPercent = 0 // recompute fresh; the banked distance keeps it monotonic
        // The reroute itself is not the rider's fault: keep the live pace
        // delta, only resync the offset book to the fresh track (offsets
        // restart at 0 there; the last timestamp carries over fine).
        lastDeltaOffsetM = 0.0
        startRoute(track)
        state = NavigationState.OnRoute
        publish()
    }

    /** Distance the rider has covered along the CURRENT route so far, metres. */
    fun currentRouteCoveredM(): Double = matcher?.currentOffsetM ?: 0.0

    /**
     * Feeds one GPS fix. Safe from any thread; the snapshot flow is the only
     * observable output.
     */
    fun onFix(fix: GpsFix) {
        val activeTrack = track ?: return
        val activeMatcher = matcher ?: return
        direction.push(fix)

        when (state) {
            NavigationState.Idle, NavigationState.Finished -> return
            NavigationState.NotStarted, NavigationState.OnRoute -> Unit
            NavigationState.NeedRebuild, NavigationState.Rebuilding -> {
                // Frozen while a rebuild is in flight: keep consuming fixes to
                // maintain the direction track, but do not re-project.
                publish()
                return
            }
        }

        val accuracy = if (fix.hasAccuracy) fix.accuracyM else 0.0
        val tolerance = settings.matchingThresholdM +
            accuracy.coerceIn(0.0, settings.maxAccuracyInflationM)
        val offset = activeMatcher.project(
            fix,
            settings.projectionWindowSegments,
            tolerance,
        )

        if (offset != null) {
            onProjected(fix, activeTrack, activeMatcher, offset)
        } else {
            onMissed(fix, activeMatcher)
        }
        publish()
    }

    private fun onProjected(
        fix: GpsFix,
        activeTrack: RouteTrack,
        activeMatcher: PolylineMatcher,
        offset: Double,
    ) {
        moveAwayCounter = 0
        lastMissDistanceM = 0.0
        val (lat, lon) = activeMatcher.projectedPosition()
        lastGoodLat = lat
        lastGoodLon = lon
        updatePaceDelta(fix, activeTrack, offset)

        // Adopt the route segment bearing when matched: stable arrow at stops.
        val bearing = activeMatcher.matchedSegmentBearingDeg()

        val remainingDistance = activeTrack.remainingDistanceM(offset)
        if (remainingDistance <= settings.finishToleranceM) {
            state = NavigationState.Finished
            publishTurnAndMetrics(fix, activeTrack, offset, lat, lon, bearing)
            return
        }
        state = NavigationState.OnRoute
        publishTurnAndMetrics(fix, activeTrack, offset, lat, lon, bearing)
    }

    /**
     * Accumulates the motorsport pace delta. For every moving fix, wall-clock
     * time since the last sample is charged against the model time the plan
     * budgeted for the distance advanced in the same interval. The raw delta
     * (elapsed - planned) is EMA-smoothed; stationary fixes and rebuild
     * freezes contribute nothing, so the number only reflects riding pace.
     */
    private fun updatePaceDelta(fix: GpsFix, activeTrack: RouteTrack, offset: Double) {
        val moving = fix.speedMps >= settings.deltaIdleSpeedMps
        val previousOffsetM = lastDeltaOffsetM
        lastDeltaOffsetM = offset
        if (!moving) {
            // Parked: wall clock runs but the plan also assumed a stop here?
            // No — the plan never budgets stops, so freeze both books and
            // simply resume the comparison when the rider moves again.
            lastDeltaFixTimestampMs = fix.timestampMs
            return
        }
        if (lastDeltaFixTimestampMs == Long.MIN_VALUE) {
            lastDeltaFixTimestampMs = fix.timestampMs
            return
        }
        val dtS = (fix.timestampMs - lastDeltaFixTimestampMs) / 1000.0
        lastDeltaFixTimestampMs = fix.timestampMs
        if (dtS <= 0.0 || dtS > MAX_DELTA_GAP_S) {
            // Clock glitch or long gap (e.g. rebuild freeze): resync silently.
            return
        }
        val dOffset = offset - previousOffsetM
        if (dOffset <= 0.0) return // backwards projection or standstill jitter
        deltaWallClockS += dtS
        val segTime = activeTrack.timeForDistanceM(dOffset)
        deltaModelS += if (segTime.isNaN()) dtS else segTime

        val covered = deltaWallClockSampledM + dOffset
        deltaWallClockSampledM = covered
        if (covered < settings.deltaMinDistanceM) return

        val raw = deltaWallClockS - deltaModelS
        paceDeltaS = if (paceDeltaS.isNaN()) raw else paceDeltaS + settings.deltaEmaAlpha * (raw - paceDeltaS)
    }

    /** Metres of movement accumulated while sampling the pace delta. */
    private var deltaWallClockSampledM: Double = 0.0

    private fun resetPaceDelta() {
        deltaWallClockS = 0.0
        deltaModelS = 0.0
        deltaWallClockSampledM = 0.0
        lastDeltaFixTimestampMs = Long.MIN_VALUE
        lastDeltaOffsetM = 0.0
        paceDeltaS = Double.NaN
    }



    private fun onMissed(fix: GpsFix, activeMatcher: PolylineMatcher) {
        // Reference is the last projection on the route (the matched point,
        // or the route start before the first match) — never the previous raw
        // fix, which would track the rider instead of the route.
        val (refLat, refLon) = if (lastGoodLat.isNaN()) {
            val t = track
            if (t != null) t.latitudes[0] to t.longitudes[0] else fix.lat to fix.lon
        } else {
            lastGoodLat to lastGoodLon
        }
        val miss = GeoMath.haversineM(refLat, refLon, fix.lat, fix.lon)
        // Hysteresis: distance change under the sensitivity threshold counts
        // as noise (GPS drifting in place), do not grow the counter.
        if (kotlin.math.abs(miss - lastMissDistanceM) > RUNAWAY_SENSITIVITY_M) {
            val slow = !fix.hasSpeed || fix.speedMps < settings.minSpeedForFastMissMps
            moveAwayCounter += if (slow) 1 else 2
        }
        lastMissDistanceM = miss
        if (moveAwayCounter > ON_ROUTE_MISSED_COUNT) {
            bankCurrentRouteProgress()
            state = NavigationState.NeedRebuild
            val heading = direction.headingDeg()
            onRebuildNeeded?.invoke(
                RebuildRequest(
                    lat = if (lastGoodLat.isNaN()) fix.lat else lastGoodLat,
                    lon = if (lastGoodLon.isNaN()) fix.lon else lastGoodLon,
                    headingDeg = heading,
                ),
            )
        }
        // Publish raw position while off-route: never fabricate a snapped dot.
        publishRaw(fix)
    }

    private fun publishTurnAndMetrics(
        fix: GpsFix,
        activeTrack: RouteTrack,
        offset: Double,
        lat: Double,
        lon: Double,
        bearing: Double,
    ) {
        val turn = activeTrack.nextTurn(offset)?.let { (node, distance) ->
            NavigationSnapshot.TurnInfo(node.sign, node.streetName, distance)
        }
        emit(fix, lat, lon, bearing, offset, turn)
    }

    private fun publishRaw(fix: GpsFix) {
        val heading = direction.headingDeg()
        val bearing = if (heading.isNaN()) Double.NaN else heading
        emit(fix, fix.lat, fix.lon, bearing, 0.0, null)
    }

    private fun emit(
        fix: GpsFix,
        lat: Double,
        lon: Double,
        bearing: Double,
        offset: Double,
        turn: NavigationSnapshot.TurnInfo?,
    ) {
        val activeTrack = track ?: return
        val remainingDistance = if (state == NavigationState.OnRoute || state == NavigationState.Finished) {
            activeTrack.remainingDistanceM(offset)
        } else {
            (activeTrack.totalDistanceM - passedDistanceOnSupersededRoutesM).coerceAtLeast(0.0)
        }
        val remainingTime = if (state == NavigationState.OnRoute || state == NavigationState.Finished) {
            activeTrack.remainingTimeS(offset)
        } else {
            activeTrack.totalTimeS
        }
        _snapshot.value = NavigationSnapshot(
            state = state,
            lat = lat,
            lon = lon,
            bearingDeg = bearing,
            speedMps = if (fix.hasSpeed) fix.speedMps else Double.NaN,
            speedLimitMps = Double.NaN, // wired once path details carry max_speed
            remainingDistanceM = remainingDistance,
            remainingTimeS = kotlin.math.max(settings.minimumEtaS, remainingTime),
            completionPercent = completionPercent(offset, activeTrack),
            paceDeltaS = paceDeltaS,
            turn = turn,
        )
    }

    private fun completionPercent(offset: Double, activeTrack: RouteTrack): Int {
        val denominator = passedDistanceOnSupersededRoutesM + activeTrack.totalDistanceM
        if (denominator <= 0.0) return 0
        val percent = 100.0 * (passedDistanceOnSupersededRoutesM + offset) / denominator
        // Quantize and clamp monotonic so the bar never jitters backwards.
        val quantized = if (state == NavigationState.Finished) {
            100
        } else {
            (percent / COMPLETION_STEP_PERCENT).toInt() * COMPLETION_STEP_PERCENT
        }
        lastCompletionPercent = kotlin.math.max(lastCompletionPercent, quantized)
        return lastCompletionPercent
    }

    private fun bankCurrentRouteProgress() {
        val activeMatcher = matcher ?: return
        passedDistanceOnSupersededRoutesM += activeMatcher.currentOffsetM
    }

    private fun publish() {
        // Re-publish state-only transitions (start/stop/rebuild transitions)
        // by copying the current snapshot with the new state.
        _snapshot.value = _snapshot.value.copy(state = state)
    }

    companion object {
        /** Gaps longer than this (s) do not accumulate into the delta. */
        const val MAX_DELTA_GAP_S = 30.0
        const val ON_ROUTE_MISSED_COUNT = 10

        /** Organic Maps' kRunawayDistanceSensitivityMeters. */
        const val RUNAWAY_SENSITIVITY_M = 0.01

        /** Organic Maps' kCompletionPercentAccuracy. */
        const val COMPLETION_STEP_PERCENT = 5

        private fun initialSnapshot() = NavigationSnapshot(
            state = NavigationState.Idle,
            lat = Double.NaN,
            lon = Double.NaN,
            bearingDeg = Double.NaN,
            speedMps = Double.NaN,
            speedLimitMps = Double.NaN,
            remainingDistanceM = 0.0,
            remainingTimeS = 0.0,
            completionPercent = 0,
            paceDeltaS = Double.NaN,
            turn = null,
        )
    }
}
