package com.organicmoto.maps.routing.navigation

import com.graphhopper.ResponsePath
import com.graphhopper.util.RoundaboutInstruction

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
        val times = mutableListOf<Double>()
        val roundaboutExitNumbers = mutableListOf<Int?>()
        val roundaboutClockwise = mutableListOf<Boolean?>()
        for (i in 0 until instructions.size) {
            val instruction = instructions[i]
            signs += instruction.sign
            names += instruction.name ?: ""
            pointCounts += instruction.points.size()
            times += instruction.time / 1000.0
            val roundabout = instruction as? RoundaboutInstruction
            roundaboutExitNumbers += roundabout
                ?.takeIf { it.isExited }
                ?.exitNumber
            roundaboutClockwise += roundabout
                ?.takeIf { it.isExited }
                ?.turnAngle
                ?.takeIf { it.isFinite() }
                ?.let { it > 0.0 }
        }
        return RouteTrack.fromPath(
            latitudes, longitudes, signs, names, pointCounts, times,
            roundaboutExitNumbers, roundaboutClockwise,
        )
    }
}
