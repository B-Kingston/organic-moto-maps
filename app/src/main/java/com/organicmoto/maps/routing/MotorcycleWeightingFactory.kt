package com.organicmoto.maps.routing

import com.graphhopper.config.Profile
import com.graphhopper.routing.DefaultWeightingFactory
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.ev.EncodedValue
import com.graphhopper.routing.ev.EncodedValueLookup
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.util.EncodingManager
import com.graphhopper.routing.weighting.DefaultTurnCostProvider
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.Weighting
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.routing.weighting.custom.CustomWeighting.MaxCalc
import com.graphhopper.routing.weighting.custom.MotorcycleWeightingHelper
import com.graphhopper.storage.BaseGraph
import com.graphhopper.util.CustomModel
import com.graphhopper.util.PMap
import com.graphhopper.util.Parameters
import com.graphhopper.util.TurnCostsConfig

/**
 * WeightingFactory that sidesteps Janino for the motorcycle custom weighting.
 *
 * The default [DefaultWeightingFactory] builds custom weightings through
 * CustomModelParser, which compiles the custom model expressions with Janino
 * into JVM bytecode at load time. ART cannot load that bytecode, so routing
 * fails on Android. This factory mirrors DefaultWeightingFactory's custom
 * branch for the `motorcycle` profile only, assembling
 * [CustomWeighting.Parameters] from the pre-compiled [MotorcycleWeightingHelper]
 * instead.
 *
 * Any other profile is delegated to the default factory: non-custom weightings
 * are safe (they do not involve Janino), while custom weightings on other
 * profiles are rejected with a clear error instead of silently applying the
 * motorcycle model — this build only ships the pre-compiled motorcycle helper.
 */
class MotorcycleWeightingFactory(
    private val graph: BaseGraph,
    private val encodingManager: EncodingManager,
) : WeightingFactory {

    private val delegate by lazy { DefaultWeightingFactory(graph, encodingManager) }

    override fun createWeighting(profile: Profile, hints: PMap, disableTurnCosts: Boolean): Weighting {
        // The pre-compiled helper implements exactly one model: the motorcycle
        // profile's. Never apply it to any other profile.
        if (profile.name != MOTORCYCLE_PROFILE) {
            if (CustomWeighting.NAME.equals(profile.weighting, ignoreCase = true))
                throw IllegalArgumentException(
                    "No pre-compiled weighting helper for profile '${profile.name}': " +
                        "this build only ships the motorcycle custom model (ART cannot load " +
                        "Janino-compiled custom weightings)"
                )
            return delegate.createWeighting(profile, hints, disableTurnCosts)
        }
        if (!CustomWeighting.NAME.equals(profile.weighting, ignoreCase = true))
            return delegate.createWeighting(profile, hints, disableTurnCosts)

        // Mirrors DefaultWeightingFactory.createWeighting's custom branch.
        val queryCustomModel = hints.getObject(CustomModel.KEY, null as CustomModel?)
        if (queryCustomModel != null)
            throw IllegalArgumentException(
                "Request-level custom model overrides are not supported on Android: " +
                    "the pre-compiled weighting helper only implements the motorcycle profile model"
            )
        val turnCostsConfig = profile.turnCostsConfig
        if (turnCostsConfig != null && !turnCostsConfig.isAllowTurnPenaltyInRequest &&
            queryCustomModel != null && queryCustomModel.turnPenalty.isNotEmpty()
        )
            throw IllegalArgumentException(
                "The turn_penalty feature is not supported per request for ${profile.name}. " +
                    "Set 'allow_turn_penalty_in_request' to true in the 'turn_costs' option in the config.yml."
            )

        val mergedCustomModel = CustomModel.merge(profile.customModel, queryCustomModel)
        if (hints.has(Parameters.Routing.HEADING_PENALTY))
            mergedCustomModel.headingPenalty = hints.getDouble(
                Parameters.Routing.HEADING_PENALTY,
                Parameters.Routing.DEFAULT_HEADING_PENALTY,
            )

        val parameters = createParameters(mergedCustomModel, encodingManager)

        val turnCostProvider = if (profile.hasTurnCosts() && !disableTurnCosts) {
            val turnRestrictionEnc =
                encodingManager.getTurnBooleanEncodedValue(TurnRestriction.key(profile.name))
                    ?: throw IllegalArgumentException(
                        "Cannot find turn restriction encoded value for ${profile.name}"
                    )
            val uTurnCosts = hints.getInt(
                Parameters.Routing.U_TURN_COSTS,
                turnCostsConfig!!.getUTurnCosts(),
            )
            val tcConfig = TurnCostsConfig(turnCostsConfig).setUTurnCosts(uTurnCosts)
            DefaultTurnCostProvider(
                turnRestrictionEnc,
                graph,
                tcConfig,
                parameters.turnPenaltyMapping,
            )
        } else {
            if (mergedCustomModel.turnPenalty.isNotEmpty())
                throw IllegalArgumentException(
                    "The turn_penalty feature is not supported for ${profile.name}. " +
                        "You have to enable this in 'turn_costs' in config.yml."
                )
            TurnCostProvider.NO_TURN_COST_PROVIDER
        }

        return CustomWeighting(turnCostProvider, parameters)
    }

    private fun createParameters(
        customModel: CustomModel,
        lookup: EncodedValueLookup,
    ): CustomWeighting.Parameters {
        val prio = MotorcycleWeightingHelper()
        prio.init(customModel, lookup, CustomModel.getAreasAsMap(customModel.areas))

        // GraphHopper's CustomWeightingHelper.calcMaxSpeed()/calcMaxPriority()
        // are final and range-check the value expressions ("0.9 *
        // car_average_speed", ...) through ValueExpressionVisitor, which
        // compiles them with Janino — another ART-incompatible path. Only the
        // MAX values are consumed at runtime
        // (CustomWeighting.calcMinWeightPerDistance), so compute them directly,
        // mirroring FindMinMax for the fixed motorcycle model
        // (tools/gh/motorcycle.json — the canonical model):
        //   maxPriority: every priority statement is MULTIPLY with value <= 1
        //     and has no ELSE, so the neutral branch keeps max = 1.
        //   maxSpeed: groups [IF true LIMIT 0.9*car_average_speed],
        //     [IF true LIMIT 120], [IF rough-surface LIMIT 30] ->
        //     min(120, 0.9 * max(car_average_speed)).
        // If the model statements change, regenerate MotorcycleWeightingHelper
        // AND update these computations (see AGENTS.md).
        val avgSpeedEnc = lookup.getEncodedValue("car_average_speed", EncodedValue::class.java)
            as DecimalEncodedValue
        val maxSpeed = minOf(120.0, 0.9 * avgSpeedEnc.maxOrMaxStorableDecimal)

        return CustomWeighting.Parameters(
            prio::getSpeed,
            MaxCalc { maxSpeed },
            prio::getPriority,
            MaxCalc { 1.0 },
            prio::getTurnPenalty,
            customModel.distanceInfluence ?: 0.0,
            customModel.headingPenalty ?: Parameters.Routing.DEFAULT_HEADING_PENALTY,
        )
    }
}
