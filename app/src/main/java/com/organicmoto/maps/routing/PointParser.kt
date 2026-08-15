package com.organicmoto.maps.routing

import com.graphhopper.util.shapes.GHPoint

object PointParser {

    fun parse(input: String): GHPoint {
        val parts = input.trim().split(",")
        require(parts.size == 2) { "Expected \"lat,lon\" but got \"$input\"" }
        val lat = parts[0].trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid latitude in \"$input\"")
        val lon = parts[1].trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Invalid longitude in \"$input\"")
        require(lat in -90.0..90.0) { "Latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "Longitude out of range: $lon" }
        return GHPoint(lat, lon)
    }
}
