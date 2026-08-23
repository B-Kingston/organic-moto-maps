package com.organicmoto.maps.routing

import com.graphhopper.config.Profile
import com.graphhopper.routing.DefaultWeightingFactory
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
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
 * The factory also implements the `moto_blend` route slider (request hint
 * [MOTO_BLEND], value in [0,1], default 1.0): a value below 1.0 returns a
 * [BlendedWeighting] that linearly blends the motorcycle [CustomWeighting]
 * with [FastestWeighting]. Both use the model's effective motorcycle speeds;
 * Fastest omits only its scenic road priorities and distance influence. At
 * t = 1.0 — and during graph load, where
 * the hints PMap is empty — the pure CustomWeighting is returned exactly as
 * before, so the stored graph and its baked-in profile hash are untouched.
 * The caller must set `ch.disable` for t < 1: the CH solver ignores request
 * hints and always uses the weighting baked into the CH graph at import time.
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

        // Resolved once here because the model's maximum effective speed is
        // used by both CustomWeighting's and FastestWeighting's admissible A*
        // lower bounds.
        val avgSpeedEnc = encodingManager.getDecimalEncodedValue("car_average_speed")

        val preparedModel = prepareModel(mergedCustomModel, encodingManager, avgSpeedEnc)

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
                preparedModel.parameters.turnPenaltyMapping,
            )
        } else {
            if (mergedCustomModel.turnPenalty.isNotEmpty())
                throw IllegalArgumentException(
                    "The turn_penalty feature is not supported for ${profile.name}. " +
                        "You have to enable this in 'turn_costs' in config.yml."
                )
            TurnCostProvider.NO_TURN_COST_PROVIDER
        }

        // Route-blend slider: t in [0,1] via the moto_blend request hint,
        // default 1.0. At t >= 1.0 return the pure CustomWeighting exactly as
        // before (this is also the path taken during graph load, where the
        // hints PMap is empty). For t < 1.0 blend in FastestWeighting;
        // GraphHopperRouter must also set Parameters.CH.DISABLE, because the
        // CH solver uses the weighting baked into the CH graph at import time
        // and ignores request hints entirely — without it the slider would
        // silently do nothing.
        val blend = hints.getDouble(MOTO_BLEND, 1.0).coerceIn(0.0, 1.0)
        if (blend >= 1.0) return CustomWeighting(turnCostProvider, preparedModel.parameters)

        val carAccessEnc: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("car_access")
        return BlendedWeighting(
            CustomWeighting(turnCostProvider, preparedModel.parameters),
            FastestWeighting(
                preparedModel.helper::getSpeed,
                preparedModel.maxSpeedKmh,
                turnCostProvider,
                carAccessEnc,
            ),
            blend,
            carAccessEnc,
        )
    }

    private data class PreparedModel(
        val parameters: CustomWeighting.Parameters,
        val helper: MotorcycleWeightingHelper,
        val maxSpeedKmh: Double,
    )

    private fun prepareModel(
        customModel: CustomModel,
        lookup: EncodedValueLookup,
        avgSpeedEnc: DecimalEncodedValue,
    ): PreparedModel {
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
        val maxSpeed = minOf(120.0, 0.9 * avgSpeedEnc.maxOrMaxStorableDecimal)

        val parameters = CustomWeighting.Parameters(
            prio::getSpeed,
            MaxCalc { maxSpeed },
            prio::getPriority,
            MaxCalc { 1.0 },
            prio::getTurnPenalty,
            customModel.distanceInfluence ?: 0.0,
            customModel.headingPenalty ?: Parameters.Routing.DEFAULT_HEADING_PENALTY,
        )
        return PreparedModel(parameters, prio, maxSpeed)
    }
}
