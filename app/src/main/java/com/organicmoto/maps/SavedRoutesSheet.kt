package com.organicmoto.maps

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.organicmoto.maps.storage.SavedRoute
import com.organicmoto.maps.storage.SavedRouteComment
import com.organicmoto.maps.storage.SavedRouteSummary
import kotlinx.coroutines.launch

private val TEXT_PRIMARY = Color(0xFF303030)
private val TEXT_SECONDARY = Color(0xFF8A000000)
private val ACCENT_BLUE = Color(0xFF249CF2)

/**
 * The route-storage menu: a bottom sheet listing every saved ride with its
 * mini-map banner, endpoint names, distance/time summary, comment count and
 * per-route comments.
 *
 * Data flows through suspend providers so this composable stays decoupled
 * from the repository instance; mutations call back into the providers to
 * refresh. Tapping an item hands the stored [SavedRoute] to [onLoadRoute].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedRoutesSheet(
    summariesProvider: suspend () -> List<SavedRouteSummary>,
    commentsProvider: suspend (routeId: Long) -> List<SavedRouteComment>,
    onAddComment: suspend (routeId: Long, text: String) -> Unit,
    onLoadRoute: (SavedRoute) -> Unit,
    onDeleteRoute: (SavedRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    var summaries by remember { mutableStateOf<List<SavedRouteSummary>?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedRoute?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshSummaries() {
        scope.launch { summaries = summariesProvider() }
    }

    LaunchedEffect(Unit) {
        summaries = summariesProvider()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Text(
                text = "Saved routes",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = TEXT_PRIMARY,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            when (val list = summaries) {
                null -> Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                ) {
                    CircularProgressIndicator()
                }
                else -> LazyColumn(Modifier.fillMaxWidth()) {
                    if (list.isEmpty()) {
                        item { EmptyState() }
                    }
                    items(list, key = { it.route.id }) { summary ->
                        SavedRouteItem(
                            summary = summary,
                            commentsProvider = commentsProvider,
                            onAddComment = { routeId, text ->
                                onAddComment(routeId, text)
                                refreshSummaries() // keep the count badge honest
                            },
                            onLoad = { onLoadRoute(summary.route) },
                            onDelete = { pendingDelete = summary.route },
                        )
                        HorizontalDivider(color = Color(0x14000000), modifier = Modifier.padding(start = 16.dp))
                    }
                    // Tail padding so the last item clears the nav bar area.
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    pendingDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete saved route?") },
            text = {
                Text("\"${route.fromName} → ${route.toName}\" and its comments will be removed.")
            },
            confirmButton = {
                Button(onClick = {
                    pendingDelete = null
                    onDeleteRoute(route)
                    refreshSummaries()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 40.dp),
    ) {
        BookmarkIcon(filled = false)
        Spacer(Modifier.height(10.dp))
        Text(
            text = "No saved routes yet",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = TEXT_PRIMARY,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "Long-press a coloured route card to save it here.",
            style = MaterialTheme.typography.labelMedium,
            color = TEXT_SECONDARY,
        )
    }
}

@Composable
private fun SavedRouteItem(
    summary: SavedRouteSummary,
    commentsProvider: suspend (routeId: Long) -> List<SavedRouteComment>,
    onAddComment: suspend (routeId: Long, text: String) -> Unit,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val route = summary.route
    var expanded by remember { mutableStateOf(false) }
    var comments by remember(route.id) { mutableStateOf<List<SavedRouteComment>?>(null) }
    var draft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 150),
        label = "commentsChevron",
    )

    // Comments load lazily on first expansion, not for every menu entry.
    LaunchedEffect(expanded, route.id) {
        if (expanded && comments == null) comments = commentsProvider(route.id)
    }

    fun submitDraft() {
        val text = draft.trim()
        if (text.isEmpty()) return
        scope.launch {
            onAddComment(route.id, text)
            draft = ""
            comments = commentsProvider(route.id)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            RouteMiniMap(
                geometry = route.geometry,
                modifier = Modifier
                    .size(width = 92.dp, height = 64.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = route.fromName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TEXT_PRIMARY,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = route.toName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TEXT_SECONDARY,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text =
                        "${formatRouteDuration(route.durationMillis)} · " +
                        "${formatRouteDistance(route.distanceMeters)} · " +
                        complexityLabel(route.complexity),
                    style = MaterialTheme.typography.labelSmall,
                    color = TEXT_SECONDARY,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    Modifier
                        .size(32.dp)
                        .semantics { contentDescription = "Delete saved route" },
                ) {
                    IconButton(onDelete)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { expanded = !expanded },
                ) {
                    CommentBubbleIcon(tint = TEXT_SECONDARY)
                    Spacer(Modifier.width(3.dp))
                    Text(
                        text = summary.commentCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = TEXT_SECONDARY,
                    )
                    ChevronIcon(tint = TEXT_SECONDARY, modifier = Modifier.rotate(chevronRotation))
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(top = 10.dp)) {
                val loadedComments = comments.orEmpty()
                if (loadedComments.isEmpty()) {
                    Text(
                        text = "No comments yet.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TEXT_SECONDARY,
                    )
                } else {
                    loadedComments.forEach { comment ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = comment.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = TEXT_PRIMARY,
                            )
                            Text(
                                text = DateUtils
                                    .getRelativeTimeSpanString(comment.createdAtMillis)
                                    .toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = TEXT_SECONDARY,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Add a comment", style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val enabled = draft.isNotBlank()
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (enabled) Color(0xFFEAF4FE) else Color(0xFFF0F0F0))
                            .semantics { contentDescription = "Add comment" }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = enabled,
                            ) { submitDraft() },
                    ) {
                        CheckIcon(tint = if (enabled) ACCENT_BLUE else Color(0xFFBDBDBD))
                    }
                }
            }
        }
    }
}

/** Compact icon button wrapper keeping the trash action at 32 dp. */
@Composable
private fun IconButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        TrashIcon()
    }
}
