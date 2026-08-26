package com.organicmoto.maps.routing

import com.graphhopper.routing.ev.DecimalEncodedValueImpl
import com.graphhopper.routing.ev.EnumEncodedValue
import com.graphhopper.routing.ev.RoadAccess
import com.graphhopper.routing.ev.RoadClass
import com.graphhopper.routing.ev.SimpleBooleanEncodedValue
import com.graphhopper.routing.ev.Surface
import com.graphhopper.routing.ev.TrackType
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.custom.MotorcycleWeightingHelper
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.CustomModel
import com.graphhopper.util.EdgeIteratorState
import org.junit.Assert.assertEquals
import org.junit.Test

class MotorcycleWeightingHelperTest {

    @Test
    fun speedUsesCanonicalCapsAndRoughSurfaceLimit() {
        val fixture = fixture()
        val helper = helper(fixture)
        assertEquals(90.0, helper.getSpeed(fixture.edge(avgSpeed = 100.0), false), 0.0)
        assertEquals(120.0, helper.getSpeed(fixture.edge(avgSpeed = 200.0), false), 0.0)
        listOf(
            Surface.COBBLESTONE,
            Surface.GRASS,
            Surface.GRAVEL,
            Surface.SAND,
            Surface.PAVING_STONES,
            Surface.DIRT,
            Surface.GROUND,
            Surface.UNPAVED,
            Surface.COMPACTED,
        ).forEach { surface ->
            assertEquals(
                surface.toString(),
                30.0,
                helper.getSpeed(fixture.edge(avgSpeed = 100.0, surface = surface), false),
                0.0,
            )
        }
        assertEquals(90.0, helper.getSpeed(fixture.edge(avgSpeed = 100.0, surface = Surface.ASPHALT), false), 0.0)
    }

    @Test
    fun priorityMatchesCanonicalAccessRoadAndTrackRules() {
        val fixture = fixture()
        val helper = helper(fixture)
        assertEquals(1.0, helper.getPriority(fixture.edge(), false), 0.0)
        assertEquals(0.0, helper.getPriority(fixture.edge(carAccess = false), false), 0.0)
        assertEquals(0.0, helper.getPriority(fixture.edge(trackType = TrackType.GRADE2), false), 0.0)
        assertEquals(0.0, helper.getPriority(fixture.edge(roadAccess = RoadAccess.PRIVATE), false), 0.0)
        assertEquals(0.1, helper.getPriority(fixture.edge(roadAccess = RoadAccess.DESTINATION), false), 0.0)
        assertEquals(0.1, helper.getPriority(fixture.edge(roadClass = RoadClass.MOTORWAY), false), 0.0)
        assertEquals(0.1, helper.getPriority(fixture.edge(roadClass = RoadClass.TRUNK), false), 0.0)
        assertEquals(0.0, helper.getTurnPenalty(fixture.graph, fixture.graph.edgeAccess, 0, 0, 0), 0.0)
    }

    private fun helper(fixture: Fixture): MotorcycleWeightingHelper =
        MotorcycleWeightingHelper().also { it.init(CustomModel(), fixture.encodingManager, emptyMap()) }

    private fun fixture(): Fixture {
        val access = SimpleBooleanEncodedValue("car_access", true)
        val trackType = EnumEncodedValue("track_type", TrackType::class.java, true)
        val roadAccess = EnumEncodedValue("road_access", RoadAccess::class.java, true)
        val roadClass = EnumEncodedValue("road_class", RoadClass::class.java, true)
        val averageSpeed = DecimalEncodedValueImpl("car_average_speed", 8, 1.0, true)
        val surface = EnumEncodedValue("surface", Surface::class.java, true)
        val encodingManager = EncodingManager.start()
            .add(access)
            .add(trackType)
            .add(roadAccess)
            .add(roadClass)
            .add(averageSpeed)
            .add(surface)
            .build()
        val graph = BaseGraph.Builder(encodingManager).create()
        graph.nodeAccess.setNode(0, 0.0, 0.0)
        graph.nodeAccess.setNode(1, 0.0, 1.0)
        return Fixture(graph, encodingManager, access, trackType, roadAccess, roadClass, averageSpeed, surface)
    }

    private data class Fixture(
        val graph: BaseGraph,
        val encodingManager: EncodingManager,
        val access: SimpleBooleanEncodedValue,
        val trackType: EnumEncodedValue<TrackType>,
        val roadAccess: EnumEncodedValue<RoadAccess>,
        val roadClass: EnumEncodedValue<RoadClass>,
        val averageSpeed: DecimalEncodedValueImpl,
        val surface: EnumEncodedValue<Surface>,
    ) {
        fun edge(
            carAccess: Boolean = true,
            trackType: TrackType = TrackType.GRADE1,
            roadAccess: RoadAccess = RoadAccess.YES,
            roadClass: RoadClass = RoadClass.PRIMARY,
            avgSpeed: Double = 100.0,
            surface: Surface = Surface.ASPHALT,
        ): EdgeIteratorState = graph.edge(0, 1)
            .setDistance(1_000.0)
            .setWayGeometry(com.graphhopper.util.PointList().apply { add(0.0, 0.5) })
            .set(access, carAccess)
            .setReverse(access, carAccess)
            .set(this.trackType, trackType)
            .set(this.roadAccess, roadAccess)
            .set(this.roadClass, roadClass)
            .set(this.averageSpeed, avgSpeed)
            .setReverse(this.averageSpeed, avgSpeed)
            .set(this.surface, surface)
            .setReverse(this.surface, surface)
    }
}
