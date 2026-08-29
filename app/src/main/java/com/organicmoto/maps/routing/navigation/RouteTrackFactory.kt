package com.organicmoto.maps.routing.navigation

import com.graphhopper.ResponsePath

/**
 * Builds a [RouteTrack] from a GraphHopper [ResponsePath].
 *
 * Instruction point counts are derived from each instruction's geometry
 * length; GraphHopper instruction points are contiguous slices of the path
 * geometry. Pure JVM so unit tests can drive it directly.
 */
object RouteTrackFactory {

    fun fromPath(path: ResponsePath): RouteTrack {
        val points = path.points
        val latitudes = DoubleArray(points.size())
        val longitudes = DoubleArray(points.size())
        for (i in 0 until points.size()) {
            latitudes[i] = points.getLat(i)
            longitudes[i] = points.getLon(i)
        }
        val instructions = path.instructions
        val signs = mutableListOf<Int>()
        val names = mutableListOf<String>()
        val pointCounts = mutableListOf<Int>()
        val distances = mutableListOf<Double>()
        val times = mutableListOf<Double>()
        for (i in 0 until instructions.size) {
            val instruction = instructions[i]
            signs += instruction.sign
            names += instruction.name ?: ""
            pointCounts += instruction.points.size()
            distances += instruction.distance
            times += instruction.time / 1000.0
        }
        return RouteTrack.fromPath(
            latitudes, longitudes, signs, names, pointCounts, distances, times,
        )
    }
}
