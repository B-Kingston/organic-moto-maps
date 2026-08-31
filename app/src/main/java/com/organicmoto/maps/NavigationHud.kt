package com.organicmoto.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import com.graphhopper.util.Instruction
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Compact top-left guidance card, shown while a route is being followed:
 * next-turn arrow over the distance to it, on a dark rounded card floating
 * over the map. The riding metrics (speed, ETA, remaining distance, pace
 * delta) live in [NavigationDataBar] at the bottom of the screen. Layout
 * follows the repo's hand-drawn style: Canvas icons, plain Surface, no
 * image assets.
 */
@Composable
fun NavigationHud(snapshot: NavigationSnapshot, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF1C1C1E),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 8.dp)
            .semantics {
                contentDescription = "Navigation guidance: " +
                    NavigationHudFormat.turnDescription(snapshot.turn)
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Canvas(Modifier.size(width = 44.dp, height = 44.dp)) {
                drawTurnArrow(snapshot.turn?.sign, size.width, size.height)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = snapshot.turn?.let { NavigationHudFormat.distanceText(it.distanceM) } ?: "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            if (!snapshot.turn?.streetName.isNullOrBlank()) {
                Text(
                    text = snapshot.turn!!.streetName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3FFFFFF),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Bottom-of-screen guidance bar that replaces the planner controls in ride
 * mode: live speed, ETA, remaining distance, and pace delta on the left, and
 * a red END button on the right that cancels navigation. Dark blue so the
 * white metric text stays readable over the map.
 */
@Composable
fun NavigationDataBar(
    snapshot: NavigationSnapshot,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xFF0D2137),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Ride data: " +
                    "${NavigationHudFormat.speedKmh(snapshot.speedMps)} kilometres per hour, " +
                    "${NavigationHudFormat.etaText(snapshot.remainingTimeS)} remaining, " +
                    "${NavigationHudFormat.distanceText(snapshot.remainingDistanceM)} left, " +
                    NavigationHudFormat.deltaDescription(snapshot.paceDeltaS)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            DeltaCell(
                deltaS = snapshot.paceDeltaS,
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = NavigationHudFormat.speedKmh(snapshot.speedMps),
                label = "km/h",
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = NavigationHudFormat.etaText(snapshot.remainingTimeS),
                label = "remaining",
                modifier = Modifier.weight(1f),
            )
            MetricCell(
                value = NavigationHudFormat.distanceText(snapshot.remainingDistanceM),
                label = "left",
                modifier = Modifier.weight(1.2f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onEnd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.semantics { contentDescription = "End navigation" },
            ) {
                Text("END", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}



@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color(0xB3FFFFFF))
    }
}

/**
 * Motorsport-style live pace delta: green when ahead of the plan, red when
 * behind, dashes until the engine has measured enough movement. The value is
 * a smoothed total (not per-second jitter) so it reads like a timing screen.
 */
@Composable
private fun DeltaCell(deltaS: Double, modifier: Modifier = Modifier) {
    val known = !deltaS.isNaN()
    val color = when {
        !known -> Color(0xB3FFFFFF)
        deltaS <= 0.0 -> Color(0xFF3DDC84) // ahead: green
        else -> Color(0xFFFF5252)          // behind: red
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = NavigationHudFormat.deltaText(deltaS),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
        Text(
            text = when {
                !known -> "pace"
                deltaS > 0.0 -> "lost"
                else -> "gained"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xB3FFFFFF),
        )
    }
}

/** Hand-drawn turn arrow: one canvas, sign-driven shape like the repo's icons. */
private fun DrawScope.drawTurnArrow(sign: Int?, w: Float, h: Float) {
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val paint = Color.White
    if (sign == null) {
        // No turn pending: draw a straight placeholder stem.
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.2f)
            lineTo(w * 0.5f, h * 0.8f)
        }
        drawPath(path, paint, style = stroke)
        return
    }
    val isUTurn = sign == Instruction.U_TURN_UNKNOWN ||
        sign == Instruction.U_TURN_LEFT || sign == Instruction.U_TURN_RIGHT
    val isRoundabout = sign == Instruction.USE_ROUNDABOUT ||
        sign == Instruction.LEAVE_ROUNDABOUT
    val left = sign < 0 // GraphHopper left maneuvers have negative signs
    val sharp = abs(sign) == 3 // sharp variants
    val straight = sign == Instruction.CONTINUE_ON_STREET ||
        sign == Instruction.FERRY || sign == Instruction.UNKNOWN
    var headFromX = w * 0.5f
    var headFromY = h * 0.45f
    var headX = w * 0.5f
    var headY = h * 0.16f
    val path = Path().apply {
        when {
            isUTurn || isRoundabout -> {
                // A compact hook whose arrowhead follows the end of the curve.
                val side = if (left) w * 0.28f else w * 0.72f
                moveTo(w * 0.5f, h * 0.85f)
                lineTo(w * 0.5f, h * 0.38f)
                cubicTo(w * 0.5f, h * 0.15f, side, h * 0.15f, side, h * 0.4f)
                headFromX = side
                headFromY = h * 0.18f
                headX = side
                headY = h * 0.4f
            }
            straight -> {
                moveTo(w * 0.5f, h * 0.85f)
                lineTo(headX, headY)
            }
            else -> {
                // Keep the elbow close to the stem and let the final segment
                // determine the arrowhead angle.
                val direction = if (left) -1f else 1f
                val slight = abs(sign) == 1 || abs(sign) == 7
                headX = w * (0.5f + direction * if (sharp) 0.32f else if (slight) 0.25f else 0.3f)
                headY = h * if (sharp) 0.55f else if (slight) 0.2f else 0.4f
                headFromX = w * 0.5f
                headFromY = h * 0.44f
                moveTo(w * 0.5f, h * 0.85f)
                lineTo(headFromX, headFromY)
                lineTo(headX, headY)
            }
        }
    }
    drawPath(path, paint, style = stroke)

    // Align a compact arrowhead with the maneuver's final segment. The old
    // fixed upright chevron spread away from diagonal branches.
    val dx = headX - headFromX
    val dy = headY - headFromY
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val ux = dx / length
    val uy = dy / length
    val baseX = headX - ux * w * 0.16f
    val baseY = headY - uy * w * 0.16f
    val wing = w * 0.075f
    val head = Path().apply {
        moveTo(baseX - uy * wing, baseY + ux * wing)
        lineTo(headX, headY)
        lineTo(baseX + uy * wing, baseY - ux * wing)
    }
    drawPath(head, paint, style = stroke)
}


/** Pure formatting helpers, JVM-testable. */
object NavigationHudFormat {
    fun speedKmh(speedMps: Double): String =
        if (speedMps.isNaN()) "—" else "${(speedMps * 3.6).toInt()}"

    fun distanceText(meters: Double): String = when {
        meters.isNaN() || meters < 0 -> "—"
        meters >= 10_000 -> "%,.0f km".format(java.util.Locale.US, meters / 1000.0)
        meters >= 1_000 -> "%.1f km".format(java.util.Locale.US, meters / 1000.0)
        else -> "${meters.toInt()} m"
    }

    fun etaText(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val totalMinutes = (safe / 60.0).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "$hours h $minutes min"
            totalMinutes > 0 -> "$totalMinutes min"
            else -> "<1 min"
        }
    }

    /** Maneuver name for accessibility and logs, aligned with GraphHopper signs. */
    fun turnDescription(turn: NavigationSnapshot.TurnInfo?): String {
        turn ?: return "continue ahead"
        val action = when (turn.sign) {
            Instruction.U_TURN_UNKNOWN,
            Instruction.U_TURN_LEFT,
            Instruction.U_TURN_RIGHT,
            -> "make a U-turn"
            Instruction.TURN_SHARP_LEFT -> "turn sharp left"
            Instruction.TURN_LEFT -> "turn left"
            Instruction.TURN_SLIGHT_LEFT -> "turn slight left"
            Instruction.KEEP_LEFT -> "keep left"
            Instruction.TURN_SHARP_RIGHT -> "turn sharp right"
            Instruction.TURN_RIGHT -> "turn right"
            Instruction.TURN_SLIGHT_RIGHT -> "turn slight right"
            Instruction.KEEP_RIGHT -> "keep right"
            Instruction.USE_ROUNDABOUT -> "enter the roundabout"
            Instruction.LEAVE_ROUNDABOUT -> "exit the roundabout"
            Instruction.FERRY -> "take the ferry"
            else -> "continue ahead"
        }
        return "$action in ${distanceText(turn.distanceM)}"
    }

    /**
     * Motorsport delta format: "+12.3 s" / "-1.0 s" / "0.0 s"; dashes until
     * the engine has measured. The sign carries the direction, the label
     * ("gained"/"lost") disambiguates for accessibility.
     */
    fun deltaText(deltaS: Double): String = when {
        deltaS.isNaN() -> "—"
        else -> {
            val sign = if (deltaS > 0.05) "+" else if (deltaS < -0.05) "-" else ""
            "$sign%.1f s".format(java.util.Locale.US, abs(deltaS))
        }
    }

    fun deltaDescription(deltaS: Double): String = when {
        deltaS.isNaN() -> "pace delta unavailable"
        deltaS > 0.05 -> "pace ${deltaText(deltaS)} lost"
        deltaS < -0.05 -> "pace ${deltaText(deltaS)} gained"
        else -> "pace on plan"
    }
}
