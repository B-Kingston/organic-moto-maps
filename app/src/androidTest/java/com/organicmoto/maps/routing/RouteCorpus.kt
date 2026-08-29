package com.organicmoto.maps.routing

import com.graphhopper.util.shapes.GHPoint

/** A stable corridor used for on-device routing regression checks.
 *
 *  [goldDistanceMeters] / [goldDurationMillis] are committed fastest-route
 *  measurements (detent 0) used by the ±15% drift alert in
 *  [RouteCorpusTest.fastestRoutesMatchCommittedGoldBaselines]. They sit much
 *  tighter than the wide [baselineKm]/[baselineHours] sanity bands and are
 *  recalibrated via the @Ignore calibrationProbe whenever OSM data or the
 *  weighting model changes deliberately. */
data class RouteCorpusEntry(
    val name: String,
    val from: GHPoint,
    val to: GHPoint,
    val baselineKm: Double,
    val baselineHours: Double,
    val alternativesExpected: Boolean,
    val goldDistanceMeters: Double? = null,
    val goldDurationMillis: Long? = null,
)

internal val ROUTE_CORPUS: List<RouteCorpusEntry> = listOf(
    RouteCorpusEntry(
        "Brisbane CBD to Mount Glorious",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-27.3353, 152.7720),
        baselineKm = 40.0,
        baselineHours = 1.0,
        alternativesExpected = true,
        goldDistanceMeters = 39_223.5,
        goldDurationMillis = 2_709_689L,
    ),
    RouteCorpusEntry(
        "Brisbane CBD to Cairns",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-16.9203, 145.7710),
        baselineKm = 1_700.0,
        baselineHours = 24.0,
        alternativesExpected = false,
        goldDistanceMeters = 1_696_560.4,
        goldDurationMillis = 78_276_822L,
    ),
    RouteCorpusEntry(
        "Samford to Brisbane CBD",
        GHPoint(-27.3727, 152.8864),
        GHPoint(-27.4698, 153.0251),
        baselineKm = 35.0,
        baselineHours = 0.75,
        alternativesExpected = true,
        goldDistanceMeters = 21_248.2,
        goldDurationMillis = 1_567_504L,
    ),
    RouteCorpusEntry(
        "Toowoomba to Warwick",
        GHPoint(-27.5598, 151.9507),
        GHPoint(-28.2167, 152.0333),
        baselineKm = 160.0,
        baselineHours = 2.0,
        alternativesExpected = true,
        goldDistanceMeters = 83_349.0,
        goldDurationMillis = 4_221_600L,
    ),
    RouteCorpusEntry(
        "Gympie to Noosa",
        GHPoint(-26.1900, 152.6650),
        GHPoint(-26.3980, 153.0600),
        baselineKm = 80.0,
        baselineHours = 1.3,
        alternativesExpected = true,
        goldDistanceMeters = 58_528.4,
        goldDurationMillis = 3_043_587L,
    ),
    RouteCorpusEntry(
        "Tamborine Mountain to Springbrook",
        GHPoint(-27.9797, 153.1878),
        GHPoint(-28.2270, 153.2700),
        baselineKm = 55.0,
        baselineHours = 1.2,
        alternativesExpected = true,
        goldDistanceMeters = 51_606.2,
        goldDurationMillis = 3_589_159L,
    ),
    RouteCorpusEntry(
        "Mount Isa to Townsville",
        GHPoint(-20.7253, 139.4927),
        GHPoint(-19.2589, 146.8169),
        baselineKm = 900.0,
        baselineHours = 11.0,
        alternativesExpected = false,
        goldDistanceMeters = 904_629.6,
        goldDurationMillis = 39_260_050L,
    ),
    RouteCorpusEntry(
        "Charleville to Longreach",
        GHPoint(-26.4017, 146.2422),
        GHPoint(-23.4420, 144.2490),
        baselineKm = 420.0,
        baselineHours = 5.5,
        alternativesExpected = false,
        goldDistanceMeters = 516_121.6,
        goldDurationMillis = 21_440_774L,
    ),
    RouteCorpusEntry(
        "Brisbane CBD to Toowoomba",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-27.5598, 151.9507),
        baselineKm = 125.0,
        baselineHours = 1.8,
        alternativesExpected = true,
        goldDistanceMeters = 125_774.8,
        goldDurationMillis = 6_216_179L,
    ),
    RouteCorpusEntry(
        "Cairns to Port Douglas",
        GHPoint(-16.9203, 145.7710),
        GHPoint(-16.4840, 145.4610),
        baselineKm = 70.0,
        baselineHours = 1.1,
        alternativesExpected = true,
        goldDistanceMeters = 66_188.8,
        goldDurationMillis = 3_885_018L,
    ),
    RouteCorpusEntry(
        "Identical Brisbane endpoints",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-27.4698, 153.0251),
        baselineKm = 0.0,
        baselineHours = 0.0,
        alternativesExpected = false,
    ),
    RouteCorpusEntry(
        "Sydney to Brisbane CBD",
        GHPoint(-33.8688, 151.2093),
        GHPoint(-27.4698, 153.0251),
        baselineKm = 0.0,
        baselineHours = 0.0,
        alternativesExpected = false,
    ),
)
