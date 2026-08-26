package com.organicmoto.maps.routing

import com.graphhopper.util.shapes.GHPoint

/** A stable corridor used for on-device routing regression checks. */
data class RouteCorpusEntry(
    val name: String,
    val from: GHPoint,
    val to: GHPoint,
    val baselineKm: Double,
    val baselineHours: Double,
    val alternativesExpected: Boolean,
)

internal val ROUTE_CORPUS: List<RouteCorpusEntry> = listOf(
    RouteCorpusEntry(
        "Brisbane CBD to Mount Glorious",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-27.3353, 152.7720),
        baselineKm = 40.0,
        baselineHours = 1.0,
        alternativesExpected = true,
    ),
    RouteCorpusEntry(
        "Brisbane CBD to Cairns",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-16.9203, 145.7710),
        baselineKm = 1_700.0,
        baselineHours = 24.0,
        alternativesExpected = false,
    ),
    RouteCorpusEntry(
        "Samford to Brisbane CBD",
        GHPoint(-27.3727, 152.8864),
        GHPoint(-27.4698, 153.0251),
        baselineKm = 35.0,
        baselineHours = 0.75,
        alternativesExpected = true,
    ),
    RouteCorpusEntry(
        "Toowoomba to Warwick",
        GHPoint(-27.5598, 151.9507),
        GHPoint(-28.2167, 152.0333),
        baselineKm = 160.0,
        baselineHours = 2.0,
        alternativesExpected = true,
    ),
    RouteCorpusEntry(
        "Gympie to Noosa",
        GHPoint(-26.1900, 152.6650),
        GHPoint(-26.3980, 153.0600),
        baselineKm = 80.0,
        baselineHours = 1.3,
        alternativesExpected = true,
    ),
    RouteCorpusEntry(
        "Tamborine Mountain to Springbrook",
        GHPoint(-27.9797, 153.1878),
        GHPoint(-28.2270, 153.2700),
        baselineKm = 55.0,
        baselineHours = 1.2,
        alternativesExpected = true,
    ),
    RouteCorpusEntry(
        "Mount Isa to Townsville",
        GHPoint(-20.7253, 139.4927),
        GHPoint(-19.2589, 146.8169),
        baselineKm = 900.0,
        baselineHours = 11.0,
        alternativesExpected = false,
    ),
    RouteCorpusEntry(
        "Charleville to Longreach",
        GHPoint(-26.4017, 146.2422),
        GHPoint(-23.4420, 144.2490),
        baselineKm = 420.0,
        baselineHours = 5.5,
        alternativesExpected = false,
    ),
    RouteCorpusEntry(
        "Brisbane CBD to Toowoomba",
        GHPoint(-27.4698, 153.0251),
        GHPoint(-27.5598, 151.9507),
        baselineKm = 125.0,
        baselineHours = 1.8,
        alternativesExpected = true,
    ),
    RouteCorpusEntry(
        "Cairns to Port Douglas",
        GHPoint(-16.9203, 145.7710),
        GHPoint(-16.4840, 145.4610),
        baselineKm = 70.0,
        baselineHours = 1.1,
        alternativesExpected = true,
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
