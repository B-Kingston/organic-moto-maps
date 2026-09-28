package com.organicmoto.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
            Box(
                modifier = Modifier.size(width = 44.dp, height = 44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(width = 44.dp, height = 44.dp)) {
                    drawTurnArrow(snapshot.turn, size.width, size.height)
                }
                snapshot.turn?.roundaboutExitNumber?.takeIf { it > 0 }?.let { exitNumber ->
                    Text(
                        text = exitNumber.toString(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(CircleShape)
                            .background(Color(0xFF249CF2))
                            .padding(horizontal = 3.dp, vertical = 1.dp),
                    )
                }
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

/** Hand-drawn maneuver symbol, shaped from GraphHopper's instruction and route geometry. */
private fun DrawScope.drawTurnArrow(turn: NavigationSnapshot.TurnInfo?, w: Float, h: Float) {
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val paint = Color.White
    val sign = turn?.sign
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
    val isRoundabout = sign == Instruction.USE_ROUNDABOUT || sign == Instruction.LEAVE_ROUNDABOUT
    if (isUTurn) {
        drawUTurnArrow(
            NavigationHudFormat.uTurnSide(sign, turn.turnAngleDeg),
            w,
            h,
            paint,
            stroke,
        )
        return
    }
    if (isRoundabout) {
        drawRoundaboutArrow(
            w,
            h,
            sign == Instruction.LEAVE_ROUNDABOUT,
            turn.roundaboutClockwise,
            paint,
            stroke,
        )
        return
    }
    if (sign == Instruction.KEEP_LEFT || sign == Instruction.KEEP_RIGHT) {
        drawKeepArrow(sign == Instruction.KEEP_LEFT, w, h, paint, stroke)
        return
    }
    if (sign == Instruction.TURN_SLIGHT_LEFT || sign == Instruction.TURN_SLIGHT_RIGHT) {
        drawVeerArrow(
            NavigationHudFormat.slightVeerAngleDeg(sign, turn.turnAngleDeg),
            w,
            h,
            paint,
            stroke,
        )
        return
    }
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
            straight -> {
                moveTo(w * 0.5f, h * 0.85f)
                lineTo(headX, headY)
            }
            else -> {
                // Keep the elbow close to the stem and let the final segment
                // determine the arrowhead angle.
                val direction = if (left) -1f else 1f
                headX = w * (0.5f + direction * if (sharp) 0.32f else 0.3f)
                headY = h * if (sharp) 0.55f else 0.4f
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

/** A neutral U-turn when GraphHopper does not provide a side. */
private fun DrawScope.drawUTurnArrow(
    side: Int,
    w: Float,
    h: Float,
    color: Color,
    stroke: Stroke,
) {
    if (side == 0) {
        val centerX = w * 0.5f
        val leftX = w * 0.27f
        val rightX = w * 0.73f
        val turnY = h * 0.45f
        val returnY = h * 0.68f
        val path = Path().apply {
            moveTo(centerX, h * 0.9f)
            lineTo(centerX, turnY)
            cubicTo(centerX, h * 0.2f, leftX, h * 0.2f, leftX, turnY)
            lineTo(leftX, returnY)
            moveTo(centerX, turnY)
            cubicTo(centerX, h * 0.2f, rightX, h * 0.2f, rightX, turnY)
            lineTo(rightX, returnY)
        }
        drawPath(path, color, style = stroke)
        drawArrowHead(leftX, returnY, 0f, 1f, w, color, stroke)
        drawArrowHead(rightX, returnY, 0f, 1f, w, color, stroke)
        return
    }

    val sideX = w * if (side < 0) 0.28f else 0.72f
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.88f)
        lineTo(w * 0.5f, h * 0.38f)
        cubicTo(w * 0.5f, h * 0.15f, sideX, h * 0.15f, sideX, h * 0.4f)
    }
    drawPath(path, color, style = stroke)
    drawArrowHead(sideX, h * 0.4f, 0f, 1f, w, color, stroke)
}

/** A slight bend keeps the arrowhead close to straight ahead, matching a veer. */
private fun DrawScope.drawVeerArrow(
    deflectionDeg: Float,
    w: Float,
    h: Float,
    color: Color,
    stroke: Stroke,
) {
    val angle = Math.toRadians(deflectionDeg.toDouble())
    val directionX = kotlin.math.sin(angle).toFloat()
    val directionY = -kotlin.math.cos(angle).toFloat()
    val stemX = w * 0.5f
    val bendY = h * 0.58f
    val tipX = stemX + directionX * w * 0.31f
    val tipY = bendY + directionY * h * 0.31f
    val handle = w * 0.16f
    val path = Path().apply {
        moveTo(stemX, h * 0.88f)
        lineTo(stemX, bendY)
        cubicTo(
            stemX,
            bendY - handle,
            tipX - directionX * handle,
            tipY - directionY * handle,
            tipX,
            tipY,
        )
    }
    drawPath(path, color, style = stroke)
    drawArrowHead(tipX, tipY, directionX, directionY, w, color, stroke)
}

/** A fork with only the chosen keep-left/right branch receiving an arrowhead. */
private fun DrawScope.drawKeepArrow(
    keepLeft: Boolean,
    w: Float,
    h: Float,
    color: Color,
    stroke: Stroke,
) {
    val forkX = w * 0.5f
    val forkY = h * 0.55f
    val selectedX = w * if (keepLeft) 0.2f else 0.8f
    val otherX = w - selectedX
    val branchY = h * 0.22f
    val approach = Path().apply {
        moveTo(forkX, h * 0.9f)
        lineTo(forkX, forkY)
    }
    val selected = Path().apply {
        moveTo(forkX, forkY)
        lineTo(selectedX, branchY)
    }
    val other = Path().apply {
        moveTo(forkX, forkY)
        lineTo(otherX, branchY)
    }
    drawPath(approach, color, style = stroke)
    drawPath(selected, color, style = stroke)
    drawPath(other, Color(0xFF85858A), style = Stroke(width = w * 0.055f, cap = StrokeCap.Round))
    val directionX = if (keepLeft) -0.68f else 0.68f
    val directionY = -0.73f
    drawArrowHead(selectedX, branchY, directionX, directionY, w, color, stroke)
}

/** Entry into a roundabout, with the selected exit number shown as a badge. */
private fun DrawScope.drawRoundaboutArrow(
    w: Float,
    h: Float,
    leaving: Boolean,
    clockwise: Boolean?,
    color: Color,
    stroke: Stroke,
) {
    val diameter = w * 0.58f
    val radius = diameter * 0.5f
    val left = (w - diameter) * 0.5f
    val top = h * 0.16f
    val center = Offset(w * 0.5f, top + radius)
    drawPath(
        Path().apply {
            moveTo(center.x, h * 0.94f)
            lineTo(center.x, center.y + radius)
        },
        color,
        style = stroke,
    )
    // The lower gap joins the approach to the loop. GraphHopper supplies the
    // actual circulation direction; omit the arrowhead if its geometry is
    // ambiguous instead of implying the wrong direction.
    val startAngle = if (clockwise == false) 40f else 140f
    val sweepAngle = if (clockwise == false) -280f else 280f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweepAngle,
        useCenter = false,
        topLeft = Offset(left, top),
        size = Size(diameter, diameter),
        style = stroke,
    )
    clockwise?.let { isClockwise ->
        val endAngle = Math.toRadians((startAngle + sweepAngle).toDouble())
        val endX = center.x + radius * kotlin.math.cos(endAngle).toFloat()
        val endY = center.y + radius * kotlin.math.sin(endAngle).toFloat()
        val rotationSign = if (isClockwise) 1f else -1f
        val tangentX = -kotlin.math.sin(endAngle).toFloat() * rotationSign
        val tangentY = kotlin.math.cos(endAngle).toFloat() * rotationSign
        drawArrowHead(endX, endY, tangentX, tangentY, w, color, stroke)
    }
    if (leaving) {
        val exitY = center.y
        val exit = Path().apply {
            moveTo(center.x + radius, exitY)
            lineTo(w * 0.94f, exitY)
        }
        drawPath(exit, color, style = stroke)
        drawArrowHead(w * 0.94f, exitY, 1f, 0f, w, color, stroke)
    }
}

/** Arrowhead aligned to a normalized direction vector. */
private fun DrawScope.drawArrowHead(
    tipX: Float,
    tipY: Float,
    directionX: Float,
    directionY: Float,
    w: Float,
    color: Color,
    stroke: Stroke,
) {
    val length = kotlin.math.sqrt(directionX * directionX + directionY * directionY).coerceAtLeast(1e-3f)
    val ux = directionX / length
    val uy = directionY / length
    val headLength = w * 0.15f
    val wing = w * 0.075f
    val baseX = tipX - ux * headLength
    val baseY = tipY - uy * headLength
    val head = Path().apply {
        moveTo(baseX - uy * wing, baseY + ux * wing)
        lineTo(tipX, tipY)
        lineTo(baseX + uy * wing, baseY - ux * wing)
    }
    drawPath(head, color, style = stroke)
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
            Instruction.TURN_SLIGHT_LEFT -> "veer slightly left"
            Instruction.KEEP_LEFT -> "keep left"
            Instruction.TURN_SHARP_RIGHT -> "turn sharp right"
            Instruction.TURN_RIGHT -> "turn right"
            Instruction.TURN_SLIGHT_RIGHT -> "veer slightly right"
            Instruction.KEEP_RIGHT -> "keep right"
            Instruction.USE_ROUNDABOUT -> turn.roundaboutExitNumber
                ?.takeIf { it > 0 }
                ?.let { "take the ${ordinal(it)} exit at the roundabout" }
                ?: "enter the roundabout"
            Instruction.LEAVE_ROUNDABOUT -> "exit the roundabout"
            Instruction.FERRY -> "take the ferry"
            else -> "continue ahead"
        }
        return "$action in ${distanceText(turn.distanceM)}"
    }

    /** Returns the route's slight-turn angle when it agrees with the sign. */
    fun slightVeerAngleDeg(sign: Int, routeAngleDeg: Double?): Float {
        val direction = when (sign) {
            Instruction.TURN_SLIGHT_LEFT -> -1.0
            Instruction.TURN_SLIGHT_RIGHT -> 1.0
            else -> return 0f
        }
        val measuredMagnitude = routeAngleDeg
            ?.takeIf { it.isFinite() && it * direction > 0.0 && abs(it) >= 8.0 }
            ?.let { abs(it).coerceIn(12.0, 58.0) }
        return (measuredMagnitude ?: 35.0).toFloat() * direction.toFloat()
    }

    /** Resolves a U-turn side from its sign or unambiguous route geometry. */
    fun uTurnSide(sign: Int, routeAngleDeg: Double?): Int = when (sign) {
        Instruction.U_TURN_LEFT -> -1
        Instruction.U_TURN_RIGHT -> 1
        Instruction.U_TURN_UNKNOWN -> routeAngleDeg
            ?.takeIf { it.isFinite() && abs(it) in 30.0..160.0 }
            ?.let { if (it < 0.0) -1 else 1 }
            ?: 0
        else -> 0
    }

    private fun ordinal(number: Int): String {
        val suffix = if (number % 100 in 11..13) {
            "th"
        } else {
            when (number % 10) {
                1 -> "st"
                2 -> "nd"
                3 -> "rd"
                else -> "th"
            }
        }
        return "$number$suffix"
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
