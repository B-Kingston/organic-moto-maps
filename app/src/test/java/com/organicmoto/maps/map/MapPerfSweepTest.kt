package com.organicmoto.maps.map

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract for the debug map-performance sweep: the camera geometry the
 * on-device benchmark animates, the request JSON the host queues, and the
 * frame-interval statistics the report publishes. The instrumented
 * `MapPerformanceOnDeviceTest` proves the same plan moves a real MapView.
 */
class MapPerfSweepTest {

    private fun request(
        motion: MapPerfMotion = MapPerfMotion.ORBIT,
        zoomStart: Double = 18.0,
        zoomEnd: Double = 18.0,
        bearingStart: Double = 0.0,
        bearingEnd: Double = 360.0,
        durationMs: Long = 8_000L,
        latEnd: Double = -27.4679,
        lonEnd: Double = 153.0281,
        buildingMode: BuildingDiagnosticMode = BuildingDiagnosticMode.CURRENT,
        settle: Boolean = true,
        hideLayers: List<String> = emptyList(),
        tileLod: MapPerfTileLod? = null,
    ) = MapPerfSweepRequest(
        requestId = 42L,
        label = "cbd-orbit",
        motion = motion,
        lat = -27.4679,
        lon = 153.0281,
        latEnd = latEnd,
        lonEnd = lonEnd,
        zoomStart = zoomStart,
        zoomEnd = zoomEnd,
        tilt = 58.0,
        bearingStart = bearingStart,
        bearingEnd = bearingEnd,
        durationMs = durationMs,
        settle = settle,
        buildingMode = buildingMode,
        hideLayers = hideLayers,
        tileLod = tileLod,
    )

    @Test
    fun orbitPlanSplitsIntoQuarterTurnsAndEndsAtTheStartBearing() {
        val legs = MapPerfSweepPlan.legs(request())
        assertEquals(4, legs.size)
        assertEquals(listOf(2000, 2000, 2000, 2000), legs.map { it.durationMs })
        assertEquals(90.0, legs[0].bearing, 0.001)
        assertEquals(180.0, legs[1].bearing, 0.001)
        assertEquals(270.0, legs[2].bearing, 0.001)
        assertEquals(0.0, legs[3].bearing, 0.001)
        assertTrue(legs.all { it.zoom == 18.0 && it.tilt == 58.0 })
        assertTrue(legs.all { it.target.lat == -27.4679 && it.target.lon == 153.0281 })
    }

    @Test
    fun orbitWithHalfTurnTakesTheShortWayAround() {
        val legs = MapPerfSweepPlan.legs(request(bearingStart = 350.0, bearingEnd = 10.0))
        assertEquals(1, legs.size)
        assertEquals(10.0, legs.single().bearing, 0.001)
    }

    @Test
    fun panPlanWalksToTheEndTargetAndNeverBeyondIt() {
        val legs = MapPerfSweepPlan.legs(
            request(motion = MapPerfMotion.PAN, latEnd = -27.4616, lonEnd = 153.0466),
        )
        assertTrue("a ~2 km pan needs several legs", legs.size in 2..6)
        assertEquals(-27.4616, legs.last().target.lat, 1e-9)
        assertEquals(153.0466, legs.last().target.lon, 1e-9)
        var previousLat = -27.4679
        for (leg in legs) {
            assertTrue("pan must advance monotonically", leg.target.lat >= previousLat)
            previousLat = leg.target.lat
        }
        assertTrue(legs.all { it.zoom == 18.0 })
    }

    @Test
    fun zoomPlanCoversTheRequestedBandAndStopsAtTheEndZoom() {
        val legs = MapPerfSweepPlan.legs(
            request(motion = MapPerfMotion.ZOOM, zoomStart = 14.0, zoomEnd = 18.0),
        )
        assertEquals(3, legs.size)
        assertTrue(legs.first().zoom > 14.0)
        assertEquals(18.0, legs.last().zoom, 1e-9)
        assertTrue("zoom must increase every leg", legs.zipWithNext().all { (a, b) -> b.zoom > a.zoom })
    }

    @Test
    fun comboPlanMovesPositionZoomAndBearingTogether() {
        val legs = MapPerfSweepPlan.legs(
            request(
                motion = MapPerfMotion.COMBO,
                zoomStart = 16.0,
                zoomEnd = 18.0,
                bearingStart = 0.0,
                bearingEnd = 90.0,
                latEnd = -27.4616,
                lonEnd = 153.0466,
            ),
        )
        assertEquals(4, legs.size)
        val last = legs.last()
        assertEquals(-27.4616, last.target.lat, 1e-9)
        assertEquals(153.0466, last.target.lon, 1e-9)
        assertEquals(18.0, last.zoom, 1e-9)
        assertEquals(90.0, last.bearing, 1e-9)
    }

    @Test
    fun requestJsonRoundTripsAndRejectsMalformedPlans() {
        val original = request(
            buildingMode = BuildingDiagnosticMode.OPAQUE,
            settle = false,
            hideLayers = listOf("building-3d", "water"),
        )
        val parsed = MapPerfSweepRequest.fromJson(original.toJson())
        assertNotNull(parsed)
        assertEquals(original, parsed)
        assertEquals(listOf("building-3d", "water"), parsed!!.hideLayers)

        assertNull("missing request id", MapPerfSweepRequest.fromJson(original.toJson().with("requestId", 0)))
        assertNull("unknown motion", MapPerfSweepRequest.fromJson(original.toJson().with("motion", "teleport")))
        assertNull("unknown building mode", MapPerfSweepRequest.fromJson(original.toJson().with("buildingMode", "wireframe")))
        assertNull("latitude out of range", MapPerfSweepRequest.fromJson(original.toJson().with("lat", 95.0)))
        assertNull("zoom out of range", MapPerfSweepRequest.fromJson(original.toJson().with("zoomStart", 30.0)))
        assertNull("tilt out of range", MapPerfSweepRequest.fromJson(original.toJson().with("tilt", 70.0)))
        assertNull("absurd duration", MapPerfSweepRequest.fromJson(original.toJson().with("durationMs", 900_000L)))
        assertNull("blank label", MapPerfSweepRequest.fromJson(original.toJson().with("label", "  ")))
    }

    /** A modified copy, so a rejected field cannot leak into later assertions. */
    private fun JSONObject.with(key: String, value: Any?): JSONObject =
        JSONObject(toString()).put(key, value)

    @Test
    fun bearingHelpersNormalizeAndKeepFullTurns() {
        assertEquals(270.0, MapPerfSweepPlan.normalizeBearing(-90.0), 1e-9)
        assertEquals(90.0, MapPerfSweepPlan.normalizeBearing(450.0), 1e-9)
        assertEquals(360.0, MapPerfSweepPlan.shortestBearingDelta(0.0, 360.0), 1e-9)
        assertEquals(-360.0, MapPerfSweepPlan.shortestBearingDelta(10.0, -350.0), 1e-9)
        assertEquals(20.0, MapPerfSweepPlan.shortestBearingDelta(350.0, 10.0), 1e-9)
        assertEquals(0.0, MapPerfSweepPlan.shortestBearingDelta(12.0, 12.0), 1e-9)
    }

    @Test
    fun haversineMatchesTheBrisbaneCbdToHousesDistance() {
        val metres = MapPerfSweepPlan.haversineMeters(-27.4679, 153.0281, -27.4616, 153.0466)
        assertTrue("expected roughly 2 km, got $metres", metres in 1_800.0..2_100.0)
    }

    @Test
    fun statsReportPercentilesStallsAndFps() {
        // 16.7 ms cadence with two hitches: one 80 ms, one 160 ms.
        val frames = ArrayList<FrameSample>()
        var nanos = 1_000_000_000L
        val intervals = listOf(16.7, 16.7, 16.7, 16.7, 80.0, 16.7, 16.7, 160.0, 16.7, 16.7)
        frames.add(FrameSample(nanos, fullyRendered = true, encodingMs = 1.0, renderingMs = 2.0))
        for (interval in intervals) {
            nanos += (interval * 1_000_000.0).toLong()
            frames.add(FrameSample(nanos, fullyRendered = true, encodingMs = 1.0, renderingMs = 2.0))
        }
        val stats = FrameIntervalAggregator.stats(frames)
        assertEquals(11, stats.frames)
        assertEquals(2, stats.stalls50)
        assertEquals(1, stats.stalls100)
        assertEquals(11, stats.fullyRenderedFrames)
        assertEquals(16.7, stats.p50Ms, 0.01)
        assertTrue("p95 must see the 80 ms hitch: ${stats.p95Ms}", stats.p95Ms >= 70.0)
        assertEquals(160.0, stats.maxMs, 1e-6)
        assertTrue("fps should be far below 60 with two hitches: ${stats.fps}", stats.fps in 20.0..55.0)
        assertEquals(16.7, stats.minMs, 0.01)
        assertTrue(stats.meanMs > 16.7)
    }

    @Test
    fun statsHandleEmptyAndSingleFrameSeries() {
        assertEquals(FrameIntervalStats.EMPTY, FrameIntervalAggregator.stats(emptyList()))
        val single = FrameIntervalAggregator.stats(
            listOf(FrameSample(5L, fullyRendered = false, encodingMs = 0.0, renderingMs = 0.0)),
        )
        assertEquals(1, single.frames)
        assertEquals(0.0, single.fps, 0.0)
        assertEquals(0.0, single.p95Ms, 0.0)
        assertEquals(0, single.fullyRenderedFrames)
    }

    @Test
    fun coldWarmSplitBreaksAtTheFirstFullyRenderedFrame() {
        val frames = listOf(
            FrameSample(1L, fullyRendered = false, encodingMs = 0.0, renderingMs = 0.0),
            FrameSample(2L, fullyRendered = false, encodingMs = 0.0, renderingMs = 0.0),
            FrameSample(3L, fullyRendered = true, encodingMs = 0.0, renderingMs = 0.0),
            FrameSample(4L, fullyRendered = true, encodingMs = 0.0, renderingMs = 0.0),
            FrameSample(5L, fullyRendered = true, encodingMs = 0.0, renderingMs = 0.0),
        )
        val (cold, warm) = FrameIntervalAggregator.coldWarmSplit(frames)
        assertEquals(3, cold.size)
        assertEquals(2, warm.size)

        val neverComplete = frames.map { it.copy(fullyRendered = false) }
        val (allCold, noWarm) = FrameIntervalAggregator.coldWarmSplit(neverComplete)
        assertEquals(5, allCold.size)
        assertTrue(noWarm.isEmpty())

        val alreadyWarm = frames.map { it.copy(fullyRendered = true) }
        val (oneCold, restWarm) = FrameIntervalAggregator.coldWarmSplit(alreadyWarm)
        assertEquals(1, oneCold.size)
        assertEquals(4, restWarm.size)
    }

    @Test
    fun longestSteadyRunFindsTheStretchThatHeldCadence() {
        val frames = ArrayList<FrameSample>()
        var nanos = 0L
        val intervals = listOf(16.0, 16.0, 400.0, 16.0, 16.0, 16.0, 16.0, 16.0, 900.0, 16.0)
        frames.add(FrameSample(nanos, true, 0.0, 0.0))
        for (interval in intervals) {
            nanos += (interval * 1_000_000.0).toLong()
            frames.add(FrameSample(nanos, true, 0.0, 0.0))
        }
        assertEquals(6, FrameIntervalAggregator.longestSteadyRun(frames, 33.0))
        assertEquals(0, FrameIntervalAggregator.longestSteadyRun(emptyList(), 33.0))
    }

    @Test
    fun durationSeriesPercentilesUseTheValuesThemselves() {
        // Window frame metrics are per-frame durations, not timestamps: the
        // aggregates must not difference them.
        val stats = FrameIntervalAggregator.statsForDurations(
            listOf(16_000_000L, 33_000_000L, 200_000_000L),
        )
        assertEquals(3, stats.frames)
        assertEquals(249.0, stats.durationMs, 1e-6)
        assertEquals(16.0, stats.minMs, 1e-6)
        assertEquals(200.0, stats.maxMs, 1e-6)
        assertEquals(33.0, stats.p50Ms, 1e-6)
        assertEquals(1, stats.stalls50)
        assertEquals(1, stats.stalls100)
        assertTrue("mean duration drives the inverse fps: ${stats.fps}", stats.fps in 12.0..12.1)
        assertEquals(FrameIntervalStats.EMPTY, FrameIntervalAggregator.statsForDurations(emptyList()))
    }

    @Test
    fun percentileInterpolatesBetweenOrderStatistics() {
        val values = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(10.0, FrameIntervalAggregator.percentile(values, 0.0), 1e-9)
        assertEquals(40.0, FrameIntervalAggregator.percentile(values, 1.0), 1e-9)
        assertEquals(25.0, FrameIntervalAggregator.percentile(values, 0.5), 1e-9)
        assertEquals(0.0, FrameIntervalAggregator.percentile(emptyList(), 0.5), 1e-9)
    }

    @Test
    fun tileLodOverridesRoundTripAndAreValidated() {
        val lod = MapPerfTileLod(
            pitchThresholdRadians = Math.toRadians(45.0),
            minRadius = 3.0,
            scale = 1.0,
            zoomShift = -1.0,
        )
        val original = request(tileLod = lod)
        val parsed = MapPerfSweepRequest.fromJson(original.toJson())
        assertNotNull(parsed)
        assertEquals(original, parsed)
        assertEquals(lod, parsed!!.tileLod)
        assertNull("absent tile LOD stays absent", request().tileLod)

        // A malformed override must be dropped whole, never half-applied.
        assertNull(
            "threshold above PI",
            MapPerfTileLod.fromJson(JSONObject().put("pitchThresholdRadians", 4.0)),
        )
        assertNull(
            "minRadius below 1",
            MapPerfTileLod.fromJson(
                JSONObject().put("pitchThresholdRadians", 0.78).put("minRadius", 0.5),
            ),
        )
        assertNull(
            "non-positive scale",
            MapPerfTileLod.fromJson(
                JSONObject().put("pitchThresholdRadians", 0.78).put("scale", 0.0),
            ),
        )
        assertNull(
            "absurd zoom shift",
            MapPerfTileLod.fromJson(
                JSONObject().put("pitchThresholdRadians", 0.78).put("zoomShift", 4.0),
            ),
        )
        assertNull("empty object", MapPerfTileLod.fromJson(JSONObject()))
    }

    @Test
    fun renderStatsDerivePerFrameDeltasFromCumulativeCounters() {
        // MapLibre's buffer/texture counters run cumulatively since renderer
        // start; the report must publish deltas between consecutive samples,
        // never the mean of the running totals.
        val stats = listOf(
            RenderFrameStats(
                drawCalls = 10, totalDrawCalls = 5,
                bufferUpdates = 100, bufferUpdateBytes = 1_000,
                textureUpdates = 1, textureUpdateBytes = 100, createdTextures = 0,
                memBuffers = 50, memTextures = 20,
            ),
            RenderFrameStats(
                drawCalls = 20, totalDrawCalls = 6,
                bufferUpdates = 130, bufferUpdateBytes = 1_500,
                textureUpdates = 4, textureUpdateBytes = 400, createdTextures = 1,
                memBuffers = 60, memTextures = 25,
            ),
            RenderFrameStats(
                drawCalls = 30, totalDrawCalls = 7,
                bufferUpdates = 190, bufferUpdateBytes = 2_500,
                textureUpdates = 10, textureUpdateBytes = 900, createdTextures = 2,
                memBuffers = 70, memTextures = 30,
            ),
        )
        val json = RenderStatsAggregator.summarize(stats)
        assertEquals(3, json.getInt("frames"))
        assertEquals(2, json.getInt("perFrameSamples"))
        assertEquals(20.0, json.getDouble("drawCallsMean"), 1e-9)
        assertEquals(30, json.getInt("drawCallsMax"))
        // totalDrawCalls is cumulative as well, so it has per-frame deltas.
        assertEquals(1.0, json.getDouble("totalDrawCallsPerFrameMean"), 1e-9)
        assertEquals(1.0, json.getDouble("totalDrawCallsPerFrameMax"), 1e-9)
        assertEquals(7L, json.getLong("cumulativeTotalDrawCallsLast"))
        assertEquals(45.0, json.getDouble("bufferUpdatesPerFrameMean"), 1e-9)
        assertEquals(60.0, json.getDouble("bufferUpdatesPerFrameMax"), 1e-9)
        assertEquals(750.0, json.getDouble("bufferUpdateBytesPerFrameMean"), 1e-9)
        assertEquals(1_000.0, json.getDouble("bufferUpdateBytesPerFrameMax"), 1e-9)
        assertEquals(4.5, json.getDouble("textureUpdatesPerFrameMean"), 1e-9)
        assertEquals(1.0, json.getDouble("createdTexturesPerFrameMean"), 1e-9)
        assertEquals(190L, json.getLong("cumulativeBufferUpdatesLast"))
        assertEquals(2_500L, json.getLong("cumulativeBufferUpdateBytesLast"))
        assertEquals(70L, json.getLong("memBuffersMax"))
        assertTrue("scope must mark the cumulative counters", json.getString("scope").contains("cumulative"))
    }

    @Test
    fun renderStatsHandleSingleSampleResetsAndEmptiness() {
        val empty = RenderStatsAggregator.summarize(emptyList())
        assertEquals(0, empty.getInt("frames"))
        assertEquals(0, empty.getInt("perFrameSamples"))

        val single = RenderStatsAggregator.summarize(
            listOf(
                RenderFrameStats(7, 3, 42, 4_200, 2, 200, 1, 10, 10),
            ),
        )
        assertEquals(1, single.getInt("frames"))
        assertEquals(0, single.getInt("perFrameSamples"))
        assertEquals(0.0, single.getDouble("bufferUpdatesPerFrameMean"), 1e-9)
        assertEquals(42L, single.getLong("cumulativeBufferUpdatesLast"))
        assertEquals(3L, single.getLong("cumulativeTotalDrawCallsLast"))

        // A counter reset (never expected) must clamp to zero deltas instead
        // of publishing a negative upload.
        val reset = RenderStatsAggregator.summarize(
            listOf(
                RenderFrameStats(1, 1, 100, 1_000, 1, 100, 0, 1, 1),
                RenderFrameStats(1, 1, 50, 500, 0, 50, 0, 1, 1),
            ),
        )
        assertEquals(1, reset.getInt("perFrameSamples"))
        assertEquals(0.0, reset.getDouble("bufferUpdatesPerFrameMean"), 1e-9)
        assertEquals(0.0, reset.getDouble("bufferUpdateBytesPerFrameMean"), 1e-9)
    }

    @Test
    fun nativeFpsHarmonicMeanDiscardsTheFirstIdleInterval() {
        // Listener rates (1e9/delta) for a 40 ms pre-sweep idle interval, then
        // 20 ms, 10 ms, 10 ms: the elapsed average over the used three samples
        // is 75 fps, while the arithmetic mean of the reciprocal rates is not.
        val samples = listOf(25.0, 50.0, 100.0, 100.0)
        assertEquals(75.0, NativeFpsAggregator.harmonicMean(samples), 1e-6)
        val json = NativeFpsAggregator.summarize(samples)
        assertEquals(4, json.getInt("samples"))
        assertTrue(json.getBoolean("discardedFirst"))
        assertEquals(3, json.getInt("used"))
        assertEquals(75.0, json.getDouble("harmonic"), 1e-6)
        assertEquals(68.75, json.getDouble("arithmetic"), 1e-6)
        assertEquals(50.0, json.getDouble("min"), 1e-6)
        assertEquals(100.0, json.getDouble("max"), 1e-6)
        assertTrue("scope must explain the listener series", json.getString("scope").contains("not smoothed"))

        // A single sample is the only evidence available and is not discarded.
        val single = NativeFpsAggregator.summarize(listOf(30.0))
        assertEquals(1, single.getInt("samples"))
        assertEquals(false, single.getBoolean("discardedFirst"))
        assertEquals(30.0, single.getDouble("harmonic"), 1e-6)

        val none = NativeFpsAggregator.summarize(listOf(Double.NaN, -5.0))
        assertEquals(0, none.getInt("samples"))
        assertEquals(0.0, none.getDouble("harmonic"), 1e-9)
        assertEquals(0.0, NativeFpsAggregator.harmonicMean(emptyList()), 1e-9)
    }

    @Test
    fun statsJsonExposesTheReportedKeys() {
        val json = FrameIntervalAggregator.stats(
            listOf(
                FrameSample(0L, true, 1.0, 2.0),
                FrameSample(16_700_000L, true, 1.0, 2.0),
            ),
        ).toJson()
        for (key in listOf(
            "frames", "durationMs", "fps", "minMs", "meanMs", "p50Ms", "p95Ms", "p99Ms",
            "maxMs", "stalls50", "stalls100", "fullyRenderedFrames",
        )) {
            assertTrue("missing $key", json.has(key))
        }
    }
}
