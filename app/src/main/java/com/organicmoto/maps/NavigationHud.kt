package com.organicmoto.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.graphhopper.util.Instruction
import com.organicmoto.maps.media.GloveGap
import com.organicmoto.maps.media.GloveTarget
import com.organicmoto.maps.media.MediaNoteIcon
import com.organicmoto.maps.media.MediaPanelJoin
import com.organicmoto.maps.routing.navigation.LaneArrow
import com.organicmoto.maps.routing.navigation.LaneGuidance
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
fun NavigationHud(
    snapshot: NavigationSnapshot,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = RideSurfaceColor,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 6.dp,
        modifier = modifier
            .statusBarsPadding()
            .padding(start = 12.dp, top = 8.dp)
            .widthIn(max = 300.dp)
            .semantics {
                contentDescription = "Navigation guidance: " +
                    NavigationHudFormat.turnDescription(snapshot.turn)
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            color = RideSurfaceColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .clip(CircleShape)
                                .background(Color.White)
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
                        color = RideMutedColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                snapshot.turn?.lanes?.let { lanes ->
                    Spacer(Modifier.height(8.dp))
                    LaneGuidanceRow(lanes)
                }
            }
        }
    }
}

/** Shared dark ride surface: directions HUD, data bar, and media panel. */
internal val RideSurfaceColor = Color(0xFF1C1C1E)

/**
 * Secondary content on the dark ride surfaces and inside the white map pills:
 * metric labels, the street name, and the unknown pace delta. The ride chrome
 * uses exactly one white and this one near-black ([RideSurfaceColor]) — state
 * is carried by glyph and text, never hue.
 */
internal val RideMutedColor = Color(0xB3FFFFFF)

/**
 * Rounding of the data bar's two top corners. The media panel fuses into this
 * edge (see [com.organicmoto.maps.media.MediaPanelJoin]), and mirroring the
 * same radius is what makes the two surfaces read as one continuous shape
 * rather than two stacked cards with a notch between them.
 */
internal val DataBarTopCorner: Dp = 24.dp

/**
 * Data bar leading content padding, so the media opener's left edge sits this
 * far from the screen edge. The media panel is inset by exactly the same amount
 * so its left edge is flush with the opener it grows out of.
 */
internal val DataBarLeadingPadding: Dp = 4.dp

/** Data bar trailing content padding. */
internal val DataBarTrailingPadding: Dp = 12.dp

/**
 * Base vertical inset for ride-bar content, excluding the system navigation
 * inset. Represents the bar's top and bottom gaps around the metric row.
 */
internal val DataBarVerticalPadding: Dp = 6.dp

/**
 * Bottom-of-screen guidance bar that replaces the planner controls in ride
 * mode. It shares the directions HUD's surface colour ([RideSurfaceColor]) and
 * holds a balanced four-metric grid — speed, time, distance, pace — with the
 * far-left media control opener and a distinct END button on the right.
 *
 * The bar adapts its rows instead of squeezing the metrics: on a wide screen
 * everything sits on one row, while on a narrow or large-font screen it uses
 * two metric rows plus a media/END row so every value stays fully readable at
 * its minimum font size (never a 10 sp ellipsis). All controls keep
 * glove-sized targets ([GloveTarget]) and visible separation between them.
 *
 * The bar carries the ride chrome's single palette: white content on the dark
 * surface, with muted white for labels. [monochrome] is the B&W ride mode and
 * greys the one remaining accent, the END button. The bar pads itself for the
 * system navigation bar so its controls stay reachable and unclipped on
 * gesture devices and under large font scales.
 */
@Composable
fun NavigationDataBar(
    snapshot: NavigationSnapshot,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
    mediaPanelOpen: Boolean = false,
    onMediaToggle: () -> Unit = {},
    monochrome: Boolean = false,
) {
    val speedValue = NavigationHudFormat.speedKmh(snapshot.speedMps)
    val etaValue = NavigationHudFormat.etaCompactText(snapshot.remainingTimeS)
    val distanceValue = NavigationHudFormat.distanceText(snapshot.remainingDistanceM)
    val paceValue = NavigationHudFormat.deltaText(snapshot.paceDeltaS)
    val density = LocalDensity.current
    val navigationInset = with(density) {
        WindowInsets.navigationBars.getBottom(this).toDp()
    }
    // The bar draws behind the system navigation area. Match the content gap
    // above the row to its complete gap below it (the base vertical padding
    // plus that system inset).
    // The media-panel join still fuses over its independent base padding.
    val topRowPadding = DataBarVerticalPadding + navigationInset

    BoxWithConstraints(modifier) {
        val fontScale = LocalDensity.current.fontScale
        val singleRow = NavigationHudFormat.dataBarLayout(
            availableWidthDp = maxWidth.value,
            fontScale = fontScale,
            metricValues = listOf(speedValue, etaValue, distanceValue, paceValue),
        ) == DataBarLayout.SINGLE_ROW
        Surface(
            color = RideSurfaceColor,
            // The panel supplies the top rounding when open; the join itself
            // must be square or its corners expose little wedges of map.
            shape = RoundedCornerShape(
                topStart = MediaPanelJoin.barTopCorner(mediaPanelOpen),
                topEnd = MediaPanelJoin.barTopCorner(mediaPanelOpen),
            ),
            // The bar's own shadow would fall across the panel's bottom edge and
            // draw the very seam the join removes; the panel supplies the
            // elevation for the fused pair.
            shadowElevation = MediaPanelJoin.barShadow(mediaPanelOpen),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Ride data: " +
                        "${NavigationHudFormat.speedKmh(snapshot.speedMps)} kilometres per hour, " +
                        "${NavigationHudFormat.etaText(snapshot.remainingTimeS)} remaining, " +
                        "${NavigationHudFormat.distanceText(snapshot.remainingDistanceM)} left, " +
                        NavigationHudFormat.deltaDescription(snapshot.paceDeltaS)
                },
        ) {
            if (singleRow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(
                        start = DataBarLeadingPadding,
                        end = DataBarTrailingPadding,
                        top = topRowPadding,
                        bottom = DataBarVerticalPadding,
                    ),
                ) {
                    MediaOpenButton(open = mediaPanelOpen, onClick = onMediaToggle)
                    MetricCell(
                        value = speedValue,
                        label = "km/h",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        value = etaValue,
                        label = "remaining",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCell(
                        value = distanceValue,
                        label = "left",
                        modifier = Modifier.weight(1f),
                    )
                    DeltaCell(
                        deltaS = snapshot.paceDeltaS,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    EndRideButton(onEnd = onEnd, monochrome = monochrome)
                }
            } else {
                // Narrow/large-font: a true two-metric-wide grid with the
                // media and END controls on their own full-width row.
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(
                            start = DataBarLeadingPadding,
                            end = DataBarTrailingPadding,
                            top = topRowPadding,
                            bottom = DataBarVerticalPadding,
                        ),
                ) {
                    Row(Modifier.fillMaxWidth()) {
                        MetricCell(
                            value = speedValue,
                            label = "km/h",
                            modifier = Modifier.weight(1f),
                        )
                        MetricCell(
                            value = etaValue,
                            label = "remaining",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        MetricCell(
                            value = distanceValue,
                            label = "left",
                            modifier = Modifier.weight(1f),
                        )
                        DeltaCell(
                            deltaS = snapshot.paceDeltaS,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(GloveGap))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        MediaOpenButton(open = mediaPanelOpen, onClick = onMediaToggle)
                        Spacer(Modifier.weight(1f))
                        EndRideButton(onEnd = onEnd, monochrome = monochrome)
                    }
                }
            }
        }
    }
}

/** Glove-sized media opener: far-left, visually separated from the metrics. */
@Composable
private fun MediaOpenButton(open: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(GloveTarget)
            .semantics {
                contentDescription = "Media controls"
                stateDescription = if (open) "open" else "closed"
            },
    ) {
        MediaNoteIcon(color = Color.White)
    }
}

/** Distinct END, on the right, with the same glove-sized target floor. */
@Composable
private fun EndRideButton(onEnd: () -> Unit, monochrome: Boolean) {
    Button(
        onClick = onEnd,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (monochrome) Color(0xFFE5E5EA) else Color(0xFFD32F2F),
            contentColor = if (monochrome) RideSurfaceColor else Color.White,
        ),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        modifier = Modifier
            .heightIn(min = GloveTarget)
            .defaultMinSize(minWidth = GloveTarget)
            .semantics { contentDescription = "End navigation" },
    ) {
        Text("END", fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Balanced 24 dp speaker, with circular waves contained inside its canvas. */
@Composable
internal fun VoiceGuidanceIcon(enabled: Boolean, color: Color = Color.White) {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val speaker = Path().apply {
            moveTo(3f * unit, 9f * unit)
            lineTo(7f * unit, 9f * unit)
            lineTo(12f * unit, 5f * unit)
            lineTo(12f * unit, 19f * unit)
            lineTo(7f * unit, 15f * unit)
            lineTo(3f * unit, 15f * unit)
            close()
        }
        drawPath(speaker, color)
        val stroke = Stroke(width = 1.8f * unit, cap = StrokeCap.Round)
        if (enabled) {
            for (radius in listOf(5f, 9f)) {
                drawArc(
                    color = color,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset((12f - radius) * unit, (12f - radius) * unit),
                    size = Size(radius * 2f * unit, radius * 2f * unit),
                    style = stroke,
                )
            }
        } else {
            // The cross sits beside the speaker rather than merging into its body.
            drawLine(color, Offset(16f * unit, 9f * unit), Offset(21f * unit, 15f * unit),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
            drawLine(color, Offset(16f * unit, 15f * unit), Offset(21f * unit, 9f * unit),
                strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

/** Crescent moon: a familiar night-map control, filled when active. */
@Composable
internal fun DarkRideMapIcon(enabled: Boolean, color: Color = Color.White) {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.width / 24f
        val moon = Path().apply {
            moveTo(15f * unit, 3f * unit)
            cubicTo(8f * unit, 1f * unit, 2f * unit, 7f * unit, 3f * unit, 14f * unit)
            cubicTo(4f * unit, 21f * unit, 13f * unit, 24f * unit, 19f * unit, 19f * unit)
            cubicTo(21f * unit, 17f * unit, 21f * unit, 15f * unit, 21f * unit, 14f * unit)
            cubicTo(17f * unit, 17f * unit, 11f * unit, 15f * unit, 10f * unit, 10f * unit)
            cubicTo(9f * unit, 7f * unit, 11f * unit, 4f * unit, 15f * unit, 3f * unit)
            close()
        }
        if (enabled) drawPath(moon, color)
        else drawPath(
            moon, color,
            style = Stroke(width = 1.8f * unit, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}



/**
 * One balanced data-bar cell. The value font shrinks to the available width
 * (and the active font scale) but never below [NavigationHudFormat.MIN_METRIC_SP],
 * so a compact value such as "1h 19m" cannot collapse into an ellipsis; the
 * two-row bar gives the cells the extra width when the screen is narrow.
 */
@Composable
private fun MetricCell(value: String, label: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fontScale = LocalDensity.current.fontScale
        val characters = value.length.coerceAtLeast(1)
        val fitted = (maxWidth.value / (characters * 0.62f)) / fontScale
        val size = fitted.coerceIn(NavigationHudFormat.MIN_METRIC_SP, NavigationHudFormat.MAX_METRIC_SP).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = RideMutedColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Live pace delta: white when the engine has measured enough movement, muted
 * until then. The sign and the gained/lost label carry the direction, so the
 * cell needs no second hue — the ride chrome uses one white and one near-black
 * ([RideSurfaceColor]) everywhere.
 */
@Composable
private fun DeltaCell(
    deltaS: Double,
    modifier: Modifier = Modifier,
) {
    val known = !deltaS.isNaN()
    val color = if (known) Color.White else RideMutedColor
    val value = NavigationHudFormat.deltaText(deltaS)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val fontScale = LocalDensity.current.fontScale
        val characters = value.length.coerceAtLeast(1)
        val fitted = (maxWidth.value / (characters * 0.72f)) / fontScale
        val size = fitted.coerceIn(NavigationHudFormat.MIN_METRIC_SP, NavigationHudFormat.MAX_METRIC_SP).sp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = size,
                fontWeight = FontWeight.SemiBold,
                color = color,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    !known -> "pace"
                    deltaS > 0.0 -> "lost"
                    else -> "gained"
                },
                style = MaterialTheme.typography.labelSmall,
                color = RideMutedColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Hand-drawn maneuver symbol, shaped from GraphHopper's instruction and route geometry. */
internal fun DrawScope.drawTurnArrow(turn: NavigationSnapshot.TurnInfo?, w: Float, h: Float) {
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val paint = Color.White
    val sign = turn?.sign
    if (sign == null) {
        // Keep a recognizable forward arrow while waiting for the next instruction.
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.2f)
            lineTo(w * 0.5f, h * 0.85f)
        }
        drawPath(path, paint, style = stroke)
        drawArrowHead(w * 0.5f, h * 0.2f, 0f, -1f, w, paint, stroke)
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
    val wing = w * 0.12f
    val head = Path().apply {
        moveTo(baseX - uy * wing, baseY + ux * wing)
        lineTo(headX, headY)
        lineTo(baseX + uy * wing, baseY - ux * wing)
    }
    drawPath(head, paint, style = stroke)
}

/**
 * One cell per lane of the road being left, left to right as the rider sees
 * them, divided by dashed lane markings. The recommended lane(s) draw their
 * arrows in full white with a bar beneath; the others stay dim. Count-only
 * lanes (no painted arrows in OSM) show the turn arrow in the suggested lane
 * only. White on the ride surface only, so it reads the same in B&W mode.
 */
@Composable
internal fun LaneGuidanceRow(lanes: LaneGuidance, modifier: Modifier = Modifier) {
    val cellWidth = if (lanes.lanes.size > 8) 22.dp else LaneCellWidth
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics {
            contentDescription = "Lane guidance: " + lanes.description()
            this[RecommendedLanesKey] = NavigationHudFormat.recommendedLanesValue(lanes)
        },
    ) {
        lanes.lanes.forEachIndexed { index, lane ->
            if (index > 0) {
                Canvas(Modifier.size(width = 2.dp, height = LaneCellHeight)) {
                    val dash = size.height / 7f
                    var y = 0f
                    while (y < size.height) {
                        drawLine(
                            RideMutedColor,
                            Offset(size.width / 2f, y),
                            Offset(size.width / 2f, (y + dash).coerceAtMost(size.height)),
                            strokeWidth = size.width,
                        )
                        y += dash * 2f
                    }
                }
            }
            Canvas(Modifier.size(width = cellWidth, height = LaneCellHeight)) {
                val color = if (lane.recommended) Color.White else LaneDimColor
                drawLaneArrows(lane.arrows, lanes.leftHandTraffic, size.width, size.height * 0.84f, color)
                if (lane.recommended) {
                    drawLine(
                        Color.White,
                        Offset(size.width * 0.18f, size.height * 0.96f),
                        Offset(size.width * 0.82f, size.height * 0.96f),
                        strokeWidth = size.height * 0.07f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

private val LaneCellWidth: Dp = 26.dp
private val LaneCellHeight: Dp = 32.dp

/** Non-recommended lane arrows: visible as context, clearly not the pick. */
private val LaneDimColor = Color(0x59FFFFFF)

/** Lane arrows sharing one stem, like painted road markings. */
private fun DrawScope.drawLaneArrows(
    arrows: Set<LaneArrow>,
    leftHandTraffic: Boolean,
    w: Float,
    h: Float,
    color: Color,
) {
    if (arrows.isEmpty()) return
    val stroke = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val cx = w * 0.5f
    val fork = h * 0.52f
    drawLine(color, Offset(cx, h * 0.95f), Offset(cx, fork), strokeWidth = stroke.width, cap = StrokeCap.Round)
    if (LaneArrow.STRAIGHT in arrows) {
        drawLine(color, Offset(cx, fork), Offset(cx, h * 0.12f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawArrowHead(cx, h * 0.12f, 0f, -1f, w, color, stroke)
    }
    for ((arrow, direction) in listOf(LaneArrow.LEFT to -1f, LaneArrow.RIGHT to 1f)) {
        if (arrow !in arrows) continue
        val endX = cx + direction * w * 0.36f
        val endY = h * 0.26f
        drawPath(
            Path().apply {
                moveTo(cx, fork)
                quadraticTo(cx, endY, endX, endY)
            },
            color,
            style = stroke,
        )
        drawArrowHead(endX, endY, direction, 0f, w, color, stroke)
    }
    if (LaneArrow.UTURN in arrows) {
        // Hooks across the oncoming side: right in left-hand traffic.
        val direction = if (leftHandTraffic) 1f else -1f
        val far = cx + direction * w * 0.3f
        drawPath(
            Path().apply {
                moveTo(cx, fork)
                lineTo(cx, h * 0.3f)
                quadraticTo(cx, h * 0.1f, (cx + far) / 2f, h * 0.1f)
                quadraticTo(far, h * 0.1f, far, h * 0.3f)
                lineTo(far, h * 0.5f)
            },
            color,
            style = stroke,
        )
        drawArrowHead(far, h * 0.5f, 0f, 1f, w, color, stroke)
    }
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
    val wing = w * 0.12f
    val baseX = tipX - ux * headLength
    val baseY = tipY - uy * headLength
    val head = Path().apply {
        moveTo(baseX - uy * wing, baseY + ux * wing)
        lineTo(tipX, tipY)
        lineTo(baseX + uy * wing, baseY - ux * wing)
    }
    drawPath(head, color, style = stroke)
}


/** Row layout chosen for the ride data bar (see [NavigationHudFormat.dataBarLayout]). */
enum class DataBarLayout {
    /** All four metrics, media opener and END on one row. */
    SINGLE_ROW,

    /** Two metric rows plus a media/END row, so values stay readable. */
    TWO_ROW,
}


/** Pure formatting helpers, JVM-testable. */
object NavigationHudFormat {
    /** Smallest metric font the data bar ever uses; values stay readable. */
    const val MIN_METRIC_SP: Float = 11.5f

    /** Largest metric font, so a wide screen stays balanced. */
    const val MAX_METRIC_SP: Float = 18f

    /**
     * Minimum width (dp) a metric cell needs to render [value] completely at
     * [MIN_METRIC_SP] and the active font scale. 0.62 em is the em-width
     * approximation the data bar uses to fit values.
     */
    fun metricCellWidthDp(value: String, fontScale: Float): Float {
        val characters = value.length.coerceAtLeast(1)
        return characters * MIN_METRIC_SP * 0.62f * fontScale.coerceAtLeast(0.5f) + 8f
    }

    /**
     * Chooses the data-bar row layout from the real available width. A single
     * row is used only when all four metric cells still render at
     * [MIN_METRIC_SP] beside the glove-sized media opener and END; otherwise
     * the bar switches to the two-row grid, which is the only way to keep
     * every value fully readable at 320 dp / 2x font scale.
     */
    fun dataBarLayout(
        availableWidthDp: Float,
        fontScale: Float,
        metricValues: List<String>,
    ): DataBarLayout {
        val chrome = 2f * GloveTargetDp + 4f + 12f + 8f + 12f
        val required = metricValues.sumOf { metricCellWidthDp(it, fontScale).toDouble() } + chrome
        return if (availableWidthDp >= required) DataBarLayout.SINGLE_ROW else DataBarLayout.TWO_ROW
    }

    /** Data-bar chrome constants, mirrored from the Compose layout. */
    private const val GloveTargetDp = 64f

    fun darkRideMapDescription(enabled: Boolean): String =
        "Dark ride map, ${if (enabled) "on" else "off"}"

    fun voiceSettingsDescription(enabled: Boolean, statusDescription: String? = null): String {
        val base = if (enabled) "Voice guidance settings, enabled" else "Voice guidance settings, disabled"
        val status = statusDescription?.trim()?.takeIf { enabled && it.isNotEmpty() }
        return if (status == null) base else "$base, $status"
    }

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

    /**
     * Narrower duration for the data-bar cell, e.g. "1h 19m" / "45m" / "2h".
     * The shorter form keeps the metric row from wrapping in screenshots and
     * on small displays.
     */
    fun etaCompactText(seconds: Double): String {
        val safe = seconds.coerceAtLeast(0.0)
        val totalMinutes = (safe / 60.0).toInt()
        if (totalMinutes <= 0) return "<1m"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** [RecommendedLanesKey] value: zero-based recommended lane indices over the lane count, e.g. "1,2/3". */
    fun recommendedLanesValue(lanes: LaneGuidance): String =
        lanes.recommendedIndices.joinToString(",") + "/" + lanes.lanes.size +
            if (lanes.marked) "" else " suggested"

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
            Instruction.USE_ROUNDABOUT -> "enter the roundabout"
            Instruction.LEAVE_ROUNDABOUT -> "exit the roundabout"
            Instruction.FERRY -> "take the ferry"
            else -> "continue ahead"
        }
        val description = buildString {
            append("$action in ${distanceText(turn.distanceM)}")
            if (turn.sign == Instruction.USE_ROUNDABOUT) {
                turn.roundaboutExitNumber?.takeIf { it > 0 }?.let {
                    append(", then take the ${ordinal(it)} exit")
                }
            }
        }
        return if (turn.streetName.isBlank()) description else {
            "$description onto ${turn.streetName.trim()}"
        }
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
