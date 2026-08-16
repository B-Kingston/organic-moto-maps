package com.graphhopper.routing.weighting.custom;

import com.graphhopper.routing.weighting.custom.CustomWeightingHelper;
import com.graphhopper.routing.ev.EncodedValueLookup;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.routing.ev.*;
import java.util.Map;
import com.graphhopper.util.CustomModel;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.routing.ev.EdgeIntAccess;

/**
 * Pre-compiled replacement for the Janino-generated weighting helper.
 *
 * GraphHopper normally compiles the motorcycle custom model into a
 * CustomWeightingHelper subclass at load time using Janino. Janino emits JVM
 * bytecode, which ART cannot load, so routing fails on Android. This class is
 * that same generated source, dumped by GraphHopper itself on the desktop JVM
 * (tools/gh/generate-weighting.sh + tools/gh/GenerateWeighting.java with
 * -Dorg.codehaus.janino.source_debugging.enable=true) and shipped as a normal
 * compiled class.
 *
 * REGENERATION: if the motorcycle custom model statements change, re-import the
 * graph, re-run ./tools/gh/generate-weighting.sh, and replace this file's body
 * with the dumped JaninoCustomWeightingHelperSubclassN.java content (keeping
 * this class name). The canonical model is tools/gh/motorcycle.json (reference
 * copy of the JAR's built-in motorcycle model); GraphHopperRouter.motorcycleProfile(),
 * GenerateWeighting.motorcycleProfile(), this file and the max-speed math in
 * MotorcycleWeightingFactory must all change together with it — generate-weighting.sh
 * verifies the first three agree.
 */
public class MotorcycleWeightingHelper extends CustomWeightingHelper {

    @Override public void init(CustomModel customModel, EncodedValueLookup lookup, Map<String, com.graphhopper.util.JsonFeature> areas) {
        this.lookup = lookup;
        this.customModel = customModel;
        this.surface_enc = (EnumEncodedValue) lookup.getEncodedValue("surface", EncodedValue.class);
        this.road_access_enc = (EnumEncodedValue) lookup.getEncodedValue("road_access", EncodedValue.class);
        this.road_class_enc = (EnumEncodedValue) lookup.getEncodedValue("road_class", EncodedValue.class);
        this.car_average_speed_enc = (DecimalEncodedValue) lookup.getEncodedValue("car_average_speed", EncodedValue.class);
        this.car_access_enc = (BooleanEncodedValue) lookup.getEncodedValue("car_access", EncodedValue.class);
        this.track_type_enc = (EnumEncodedValue) lookup.getEncodedValue("track_type", EncodedValue.class);
    }

    @Override public double getPriority(EdgeIteratorState edge, boolean reverse) {
        double value = 1.0;
        boolean car_access = (boolean) (reverse ? edge.getReverse((BooleanEncodedValue) this.car_access_enc) : edge.get((BooleanEncodedValue) this.car_access_enc));
        TrackType track_type = (TrackType) (reverse ? edge.getReverse((EnumEncodedValue) this.track_type_enc) : edge.get((EnumEncodedValue) this.track_type_enc));
        RoadAccess road_access = (RoadAccess) (reverse ? edge.getReverse((EnumEncodedValue) this.road_access_enc) : edge.get((EnumEncodedValue) this.road_access_enc));
        RoadClass road_class = (RoadClass) (reverse ? edge.getReverse((EnumEncodedValue) this.road_class_enc) : edge.get((EnumEncodedValue) this.road_class_enc));

        if (!car_access) {
            value *= 0;
        }
        if (track_type.ordinal() > 1) {
            value *= 0;
        }
        if (road_access == RoadAccess.PRIVATE) {
            value *= 0;
        }
        if (road_access == RoadAccess.DESTINATION) {
            value *= 0.1;
        }
        if (road_class == RoadClass.MOTORWAY || road_class == RoadClass.TRUNK) {
            value *= 0.1;
        }
        return value;
    }

    @Override public double getSpeed(EdgeIteratorState edge, boolean reverse) {
        double value = 999.0;
        double car_average_speed = (double) (reverse ? edge.getReverse((DecimalEncodedValue) this.car_average_speed_enc) : edge.get((DecimalEncodedValue) this.car_average_speed_enc));
        Surface surface = (Surface) (reverse ? edge.getReverse((EnumEncodedValue) this.surface_enc) : edge.get((EnumEncodedValue) this.surface_enc));

        if (true) {
            value = Math.min(value, 0.9 * car_average_speed);
        }
        if (true) {
            value = Math.min(value, 120);
        }
        if (surface == Surface.COBBLESTONE || surface == Surface.GRASS || surface == Surface.GRAVEL || surface == Surface.SAND || surface == Surface.PAVING_STONES || surface == Surface.DIRT || surface == Surface.GROUND || surface == Surface.UNPAVED || surface == Surface.COMPACTED) {
            value = Math.min(value, 30);
        }
        return value;
    }

    @Override public double getTurnPenalty(
        BaseGraph graph,
        EdgeIntAccess edgeIntAccess,
        int inEdge,
        int viaNode,
        int outEdge
    ) {
        double value = 0;

        return value;
    }

    protected EnumEncodedValue surface_enc;

    protected EnumEncodedValue road_access_enc;

    protected EnumEncodedValue road_class_enc;

    protected DecimalEncodedValue car_average_speed_enc;

    protected BooleanEncodedValue car_access_enc;

    protected EnumEncodedValue track_type_enc;
}
