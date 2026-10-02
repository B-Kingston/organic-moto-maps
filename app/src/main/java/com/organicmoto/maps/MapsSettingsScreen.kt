package com.organicmoto.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.region.CatalogAction
import com.organicmoto.maps.region.DownloadPhase
import com.organicmoto.maps.region.DownloadProgress
import com.organicmoto.maps.region.InstalledRegion
import com.organicmoto.maps.region.InterruptedDownload
import com.organicmoto.maps.region.MapsState
import com.organicmoto.maps.region.RecoveredPackage
import com.organicmoto.maps.region.RegionSettings
import com.organicmoto.maps.region.RegionUiModel
import com.organicmoto.maps.region.SavedPackage
import com.organicmoto.maps.region.ServerRegion

/** Minimum touch target for every control on this screen. */
private val MAPS_TARGET_HEIGHT = 48.dp

// One spacing scale for the whole screen: 16 dp screen gutter and card
// padding, 12 dp between cards and between sibling controls, 8 dp between a
// line of text and the control it describes, 4 dp between stacked text lines.
private val ScreenGutter = 16.dp
private val CardPadding = 16.dp
private val CardGap = 12.dp
private val ControlGap = 12.dp
private val TextToControl = 8.dp
private val LineGap = 4.dp

/**
 * The map-side action rail (cog + saved routes) stays reachable above this
 * screen in the upper-right reach zone. The header block reserves its column
 * and height so no card, field, or button ever slides underneath it: the rail
 * spans roughly 33–136 dp below the status bar and its right 57 dp.
 */
private val RailClearanceEnd = 64.dp
private val RailClearanceHeight = 140.dp

private val ScreenBackground = Color(0xFFF2F3F5)
private val CardBackground = Color.White
private val CardBorder = Color(0x14000000)
private val Ink = Color(0xFF1C1C1E)
private val MutedInk = Color(0xFF6B7280)
private val Accent = Color(0xFF249CF2)
private val AccentInk = Color(0xFF0D2137)
private val AccentTint = Color(0xFFE6F3FE)
private val Positive = Color(0xFF1A7F37)
private val PositiveTint = Color(0xFFE7F5EC)
private val Warning = Color(0xFF9A6700)
private val WarningTint = Color(0xFFFFF4D6)
private val Danger = Color(0xFFB42318)
private val DangerTint = Color(0xFFFDECEA)
private val Disabled = Color(0xFFB9BDC4)

private enum class Tone { NEUTRAL, ACCENT, POSITIVE, WARNING, DANGER }

private fun Tone.ink(): Color = when (this) {
    Tone.NEUTRAL -> MutedInk
    Tone.ACCENT -> AccentInk
    Tone.POSITIVE -> Positive
    Tone.WARNING -> Warning
    Tone.DANGER -> Danger
}

private fun Tone.tint(): Color = when (this) {
    Tone.NEUTRAL -> Color(0xFFEDEEF0)
    Tone.ACCENT -> AccentTint
    Tone.POSITIVE -> PositiveTint
    Tone.WARNING -> WarningTint
    Tone.DANGER -> DangerTint
}

/**
 * Maps screen: configure the regional map server, request/download/install
 * complete region packages, import an exported package offline, and manage the
 * installed maps.
 *
 * Downloading and installing are deliberately separate: a download writes a
 * `.motomap` file to a location the user picks in the system file dialog, and
 * installation only happens when the user imports a file. Deleting an installed
 * map removes only the app-managed copy — the file in Files is user-managed.
 * Activation and active-map deletion are refused while a ride is active:
 * switching datasets mid-guidance would pull the graph out from under the
 * router.
 *
 * Layout: a header block (title, one-line purpose, the active map) that
 * clears the action rail, then one card per job — server, available regions,
 * the current transfer, installed maps, and offline import — on a single
 * 16/12/8/4 dp spacing scale.
 */
@Composable
internal fun MapsSettingsScreen(
    state: MapsState,
    rideActive: Boolean,
    onDismiss: () -> Unit,
    onCheckServer: (String) -> Unit,
    onToggleInsecure: (Boolean) -> Unit,
    onRequestBuild: (String) -> Unit,
    onCancelBuild: () -> Unit,
    onDownloadPackage: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onSaveRecoveredPackage: () -> Unit,
    onImportRecoveredPackage: () -> Unit,
    onImportSavedPackage: () -> Unit,
    onImportPackage: () -> Unit,
    onActivate: (String) -> Unit,
    onActivateBundled: () -> Unit,
    onRemove: (String) -> Unit,
    onDismissMessage: () -> Unit = {},
) {
    var addressDraft by remember(state.serverAddress) { mutableStateOf(state.serverAddress) }
    var deleteTarget by remember { mutableStateOf<InstalledRegion?>(null) }
    Surface(
        color = ScreenBackground,
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                this[ActiveRegionKey] = state.activeInstallId ?: "bundled"
                this[InstalledRegionCountKey] = state.installed.size
                this[MapsServerCheckedKey] = state.serverChecked
                this[MapsActiveJobKey] = state.activeJob?.state ?: "none"
                this[MapsDownloadingKey] = state.download != null
                this[MapsRideActiveKey] = rideActive
                this[MapsSavedFileKey] = state.savedPackage?.fileName ?: "none"
                this[MapsInterruptedKey] = state.interrupted != null
                this[MapsPendingRemovalKey] = state.pendingRemoval?.installId ?: "none"
            },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(CardGap),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = ScreenGutter, end = ScreenGutter, top = 8.dp, bottom = 24.dp),
        ) {
            HeaderBlock(state = state, onDismiss = onDismiss)

            state.error?.let { message ->
                MessageBanner(message, Tone.DANGER, "Dismiss error", onDismissMessage)
            }
            state.notice?.let { message ->
                MessageBanner(message, Tone.POSITIVE, "Dismiss message", onDismissMessage)
            }

            ServerCard(
                state = state,
                addressDraft = addressDraft,
                onAddressChange = { addressDraft = it },
                onCheckServer = { onCheckServer(addressDraft) },
                onToggleInsecure = onToggleInsecure,
            )

            if (state.catalog.isNotEmpty()) {
                MapsCard(title = "Available regions") {
                    state.catalog.forEachIndexed { index, region ->
                        if (index > 0) RowDivider()
                        CatalogRow(
                            region = region,
                            state = state,
                            onRequestBuild = onRequestBuild,
                            onDownloadPackage = onDownloadPackage,
                            onCancelBuild = onCancelBuild,
                        )
                    }
                }
            }

            state.download?.let { progress ->
                MapsCard(
                    title = when (progress.phase) {
                        DownloadPhase.DOWNLOADING -> "Download"
                        DownloadPhase.VERIFYING -> "Verifying"
                        DownloadPhase.SAVING -> "Saving"
                    },
                ) { DownloadRow(progress = progress, onCancel = onCancelDownload) }
            }
            state.savedPackage?.let { saved ->
                MapsCard(title = "Saved to device") {
                    SavedPackageRow(saved = saved, busy = state.busy, onImportSavedPackage = onImportSavedPackage)
                }
            }
            state.recoveredPackage?.let { recovered ->
                MapsCard(title = "Downloaded package in app storage") {
                    RecoveredPackageRow(
                        recovered = recovered,
                        busy = state.busy || state.download != null,
                        onSaveRecoveredPackage = onSaveRecoveredPackage,
                        onImportRecoveredPackage = onImportRecoveredPackage,
                    )
                }
            }
            state.interrupted?.let { interrupted ->
                MapsCard(title = "Interrupted download") {
                    InterruptedRow(
                        interrupted = interrupted,
                        enabled = state.download == null,
                        onResumeDownload = onResumeDownload,
                    )
                }
            }

            MapsCard(title = "Installed maps") {
                if (state.installed.isEmpty()) {
                    Text(
                        RegionUiModel.installedEmptySummary(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MutedInk,
                    )
                    RowDivider()
                }
                BundledRow(
                    active = state.activeInstallId == null,
                    rideActive = rideActive,
                    onActivateBundled = onActivateBundled,
                )
                state.installed.forEach { install ->
                    RowDivider()
                    InstalledRow(
                        install = install,
                        active = state.activeInstallId == install.installId,
                        rideActive = rideActive,
                        removing = state.pendingRemoval?.installId == install.installId,
                        busy = state.busy,
                        onActivate = onActivate,
                        onDelete = { deleteTarget = install },
                    )
                }
            }

            MapsCard(title = "Offline import") {
                Text(
                    "Install a .motomap file you downloaded or were given — no server needed. " +
                        "The file is copied into app storage and left where it is.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedInk,
                )
                Spacer(Modifier.height(ControlGap))
                SecondaryButton(
                    text = "Import package file",
                    description = "Import package file",
                    onClick = onImportPackage,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "A region package bundles the basemap, the routing graph, and offline place search. " +
                    "Packages are built by a map server you control; downloads are saved as a " +
                    ".motomap file where you choose, and importing installs it. Once installed, " +
                    "everything works offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MutedInk,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        deleteTarget?.let { target ->
            val active = state.activeInstallId == target.installId
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                containerColor = CardBackground,
                title = { Text("Delete installed map?") },
                text = { Text(RegionUiModel.deleteConfirmation(target, active)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteTarget = null
                            onRemove(target.installId)
                        },
                        modifier = Modifier
                            .heightIn(min = MAPS_TARGET_HEIGHT)
                            .semantics { contentDescription = "Confirm delete ${target.regionName} map" },
                    ) { Text("Delete", color = Danger, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { deleteTarget = null },
                        modifier = Modifier
                            .heightIn(min = MAPS_TARGET_HEIGHT)
                            .semantics { contentDescription = "Cancel delete ${target.regionName} map" },
                    ) { Text("Cancel", color = AccentInk) }
                },
            )
        }
    }
}

// --- layout building blocks ---------------------------------------------------

@Composable
private fun HeaderBlock(state: MapsState, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RailClearanceHeight)
            .padding(end = RailClearanceEnd),
    ) {
        SettingsScreenHeader(title = "Maps", onBack = onDismiss)
        Text(
            "Offline region packages: map, routing and place search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MutedInk,
            modifier = Modifier.padding(top = LineGap),
        )
        Spacer(Modifier.height(ControlGap))
        // One accessibility node: "Active map Queensland (bundled)".
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Active map ${state.activeRegionName}"
            },
        ) {
            StatusDot(if (state.pendingRemoval != null) Warning else Positive)
            Spacer(Modifier.width(8.dp))
            Text(
                "Active map",
                style = MaterialTheme.typography.labelLarge,
                color = MutedInk,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                state.activeRegionName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
        }
    }
}

@Composable
private fun MapsCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardBackground, shape)
            .border(1.dp, CardBorder, shape)
            .padding(CardPadding),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ControlGap),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            trailing?.invoke()
        }
        content()
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = Color(0xFFE8E9EC),
        modifier = Modifier.padding(vertical = ControlGap),
    )
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

@Composable
private fun StatusPill(text: String, tone: Tone) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = tone.ink(),
        modifier = Modifier
            .background(tone.tint(), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun MessageBanner(text: String, tone: Tone, dismissDescription: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(tone.tint(), shape)
            .padding(start = CardPadding, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = tone.ink(),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .size(MAPS_TARGET_HEIGHT)
                .semantics { contentDescription = dismissDescription },
        ) { CloseGlyph(tone.ink()) }
    }
}

@Composable
private fun CloseGlyph(color: Color) {
    Canvas(Modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset.Zero, Offset(size.width, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFE5E7EB),
            disabledContentColor = Color(0xFF9CA3AF),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = MAPS_TARGET_HEIGHT)
            .semantics { contentDescription = description },
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(
    text: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentColor: Color = AccentInk,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = contentColor,
            disabledContentColor = Color(0xFF9CA3AF),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = modifier
            .heightIn(min = MAPS_TARGET_HEIGHT)
            .semantics { contentDescription = description },
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
private fun SecondaryText(text: String, color: Color = MutedInk) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}

// --- cards ----------------------------------------------------------------------

@Composable
private fun ServerCard(
    state: MapsState,
    addressDraft: String,
    onAddressChange: (String) -> Unit,
    onCheckServer: () -> Unit,
    onToggleInsecure: (Boolean) -> Unit,
) {
    val canCheck = !state.busy && addressDraft.isNotBlank()
    val connectedHere = state.serverChecked && addressDraft.trim() == state.serverAddress
    MapsCard(
        title = "Map server",
        trailing = {
            if (connectedHere) {
                StatusPill("CONNECTED", Tone.POSITIVE)
            } else {
                StatusPill("NOT CONNECTED", Tone.NEUTRAL)
            }
        },
    ) {
        OutlinedTextField(
            value = addressDraft,
            onValueChange = onAddressChange,
            label = { Text("Server address") },
            placeholder = { Text(RegionSettings.SERVER_ADDRESS_HINT) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                focusedLabelColor = AccentInk,
                cursorColor = Accent,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onGo = { if (canCheck) onCheckServer() }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Map server address" },
        )
        Spacer(Modifier.height(ControlGap))
        PrimaryButton(
            text = if (state.serverChecked) "Refresh regions" else "Check server",
            description = "Check server",
            onClick = onCheckServer,
            enabled = canCheck,
            modifier = Modifier.fillMaxWidth(),
        )
        if (connectedHere) {
            Spacer(Modifier.height(TextToControl))
            val details = buildList {
                if (state.serverVersion.isNotBlank()) add("v${state.serverVersion}")
                add(
                    when (state.catalog.size) {
                        0 -> "no regions offered"
                        1 -> "1 region"
                        else -> "${state.catalog.size} regions"
                    },
                )
            }
            SecondaryText("Connected · ${details.joinToString(" · ")}", Positive)
            if (!state.generationEnabled) {
                Spacer(Modifier.height(LineGap))
                SecondaryText(
                    state.generationMessage.ifBlank {
                        "This server serves ready packages only; it cannot build new ones."
                    },
                    Warning,
                )
            }
        }
        if (BuildConfig.DEBUG) {
            RowDivider()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Allow insecure HTTP", style = MaterialTheme.typography.bodyMedium, color = Ink)
                    Spacer(Modifier.height(LineGap))
                    SecondaryText(
                        "Debug builds only, and only for local/private addresses. Release builds require HTTPS.",
                    )
                }
                Spacer(Modifier.width(ControlGap))
                Switch(
                    checked = state.allowInsecureLocal,
                    onCheckedChange = onToggleInsecure,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Accent,
                        checkedBorderColor = Accent,
                        uncheckedThumbColor = Color(0xFF8E9299),
                        uncheckedTrackColor = Color(0xFFE5E7EB),
                        uncheckedBorderColor = Color(0xFFC9CCD1),
                    ),
                    modifier = Modifier.semantics { contentDescription = "Allow insecure HTTP" },
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(
    region: ServerRegion,
    state: MapsState,
    onRequestBuild: (String) -> Unit,
    onDownloadPackage: (String) -> Unit,
    onCancelBuild: () -> Unit,
) {
    val model = RegionUiModel.catalogRow(
        region = region,
        activeJob = state.activeJob,
        busy = state.busy,
        downloadActive = state.download != null,
        pendingRemoval = state.pendingRemoval != null,
        installed = state.installed,
    )
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(region.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = Ink)
        SecondaryText(
            listOfNotNull(
                region.coverage,
                "source ${region.sourceDate}",
                if (region.sourcePinned) "pinned" else "hash recorded at build time",
            ).joinToString(" · "),
        )
        if (region.hasCachedArtifact && region.artifact != null) {
            SecondaryText("${RegionUiModel.formatBytes(region.artifact.size)} download")
        }
        model.statusLine?.let { status ->
            SecondaryText(
                status,
                when {
                    model.errorLine != null -> Danger
                    status.startsWith("This version is installed") -> Positive
                    else -> MutedInk
                },
            )
        }
        if (model.showProgress) {
            Spacer(Modifier.height(LineGap))
            LinearProgressIndicator(
                progress = { state.activeJob?.progress?.toFloat()?.coerceIn(0f, 1f) ?: 0f },
                color = Accent,
                trackColor = AccentTint,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        model.errorLine?.let { error -> SecondaryText(error, Danger) }
        Spacer(Modifier.height(TextToControl - LineGap))
        if (model.showCancel) {
            SecondaryButton(
                text = "Cancel build",
                description = "Cancel build ${region.id}",
                onClick = onCancelBuild,
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (model.action != CatalogAction.NONE) {
            PrimaryButton(
                text = model.actionLabel,
                description = model.actionDescription,
                onClick = {
                    if (model.action == CatalogAction.DOWNLOAD) {
                        onDownloadPackage(region.id)
                    } else {
                        onRequestBuild(region.id)
                    }
                },
                enabled = model.actionEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DownloadRow(progress: DownloadProgress, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            when (progress.phase) {
                DownloadPhase.DOWNLOADING -> "Downloading ${progress.regionName}"
                DownloadPhase.VERIFYING -> "Checking ${progress.regionName} for damage…"
                DownloadPhase.SAVING -> "Saving ${progress.regionName} to your file…"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
        )
        when (progress.phase) {
            DownloadPhase.DOWNLOADING -> {
                Spacer(Modifier.height(LineGap))
                LinearProgressIndicator(
                    progress = { progress.fraction ?: 0f },
                    color = if (progress.retry != null) Warning else Accent,
                    trackColor = if (progress.retry != null) WarningTint else AccentTint,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(LineGap))
                Row(Modifier.fillMaxWidth()) {
                    SecondaryText(
                        buildString {
                            append(RegionUiModel.formatBytes(progress.bytes))
                            progress.total?.let { append(" of ").append(RegionUiModel.formatBytes(it)) }
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    progress.fraction?.let { SecondaryText("${(it * 100).toInt()}%") }
                }
                progress.retry?.let { retry ->
                    SecondaryText(
                        "${retry.reason}. Retrying in ${retry.secondsUntilRetry}s " +
                            "(attempt ${retry.attempt} of ${retry.maxAttempts}) — downloaded bytes are kept.",
                        Warning,
                    )
                }
                Spacer(Modifier.height(TextToControl - LineGap))
                SecondaryButton(
                    text = "Cancel download",
                    description = "Cancel download",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DownloadPhase.VERIFYING -> {
                Spacer(Modifier.height(LineGap))
                LinearProgressIndicator(color = Accent, trackColor = AccentTint, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(LineGap))
                SecondaryText("The download finished; its SHA-256 is being compared with the server's.")
                Spacer(Modifier.height(TextToControl - LineGap))
                SecondaryButton(
                    text = "Cancel download",
                    description = "Cancel download",
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            DownloadPhase.SAVING -> {
                SecondaryText("The download is complete; the file is being copied to the location you chose.")
            }
        }
    }
}

@Composable
private fun SavedPackageRow(saved: SavedPackage, busy: Boolean, onImportSavedPackage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            "Saved \"${saved.fileName}\" (${RegionUiModel.formatBytes(saved.bytes)}) to your device.",
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
        )
        SecondaryText("It stays in your Files. Import it to copy the package into app-managed storage.")
        Spacer(Modifier.height(TextToControl - LineGap))
        PrimaryButton(
            text = "Import saved file",
            description = "Import saved file",
            onClick = onImportSavedPackage,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun RecoveredPackageRow(
    recovered: RecoveredPackage,
    busy: Boolean,
    onSaveRecoveredPackage: () -> Unit,
    onImportRecoveredPackage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            "A complete package (${RegionUiModel.formatBytes(recovered.bytes)}) is kept in app storage.",
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
        )
        SecondaryText("Save it to a file you can keep, or import it directly.")
        Spacer(Modifier.height(TextToControl - LineGap))
        Row(horizontalArrangement = Arrangement.spacedBy(ControlGap), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(
                text = "Save to device",
                description = "Save downloaded package to device",
                onClick = onSaveRecoveredPackage,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "Import",
                description = "Import downloaded package",
                onClick = onImportRecoveredPackage,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InterruptedRow(interrupted: InterruptedDownload, enabled: Boolean, onResumeDownload: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Text(
            "${interrupted.regionName}: ${RegionUiModel.formatBytes(interrupted.bytes)} downloaded",
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
        )
        interrupted.total?.let { total ->
            Spacer(Modifier.height(LineGap))
            LinearProgressIndicator(
                progress = { (interrupted.bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) },
                color = Warning,
                trackColor = WarningTint,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(LineGap))
        }
        SecondaryText("The partial bytes are kept in app storage. Resuming continues where it stopped.")
        Spacer(Modifier.height(TextToControl - LineGap))
        PrimaryButton(
            text = "Resume download",
            description = "Resume download",
            onClick = onResumeDownload,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BundledRow(active: Boolean, rideActive: Boolean, onActivateBundled: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
            Text(
                "Queensland (bundled)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            if (active) {
                Spacer(Modifier.width(8.dp))
                StatusPill("ACTIVE", Tone.POSITIVE)
            }
        }
        SecondaryText(RegionUiModel.bundledSummary())
        if (!active) {
            Spacer(Modifier.height(TextToControl - LineGap))
            SecondaryButton(
                text = "Use bundled Queensland data",
                description = "Use bundled Queensland data",
                onClick = onActivateBundled,
                enabled = !rideActive,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun InstalledRow(
    install: InstalledRegion,
    active: Boolean,
    rideActive: Boolean,
    removing: Boolean,
    busy: Boolean,
    onActivate: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val deleteEnabled = !removing && !busy && !(active && rideActive)
    Column(verticalArrangement = Arrangement.spacedBy(LineGap)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LineGap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        install.regionName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                    )
                    if (active) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("ACTIVE", Tone.POSITIVE)
                    } else if (removing) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("DELETING", Tone.WARNING)
                    }
                }
                SecondaryText(RegionUiModel.installedSummary(install))
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onDelete,
                enabled = deleteEnabled,
                modifier = Modifier
                    .size(MAPS_TARGET_HEIGHT)
                    .semantics { contentDescription = "Delete ${install.regionName} map" },
            ) {
                TrashIcon(tint = if (deleteEnabled) Color(0xFF616161) else Disabled)
            }
        }
        when {
            removing -> SecondaryText(
                "Switching to the bundled Queensland data and releasing this map…",
                Warning,
            )
            active && rideActive -> SecondaryText("Stop the ride before deleting the active map.", Warning)
            active -> SecondaryText(
                "Deleting the active map switches back to the bundled Queensland data first.",
            )
            rideActive -> SecondaryText("Stop the ride before switching regions.", Warning)
        }
        if (!active && !removing) {
            Spacer(Modifier.height(TextToControl - LineGap))
            PrimaryButton(
                text = "Activate",
                description = "Activate region ${install.regionId}",
                onClick = { onActivate(install.installId) },
                enabled = !rideActive && !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
