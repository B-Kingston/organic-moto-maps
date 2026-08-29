package com.organicmoto.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import kotlin.math.abs

/**
 * Top-of-map guidance HUD, shown while a route is being followed: next-turn
 * arrow with distance, current speed, remaining time (ETA), and remaining
 * distance. Layout follows the repo's hand-drawn style: Canvas icons, plain
 * Surface, no image assets.
 */
@Composable
fun NavigationHud(snapshot: NavigationSnapshot, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFF0D2137),
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Navigation guidance: " +
                    "${NavigationHudFormat.turnDescription(snapshot.turn)}, " +
                    "${NavigationHudFormat.speedKmh(snapshot.speedMps)} kilometres per hour, " +
                    "${NavigationHudFormat.etaText(snapshot.remainingTimeS)} remaining"
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            TurnCell(snapshot.turn, Modifier.weight(1.4f))
            Spacer(Modifier.width(12.dp))
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
        }
    }
}

@Composable
private fun TurnCell(turn: NavigationSnapshot.TurnInfo?, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Canvas(Modifier.size(width = 44.dp, height = 44.dp)) {
            drawTurnArrow(turn?.sign, size.width, size.height)
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = turn?.let { NavigationHudFormat.distanceText(it.distanceM) } ?: "—",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            if (!turn?.streetName.isNullOrBlank()) {
                Text(
                    text = turn!!.streetName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xB3FFFFFF),
                    maxLines = 1,
                )
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
    val isUTurn = abs(sign) == 8
    val isRoundabout = abs(sign) == 6 || abs(sign) == 7
    val left = sign < 0 // GraphHopper left maneuvers have negative signs
    val sharp = abs(sign) == 3 // sharp variants
    val headX = when {
        isUTurn || isRoundabout -> if (left) w * 0.35f else w * 0.65f
        left -> w * (if (sharp) 0.08f else 0.18f)
        else -> w * (if (sharp) 0.92f else 0.82f)
    }
    val headY = when {
        isUTurn || isRoundabout -> h * 0.45f
        else -> h * (if (sharp) 0.25f else 0.3f)
    }
    val path = Path().apply {
        when {
            isUTurn || isRoundabout -> {
                // U-turn / roundabout approximations: a hook.
                moveTo(headX, h * 0.85f)
                lineTo(headX, headY)
                cubicTo(headX, h * 0.2f, w - headX, h * 0.2f, w - headX, headY)
            }
            else -> {
                // Straight stem then a branching head.
                moveTo(w * 0.5f, h * 0.85f)
                lineTo(w * 0.5f, h * 0.45f)
                lineTo(headX, headY)
            }
        }
    }
    drawPath(path, paint, style = stroke)
    // Arrowhead: two short strokes at the branch end.
    val head = Path().apply {
        moveTo(headX - w * 0.1f, headY + h * 0.14f)
        lineTo(headX, headY)
        lineTo(headX + w * 0.1f, headY + h * 0.14f)
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

    /** Coarse maneuver name for accessibility and logs. */
    fun turnDescription(turn: NavigationSnapshot.TurnInfo?): String = when {
        turn == null -> "continue ahead"
        turn.sign == 4 || turn.sign == -4 -> "make a U-turn"
        turn.sign > 0 -> "turn right in ${distanceText(turn.distanceM)}"
        turn.sign < 0 -> "turn left in ${distanceText(turn.distanceM)}"
        else -> "continue ahead"
    }
}
