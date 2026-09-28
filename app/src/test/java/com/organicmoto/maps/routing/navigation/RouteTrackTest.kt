package com.organicmoto.maps.routing.navigation

import com.graphhopper.util.Instruction
import com.graphhopper.ResponsePath
import com.graphhopper.util.InstructionList
import com.graphhopper.util.PointList
import com.graphhopper.util.RoundaboutInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteTrackTest {

    /** A straight 1 km north-south track with a turn at the midpoint. */
    private fun straightTrack(turnSigns: List<Int> = emptyList()): RouteTrack {
        // 11 vertices, 100 m apart, going south (decreasing latitude).
        val n = 11
        val latitudes = DoubleArray(n) { -27.0 - it * 0.0009 }
        val longitudes = DoubleArray(n) { 153.0 }
        val signs = mutableListOf<Int>()
        val names = mutableListOf<String>()
        val counts = mutableListOf<Int>()
        val times = mutableListOf<Double>()
        signs += 0; names += "origin"
        val per = (n - 1) / (turnSigns.size + 1)
        counts += per; times += per * 6.0
        var remaining = n - 1 - per
        for (i in 0..turnSigns.size) {
            val count = if (i < turnSigns.size) per else remaining
            signs += if (i < turnSigns.size) turnSigns[i] else 0
            names += "seg$i"
            counts += count
            times += count * 6.0
            remaining -= count
        }
        return RouteTrack.fromPath(latitudes, longitudes, signs, names, counts, times)
    }
    @Test
    fun `track computes total distance close to geometric length`() {
        val track = straightTrack()
        assertEquals(1000.0, track.totalDistanceM, 5.0)
    }

    @Test
    fun `remaining distance and time shrink monotonically along the track`() {
        val track = straightTrack()
        var lastD = Double.MAX_VALUE
        var lastT = Double.MAX_VALUE
        var offset = 0.0
        while (offset <= track.totalDistanceM) {
            val d = track.remainingDistanceM(offset)
            val t = track.remainingTimeS(offset)
            assertTrue(d <= lastD)
            assertTrue(t <= lastT)
            lastD = d; lastT = t
            offset += 50.0
        }
        assertEquals(0.0, track.remainingDistanceM(track.totalDistanceM), 1e-6)
        assertEquals(0.0, track.remainingTimeS(track.totalDistanceM), 1.0)
    }

    @Test
    fun `nextTurn finds the single turn with its distance`() {
        val track = straightTrack(listOf(-2)) // left turn at the midpoint vertex
        assertTrue(track.turns.isNotEmpty())
        val (turn, distance) = track.nextTurn(0.0)!!
        assertEquals(-2, turn.sign)
        assertEquals(500.0, distance, 60.0)
        // Past the turn: none remains.
        assertNull(track.nextTurn(track.totalDistanceM + 1.0))
    }

    @Test
    fun `synthetic instructions are excluded from turns`() {
        val track = straightTrack(listOf(4)) // FINISH is not a real turn
        assertFalse(track.turns.any { RouteTrack.isRealTurn(it.sign) })
    }

    @Test
    fun `locate returns segment index and fraction`() {
        val track = straightTrack()
        val (index, fraction) = track.locate(550.0)
        assertTrue(index in 0 until track.vertexCount - 1)
        assertTrue(fraction in 0.0..1.0)
        // Reconstructing the offset from the locate result lands close.
        val reconstructed = track.cumulativeDistanceM[index] +
            fraction * (track.cumulativeDistanceM[index + 1] - track.cumulativeDistanceM[index])
        assertTrue(abs(reconstructed - 550.0) < 1.0)
    }

    @Test
    fun `instruction time includes the segment leading to the next instruction`() {
        val track = RouteTrack.fromPath(
            latitudes = doubleArrayOf(0.0, 0.0, 0.0),
            longitudes = doubleArrayOf(0.0, 0.001, 0.002),
            instructionSigns = listOf(0, 2, 4),
            instructionNames = listOf("depart", "turn", "finish"),
            instructionPointCounts = listOf(1, 1, 1),
            instructionTimesS = listOf(10.0, 30.0, 0.0),
        )

        assertEquals(30.0, track.remainingTimeS(track.cumulativeDistanceM[1]), 1e-6)
        assertEquals(0.0, track.remainingTimeS(track.totalDistanceM), 1e-6)
    }

    @Test
    fun `slight turn keeps its measured route deflection`() {
        val track = RouteTrack.fromPath(
            latitudes = doubleArrayOf(0.0, 0.001, 0.002, 0.003),
            longitudes = doubleArrayOf(0.0, 0.0, 0.0003, 0.0006),
            instructionSigns = listOf(
                Instruction.CONTINUE_ON_STREET,
                Instruction.TURN_SLIGHT_RIGHT,
                Instruction.FINISH,
            ),
            instructionNames = listOf("approach", "veer", "finish"),
            instructionPointCounts = listOf(1, 2, 1),
            instructionTimesS = listOf(10.0, 20.0, 0.0),
        )

        assertEquals(17.0, track.turns.single().turnAngleDeg!!, 2.0)
    }

    @Test
    fun `signed bearing delta wraps across north`() {
        assertEquals(20.0, GeoMath.signedBearingDeltaDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, GeoMath.signedBearingDeltaDeg(10.0, 350.0), 1e-9)
    }

    @Test
    fun `roundabout instruction keeps its selected exit number`() {
        val track = RouteTrack.fromPath(
            latitudes = doubleArrayOf(0.0, 0.001, 0.002, 0.003),
            longitudes = doubleArrayOf(0.0, 0.0, 0.0001, 0.0002),
            instructionSigns = listOf(
                Instruction.CONTINUE_ON_STREET,
                Instruction.USE_ROUNDABOUT,
                Instruction.FINISH,
            ),
            instructionNames = listOf("approach", "exit road", "finish"),
            instructionPointCounts = listOf(1, 2, 1),
            instructionTimesS = listOf(10.0, 20.0, 0.0),
            instructionRoundaboutExitNumbers = listOf(null, 3, null),
            instructionRoundaboutClockwise = listOf(null, true, null),
        )

        assertEquals(3, track.turns.single().roundaboutExitNumber)
        assertEquals(true, track.turns.single().roundaboutClockwise)
    }

    @Test
    fun `path factory reads the chosen roundabout exit from GraphHopper`() {
        val routePoints = PointList().apply {
            add(0.0, 0.0)
            add(0.001, 0.0)
            add(0.002, 0.0001)
            add(0.003, 0.0002)
        }
        val approachPoints = PointList().apply { add(0.0, 0.0) }
        val roundaboutPoints = PointList().apply {
            add(0.001, 0.0)
            add(0.002, 0.0001)
        }
        val finishPoints = PointList().apply { add(0.003, 0.0002) }
        val roundabout = RoundaboutInstruction(
            Instruction.USE_ROUNDABOUT,
            "Abbotsford Road",
            roundaboutPoints,
        ).setExitNumber(3).setExited().setDirOfRotation(1.0).setRadian(0.5).setTime(20_000L)
        val instructions = InstructionList(null).apply {
            add(Instruction(Instruction.CONTINUE_ON_STREET, "Approach", approachPoints).setTime(10_000L))
            add(roundabout)
            add(Instruction(Instruction.FINISH, "", finishPoints))
        }
        val path = ResponsePath()
            .setPoints(routePoints)
            .also { it.instructions = instructions }

        val track = RouteTrackFactory.fromPath(path)

        assertEquals(3, track.turns.single().roundaboutExitNumber)
        assertEquals("Abbotsford Road", track.turns.single().streetName)
        assertEquals(true, track.turns.single().roundaboutClockwise)
    }

    @Test
    fun `path factory preserves counterclockwise roundabout direction`() {
        val routePoints = PointList().apply {
            add(0.0, 0.0)
            add(0.001, 0.0)
            add(0.002, 0.0001)
            add(0.003, 0.0002)
        }
        val approachPoints = PointList().apply { add(0.0, 0.0) }
        val roundaboutPoints = PointList().apply {
            add(0.001, 0.0)
            add(0.002, 0.0001)
        }
        val finishPoints = PointList().apply { add(0.003, 0.0002) }
        val roundabout = RoundaboutInstruction(
            Instruction.USE_ROUNDABOUT,
            "Abbotsford Road",
            roundaboutPoints,
        ).setExitNumber(3).setExited().setDirOfRotation(-1.0).setRadian(-0.5).setTime(20_000L)
        val instructions = InstructionList(null).apply {
            add(Instruction(Instruction.CONTINUE_ON_STREET, "Approach", approachPoints).setTime(10_000L))
            add(roundabout)
            add(Instruction(Instruction.FINISH, "", finishPoints))
        }
        val path = ResponsePath()
            .setPoints(routePoints)
            .also { it.instructions = instructions }

        val track = RouteTrackFactory.fromPath(path)

        assertEquals(false, track.turns.single().roundaboutClockwise)
    }
}
