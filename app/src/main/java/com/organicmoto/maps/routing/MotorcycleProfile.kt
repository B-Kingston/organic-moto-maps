package com.organicmoto.maps.routing

import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.util.CustomModel

/** GraphHopper profile name shared by import, load, and route requests. */
internal const val MOTORCYCLE_PROFILE = "motorcycle"

/**
 * Rebuilds the import-time motorcycle profile bit-for-bit.
 *
 * The stored graph was imported with `custom_model_files: [motorcycle.json]`.
 * GraphHopper resolves that name to its built-in model. The hint insertion
 * order is part of the stored profile hash. Keep these operations in order.
 */
internal fun motorcycleProfile(): Profile {
    val customModel = CustomModel().apply {
        setDistanceInfluence(90.0)
        addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
        addToPriority(Statement.If("track_type.ordinal() > 1", Statement.Op.MULTIPLY, "0"))
        addToPriority(Statement.If("road_access == PRIVATE", Statement.Op.MULTIPLY, "0"))
        addToPriority(Statement.If("road_access == DESTINATION", Statement.Op.MULTIPLY, "0.1"))
        addToPriority(
            Statement.If("road_class == MOTORWAY || road_class == TRUNK", Statement.Op.MULTIPLY, "0.1")
        )
        addToSpeed(Statement.If("true", Statement.Op.LIMIT, "0.9 * car_average_speed"))
        addToSpeed(Statement.If("true", Statement.Op.LIMIT, "120"))
        addToSpeed(
            Statement.If(
                "surface==COBBLESTONE || surface==GRASS || surface==GRAVEL || surface==SAND || " +
                    "surface==PAVING_STONES || surface==DIRT || surface==GROUND || " +
                    "surface==UNPAVED || surface==COMPACTED",
                Statement.Op.LIMIT,
                "30",
            ),
        )
    }
    return Profile(MOTORCYCLE_PROFILE).apply {
        hints.remove("custom_model")
        putHint("custom_model_files", listOf("motorcycle.json"))
        setCustomModel(customModel)
    }
}
