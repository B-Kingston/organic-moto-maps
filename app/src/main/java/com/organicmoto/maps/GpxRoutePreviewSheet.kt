package com.organicmoto.maps

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.storage.GpxMilestone
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val MILESTONE_ROW_HEIGHT = 76.dp
private val PREVIEW_BLUE = Color(0xFF249CF2)

/**
 * Distributes snap targets over the list's real scroll range. The eased
 * curve leaves Start and Finish flush with the list bounds while reserving
 * distinct scroll positions for the milestones next to them.
 */
private object GpxSelectionSnapPosition : SnapPosition {
    override fun position(
        layoutSize: Int,
        itemSize: Int,
        beforeContentPadding: Int,
        afterContentPadding: Int,
        itemIndex: Int,
        itemCount: Int,
    ): Int {
        if (itemCount <= 1) return beforeContentPadding
        val scrollRange = (
            beforeContentPadding + itemCount * itemSize + afterContentPadding - layoutSize
        ).coerceAtLeast(0)
        val progress = itemIndex.toFloat() / (itemCount - 1)
        val curvedProgress = progress * progress * (3f - 2f * progress)
        val targetScroll = (scrollRange * curvedProgress).roundToInt()
        return beforeContentPadding + itemIndex * itemSize - targetScroll
    }
}

/**
 * A route scrubber disguised as a readable waypoint list. The item nearest
 * the curved selection slot snaps into its detent and drives the map camera.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpxRoutePreviewSheet(
    routeName: String,
    milestones: List<GpxMilestone>,
    onFocusMilestone: (GpxMilestone) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val flingBehavior = rememberSnapFlingBehavior(listState, GpxSelectionSnapPosition)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val rowHeightPx = with(LocalDensity.current) { MILESTONE_ROW_HEIGHT.roundToPx() }
    var focusedIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState, milestones) {
        snapshotFlow {
            val info = listState.layoutInfo
            info.visibleItemsInfo.minByOrNull { item ->
                abs(
                    item.offset - GpxSelectionSnapPosition.position(
                        layoutSize = info.viewportSize.height,
                        itemSize = item.size,
                        beforeContentPadding = info.beforeContentPadding,
                        afterContentPadding = info.afterContentPadding,
                        itemIndex = item.index,
                        itemCount = info.totalItemsCount,
                    ),
                )
            }?.index
        }.distinctUntilChanged().collect { index ->
            if (index != null && index in milestones.indices) {
                if (index != focusedIndex) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                focusedIndex = index
                onFocusMilestone(milestones[index])
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        scrimColor = Color.Transparent,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 16.dp, top = 2.dp, bottom = 8.dp)
                ) {
                    Text("Preview route", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        routeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0x99000000),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "Close route preview" },
                ) { Text("Close") }
            }
            HorizontalDivider(color = Color(0x18000000))
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = "GPX route milestones" },
                ) {
                    itemsIndexed(milestones, key = { index, item -> "$index:${item.progress}" }) { index, item ->
                        MilestoneRow(
                            milestone = item,
                            index = index,
                            count = milestones.size,
                            focused = index == focusedIndex,
                            onClick = {
                                focusedIndex = index
                                scope.launch {
                                    val info = listState.layoutInfo
                                    val targetPosition = GpxSelectionSnapPosition.position(
                                        layoutSize = info.viewportSize.height,
                                        itemSize = info.visibleItemsInfo.firstOrNull()?.size ?: rowHeightPx,
                                        beforeContentPadding = info.beforeContentPadding,
                                        afterContentPadding = info.afterContentPadding,
                                        itemIndex = index,
                                        itemCount = milestones.size,
                                    )
                                    listState.animateScrollToItem(index, -targetPosition)
                                }
                                onFocusMilestone(item)
                            },
                        )
                    }
                }
                // The selection rule makes the scroll-to-map relationship visible.
                Canvas(Modifier.fillMaxSize()) {
                    drawLine(
                        color = PREVIEW_BLUE.copy(alpha = 0.42f),
                        start = Offset(0f, size.height / 2f),
                        end = Offset(12.dp.toPx(), size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                    )
                    drawLine(
                        color = PREVIEW_BLUE.copy(alpha = 0.42f),
                        start = Offset(size.width - 12.dp.toPx(), size.height / 2f),
                        end = Offset(size.width, size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MilestoneRow(
    milestone: GpxMilestone,
    index: Int,
    count: Int,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val title = milestone.placeName ?: when (index) {
        0 -> "Route start"
        count - 1 -> "Route finish"
        else -> "Waypoint ${index + 1}"
    }
    val distance = if (index == 0) "Start" else "${formatRouteDistance(milestone.distanceMeters)} from start"
    val detail = listOfNotNull(milestone.placeDetail?.takeIf(String::isNotBlank), distance)
        .joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(MILESTONE_ROW_HEIGHT)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                if (focused) PREVIEW_BLUE.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$title, $distance${if (focused) ", focused on map" else ""}"
            }
            .padding(horizontal = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(36.dp)
                .background(if (focused) PREVIEW_BLUE else Color(0xFFE1E5E8), CircleShape),
        ) {
            Text(
                when (index) { 0 -> "S"; count - 1 -> "F"; else -> index.toString() },
                color = if (focused) Color.White else Color(0xFF404040),
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0x99000000),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("${(milestone.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = PREVIEW_BLUE)
    }
}
