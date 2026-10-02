package com.organicmoto.maps

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal fun complexityLabel(value: Float): String =
    if (value < 0.5f) "Fastest" else "Curvy route ${value.roundToInt()}"

/** Clicks per knob revolution; one click = one level = +1.0 complexity. */
private const val KNOB_LEVELS_PER_REVOLUTION = 8

/**
 * An endless click-stop rotary control: [KNOB_LEVELS_PER_REVOLUTION] detent
 * clicks per revolution, each clockwise click adding one complexity level
 * (+1.0). Winding past the eighth level keeps counting into the next
 * revolution (9, 10, ...); counter-clockwise rotation stops hard at zero.
 * Every crossed detent fires a short haptic tick, and release snaps to the
 * nearest level with a small spring settle.
 */
@Composable
internal fun InfiniteComplexityKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnFinished by rememberUpdatedState(onValueChangeFinished)
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // The painted angle chases the committed value: exact finger tracking
    // while dragging (snapTo), then a short mechanical bounce onto the
    // clicked detent after release (animateTo).
    val paintedLevels = remember { Animatable(value) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { latestValue }.collect { committed ->
            // External value changes (saved-route restore) must move the
            // painted indicator even though no drag produced them.
            if (!dragging && paintedLevels.value != committed) {
                paintedLevels.animateTo(
                    committed,
                    spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
        }
    }
    Canvas(
        modifier = modifier
            .size(92.dp)
            .semantics {
                contentDescription = "Ride complexity level ${value.roundToInt()}"
            }
            .pointerInput(Unit) {
                var previousAngle = 0f
                var gestureValue = 0f
                fun hapticTick(level: Int) {
                    // CLOCK_TICK is the platform's rotary-detent click; the
                    // Fastest end stop gets the firmer VIRTUAL_KEY pulse.
                    view.performHapticFeedback(
                        if (level <= 0) HapticFeedbackConstants.VIRTUAL_KEY
                        else HapticFeedbackConstants.CLOCK_TICK,
                    )
                }
                detectDragGestures(
                    onDragStart = { position ->
                        dragging = true
                        previousAngle = atan2(
                            position.y - size.height / 2f,
                            position.x - size.width / 2f,
                        )
                        gestureValue = latestValue
                        scope.launch { paintedLevels.snapTo(gestureValue) }
                    },
                    onDragEnd = {
                        dragging = false
                        val snapped = gestureValue.roundToInt()
                        if (snapped != gestureValue.toInt()) {
                            // Release crossed one more detent than the drag
                            // ticks announced: click that detent too.
                            hapticTick(snapped)
                        }
                        gestureValue = snapped.toFloat()
                        latestOnValueChange(gestureValue)
                        latestOnFinished(gestureValue)
                        scope.launch {
                            paintedLevels.animateTo(
                                snapped.toFloat(),
                                spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    },
                    onDragCancel = {
                        dragging = false
                        // Interrupted gesture: settle back onto a whole detent
                        // instead of leaving a fractional level in state (no
                        // re-route fires for cancelled gestures).
                        gestureValue = gestureValue.roundToInt().toFloat()
                        latestOnValueChange(gestureValue)
                    },
                    onDrag = { change, _ ->
                        val angle = atan2(
                            change.position.y - size.height / 2f,
                            change.position.x - size.width / 2f,
                        )
                        var delta = angle - previousAngle
                        if (delta > PI) delta -= (2.0 * PI).toFloat()
                        if (delta < -PI) delta += (2.0 * PI).toFloat()
                        previousAngle = angle
                        val levelsPerRadian =
                            KNOB_LEVELS_PER_REVOLUTION / (2.0 * PI).toFloat()
                        val newValue = (gestureValue + delta * levelsPerRadian)
                            .coerceAtLeast(0f)
                        if (newValue.toInt() != gestureValue.toInt()) {
                            hapticTick(newValue.toInt())
                        }
                        gestureValue = newValue
                        latestOnValueChange(gestureValue)
                        scope.launch { paintedLevels.snapTo(newValue) }
                        change.consume()
                    },
                )
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.47f
        val knobRadius = size.minDimension * 0.34f

        drawCircle(Color(0x18000000), outerRadius, center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFE7E7E7), outerRadius, center)
        repeat(KNOB_LEVELS_PER_REVOLUTION) { index ->
            val angle = Math.toRadians(-135.0 + index * 45.0)
            val dotCenter = center + Offset(
                (cos(angle) * outerRadius * 0.84).toFloat(),
                (sin(angle) * outerRadius * 0.84).toFloat(),
            )
            val level = value.roundToInt().coerceAtLeast(0)
            val activeDot = ((level % KNOB_LEVELS_PER_REVOLUTION) +
                KNOB_LEVELS_PER_REVOLUTION) % KNOB_LEVELS_PER_REVOLUTION
            drawCircle(
                color = when {
                    index == 0 && level <= 0 -> Color(0xFF249CF2)
                    index == activeDot && level > 0 -> Color(0xFF249CF2)
                    else -> Color(0xFF8A8A8A)
                },
                radius = 2.dp.toPx(),
                center = dotCenter,
            )
        }
        drawCircle(Color(0x22000000), knobRadius + 2.dp.toPx(), center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFF7F7F7), knobRadius, center)
        drawCircle(
            Color(0xFFE0E0E0),
            knobRadius,
            center,
            style = Stroke(width = 1.dp.toPx()),
        )

        val indicatorAngle = Math.toRadians(
            -135.0 + paintedLevels.value.toDouble() * (360.0 / KNOB_LEVELS_PER_REVOLUTION),
        )
        val indicatorStart = center + Offset(
            (cos(indicatorAngle) * knobRadius * 0.48).toFloat(),
            (sin(indicatorAngle) * knobRadius * 0.48).toFloat(),
        )
        val indicatorEnd = center + Offset(
            (cos(indicatorAngle) * knobRadius * 0.78).toFloat(),
            (sin(indicatorAngle) * knobRadius * 0.78).toFloat(),
        )
        drawLine(
            color = Color(0xFF249CF2),
            start = indicatorStart,
            end = indicatorEnd,
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
@Composable
internal fun SettingsCogIcon() {
    Canvas(Modifier.size(20.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        repeat(8) { index ->
            val angle = Math.toRadians(index * 45.0)
            drawLine(
                color = Color(0xFF616161),
                start = center + Offset(
                    (cos(angle) * size.minDimension * 0.28).toFloat(),
                    (sin(angle) * size.minDimension * 0.28).toFloat(),
                ),
                end = center + Offset(
                    (cos(angle) * size.minDimension * 0.43).toFloat(),
                    (sin(angle) * size.minDimension * 0.43).toFloat(),
                ),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        drawCircle(
            color = Color(0xFF616161),
            radius = size.minDimension * 0.29f,
            center = center,
            style = Stroke(width = 2.5.dp.toPx()),
        )
        drawCircle(Color(0xFF616161), size.minDimension * 0.08f, center)
    }
}

/**
 * Screen header for the screens opened from the settings cog: a 48 dp back
 * button that returns to the planner, then the screen title. Every screen
 * opened from the cog exposes this back button as its exit affordance.
 */
@Composable
internal fun SettingsScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(48.dp)
                .offset(x = (-12).dp)
                .semantics { contentDescription = "Back" },
        ) { BackArrowIcon() }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** Hand-drawn back chevron with a tail, matching the app's Canvas icons. */
@Composable
internal fun BackArrowIcon(tint: Color = Color(0xFF303030)) {
    Canvas(Modifier.size(20.dp)) {
        val strokeWidth = 2.4.dp.toPx()
        drawPath(
            path = Path().apply {
                moveTo(size.width * 0.64f, size.height * 0.17f)
                lineTo(size.width * 0.32f, size.height * 0.50f)
                lineTo(size.width * 0.64f, size.height * 0.83f)
            },
            color = tint,
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.32f, size.height * 0.50f),
            end = Offset(size.width * 0.86f, size.height * 0.50f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
internal fun RouteSettingsDialog(
    currentPercent: Float,
    blockUnpaved: Boolean,
    onDismiss: () -> Unit,
    onApply: (Float, Boolean) -> Unit,
) {
    var draftPercent by remember(currentPercent) { mutableStateOf(currentPercent) }
    var draftBlockUnpaved by remember(blockUnpaved) { mutableStateOf(blockUnpaved) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { SettingsScreenHeader(title = "Route settings", onBack = onDismiss) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Target maximum shared roads",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF8A000000),
                )
                Text(
                    "${draftPercent.roundToInt()}%",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    RoadShareKnob(
                        value = draftPercent,
                        onValueChange = { draftPercent = it },
                    )
                }
                Text(
                    "Lower values ask routes to use more different roads. " +
                        "Higher values allow more overlap. If the road network cannot meet the target, " +
                            "the most distinct sensible route is still shown.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8A000000),
                )
                HorizontalDivider(Modifier.padding(vertical = 16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Block unpaved roads",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Exclude roads tagged as unpaved, gravel, dirt, ground, grass, or sand.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF8A000000),
                        )
                    }
                    Switch(
                        checked = draftBlockUnpaved,
                        onCheckedChange = { draftBlockUnpaved = it },
                        modifier = Modifier.semantics {
                            contentDescription = "Block unpaved roads"
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(draftPercent, draftBlockUnpaved) }) { Text("APPLY") }
        },
    )
}

/** Finite 10–90% dial with tactile-style 5% visual detents. */
@Composable
private fun RoadShareKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    Canvas(
        modifier = Modifier
            .size(150.dp)
            .semantics {
                contentDescription = "Maximum shared roads ${value.roundToInt()} percent"
            }
            .pointerInput(Unit) {
                fun updateFrom(position: Offset) {
                    var degrees = Math.toDegrees(
                        atan2(
                            position.y - size.height / 2f,
                            position.x - size.width / 2f,
                        ).toDouble()
                    )
                    if (degrees < 0.0) degrees += 360.0
                    var sweep = (degrees - 135.0 + 360.0) % 360.0
                    if (sweep > 270.0) sweep = if (sweep < 315.0) 270.0 else 0.0
                    val raw = 10f + (sweep / 270.0 * 80.0).toFloat()
                    latestOnValueChange((raw / 5f).roundToInt() * 5f)
                }
                detectDragGestures(
                    onDragStart = { updateFrom(it) },
                    onDrag = { change, _ ->
                        updateFrom(change.position)
                        change.consume()
                    },
                )
            },
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = size.minDimension * 0.46f
        val knobRadius = size.minDimension * 0.31f
        val fraction = ((value.coerceIn(10f, 90f) - 10f) / 80f)

        drawCircle(Color(0x16000000), outerRadius, center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFEAEAEA), outerRadius, center)
        repeat(17) { index ->
            val angle = Math.toRadians(135.0 + index * (270.0 / 16.0))
            val dot = center + Offset(
                (cos(angle) * outerRadius * 0.84).toFloat(),
                (sin(angle) * outerRadius * 0.84).toFloat(),
            )
            drawCircle(
                color = if (index <= (fraction * 16f).roundToInt()) {
                    Color(0xFF249CF2)
                } else {
                    Color(0xFF929292)
                },
                radius = if (index % 2 == 0) 2.4.dp.toPx() else 1.7.dp.toPx(),
                center = dot,
            )
        }
        drawCircle(Color(0x22000000), knobRadius + 2.dp.toPx(), center + Offset(0f, 2.dp.toPx()))
        drawCircle(Color(0xFFF9F9F9), knobRadius, center)
        drawCircle(
            Color(0xFFD9D9D9),
            knobRadius,
            center,
            style = Stroke(width = 1.dp.toPx()),
        )
        val indicatorAngle = Math.toRadians(135.0 + fraction * 270.0)
        drawLine(
            color = Color(0xFF249CF2),
            start = center + Offset(
                (cos(indicatorAngle) * knobRadius * 0.45).toFloat(),
                (sin(indicatorAngle) * knobRadius * 0.45).toFloat(),
            ),
            end = center + Offset(
                (cos(indicatorAngle) * knobRadius * 0.78).toFloat(),
                (sin(indicatorAngle) * knobRadius * 0.78).toFloat(),
            ),
            strokeWidth = 5.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
