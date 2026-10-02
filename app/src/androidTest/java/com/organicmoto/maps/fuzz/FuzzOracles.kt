package com.organicmoto.maps.fuzz

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import com.organicmoto.maps.MapReadyKey
import com.organicmoto.maps.MediaPanelOpenKey
import com.organicmoto.maps.RouteUiStateKey

/** Oracle checks for the route screen fuzz campaign. */
class FuzzOracles(private val rule: ComposeTestRule) {

    fun hardFailures(state: StateFingerprint, loadingSteps: Int, loadingGeneration: Int?): List<String> {
        if (!hasComposeHierarchy()) return listOf("NoComposeHierarchy: route screen disappeared")
        val failures = mutableListOf<String>()
        if (state.stateName == "loading" && loadingSteps > 20 && loadingGeneration == state.generation) {
            failures += "NoPermanentLoading: loading persisted for $loadingSteps steps at generation ${state.generation}"
        }
        if (state.routeCount > 0) {
            if (state.selectedIndex != state.focusedRouteIndex) {
                failures += "CarouselMatchesMap: selected=${state.selectedIndex} focused=${state.focusedRouteIndex}"
            }
            if (state.routeCount != state.mapRouteCount) {
                failures += "CarouselMatchesMap: cards=${state.routeCount} map=${state.mapRouteCount}"
            }
        }
        val blockingDialogs = listOf(
            hasText("Delete saved route?"),
            hasText("Route settings"),
        ).sumOf { matcher ->
            // Count only nodes inside a dialog: the settings card also shows a
            // "Route settings" row, which is not a blocking dialog.
            rule.onAllNodes(
                matcher.and(SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
        }
        if (blockingDialogs > 1) failures += "NoDuplicateBlockingDialogs: $blockingDialogs dialogs"

        // The planner's action button is intentionally disabled while a route
        // request is in flight (it still reads START and reports the loading
        // state), so reachability is only asserted once the planner settles.
        // With a route ready the same button reads RIDE, so either label
        // satisfies "the planner always offers its next action".
        if (state.stateName != "loading") {
            val startOrEnd = if (state.guidanceActive) {
                rule.onAllNodes(hasContentDescription("End navigation").and(hasClickAction()))
            } else {
                rule.onAllNodes(
                    hasText("START").or(hasText("RIDE")).and(hasClickAction()),
                )
            }
            if (startOrEnd.fetchSemanticsNodes().isEmpty()) {
                failures += if (state.guidanceActive) {
                    "EndNavigationReachable: END has no click action"
                } else {
                    "StartReachable: planner action (START/RIDE) has no click action"
                }
            }
        }
        val darkMapControls = rule.onAllNodes(
            hasContentDescription("Dark ride map", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        if (state.guidanceActive) {
            if (darkMapControls.size != 1) {
                failures += "DarkRideMapAccessible: expected one guidance toggle, found ${darkMapControls.size}"
            } else {
                val darkMapNode = darkMapControls.single()
                val clickableToggle = rule.onAllNodes(
                    hasContentDescription("Dark ride map", substring = true).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (clickableToggle.isEmpty()) {
                    failures += "DarkRideMapAccessible: toggle has no click action"
                }
                val minSizePx = 64f * rule.density.density
                if (darkMapNode.boundsInRoot.width < minSizePx ||
                    darkMapNode.boundsInRoot.height < minSizePx
                ) {
                    failures += "DarkRideMapAccessible: target smaller than 64 dp"
                }
                val expectedDescription = if (state.darkRideMapEnabled) {
                    "Dark ride map, on"
                } else {
                    "Dark ride map, off"
                }
                val descriptions = darkMapNode.config.getOrElse(
                    SemanticsProperties.ContentDescription,
                ) { emptyList() }
                if (expectedDescription !in descriptions) {
                    failures += "DarkRideMapStateVisible: expected $expectedDescription, found $descriptions"
                }
            }
        } else if (darkMapControls.isNotEmpty()) {
            failures += "DarkRideMapGuidanceOnly: toggle visible outside guidance"
        }
        if (state.guidanceActive) {
            val soundControls = rule.onAllNodes(
                hasContentDescription("Voice guidance settings", substring = true).and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            val gloveSize = 64f * rule.density.density
            if (soundControls.size != 1) {
                failures += "RideSoundAccessible: expected one sound control, found ${soundControls.size}"
            } else {
                val sound = soundControls.single().boundsInRoot
                if (sound.width < gloveSize || sound.height < gloveSize) {
                    failures += "RideSoundTargetSize: target smaller than 64 dp"
                }
                darkMapControls.singleOrNull()?.boundsInRoot?.let { dark ->
                    if (sound.overlaps(dark)) {
                        failures += "RideSettingsTargetsSeparate: sound and B&W targets overlap"
                    }
                }
            }
            val recenterNodes = rule.onAllNodes(
                hasContentDescription("Rider lock", substring = true).and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (recenterNodes.isEmpty()) {
                failures += "RideCameraLockAccessible: lock action missing during guidance"
            } else if (recenterNodes.none {
                    it.boundsInRoot.width >= 48f * rule.density.density &&
                        it.boundsInRoot.height >= 48f * rule.density.density
                }
            ) {
                failures += "RideRecenterTargetSize: recenter action is smaller than 48 dp"
            }
        }
        if (rule.onAllNodes(hasText("OpenMapTiles.org", substring = true)).fetchSemanticsNodes().isEmpty()) {
            failures += "AttributionVisible: attribution is missing"
        }
        val mediaTargetPx = 64f * rule.density.density
        val mediaPanelOpen = rule.onAllNodes(
            SemanticsMatcher.expectValue(MediaPanelOpenKey, true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        if (state.guidanceActive) {
            val openers = rule.onAllNodes(
                hasContentDescription("Media controls").and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (openers.isEmpty()) {
                failures += "MediaOpenerAccessible: media opener missing during guidance"
            } else if (openers.none {
                    it.boundsInRoot.width >= mediaTargetPx && it.boundsInRoot.height >= mediaTargetPx
                }
            ) {
                failures += "MediaOpenerTargetSize: media opener is smaller than the 64 dp glove target"
            }
        } else if (rule.onAllNodes(hasContentDescription("Media controls"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        ) {
            failures += "MediaOpenerGuidanceOnly: media opener visible outside guidance"
        }
        if (mediaPanelOpen) {
            if (rule.onAllNodes(hasContentDescription("Enable notification access"))
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                failures += "MediaPanelNoPermissionPrompt: setup must stay outside the ride panel"
            }
            val timers = rule.onAllNodes(
                hasContentDescription("Media controls inactivity timer"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (timers.isEmpty()) {
                failures += "MediaInactivityTimerPresent: open panel has no timer"
            }
            if (!state.guidanceActive) {
                failures += "MediaPanelGuidanceOnly: panel visible outside guidance"
            }
            if (rule.onAllNodes(hasContentDescription("Media control center"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            ) {
                failures += "MediaPanelVisible: panel state says open but the panel is missing"
            }
            // Every transport/volume target is a 64 dp glove target, and the
            // play/pause control is 80 dp. There is always at least one
            // play/pause direction: the unknown-state panel shows both.
            val gloveTargets = listOf(
                "Close media controls",
                "Previous track",
                "Next track",
            )
            gloveTargets.forEach { description ->
                val nodes = rule.onAllNodes(
                    hasContentDescription(description).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    failures += "MediaPanelControlReachable: missing clickable $description"
                } else if (nodes.none {
                        it.boundsInRoot.width >= mediaTargetPx &&
                            it.boundsInRoot.height >= mediaTargetPx
                    }
                ) {
                    failures += "MediaPanelTargetSize: $description is smaller than 64 dp"
                }
            }
            val playPausePx = 80f * rule.density.density
            val directions = listOf("Play", "Pause").flatMap { description ->
                rule.onAllNodes(
                    hasContentDescription(description).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
            }
            if (directions.isEmpty()) {
                failures += "MediaPanelTransportReachable: no play/pause target"
            } else if (directions.none {
                    it.boundsInRoot.width >= playPausePx && it.boundsInRoot.height >= playPausePx
                }
            ) {
                failures += "MediaPanelPlayPauseTargetSize: play/pause is smaller than 80 dp"
            }
        }
        if (state.voiceSettingsOpen) {
            listOf("Spoken turn guidance", "Announcement interval", "Preview next").forEach { description ->
                if (rule.onAllNodes(
                        androidx.compose.ui.test.hasContentDescription(description, substring = true),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes().isEmpty()
                ) {
                    failures += "VoiceSettingsAccessible: missing $description control"
                }
            }
        }
        // The Maps screen must keep its server/import controls reachable and
        // glove-sized whenever the campaign opens it. The check uses the
        // node's layout size, not its clipped on-screen bounds: the screen
        // scrolls, so a control below the fold is still a full-size target.
        if (state.mapsSettingsOpen) {
            val mapsTargetPx = 48f * rule.density.density
            listOf("Map server address", "Check server", "Import package file").forEach { description ->
                val nodes = rule.onAllNodes(
                    hasContentDescription(description),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (nodes.isEmpty()) {
                    failures += "MapsControlsVisible: missing $description"
                } else if (nodes.none {
                        it.size.width >= mapsTargetPx && it.size.height >= mapsTargetPx
                    }
                ) {
                    failures += "MapsControlsTargetSize: $description is smaller than 48 dp"
                }
            }
            // The bundled fallback is always listed and can never be deleted;
            // the campaign cannot install a regional package (no server, no SAF
            // picker), so the rubbish bin is covered by MapsSettingsScreenTest
            // and MapsDownloadFlowOnDeviceTest instead.
            if (rule.onAllNodes(hasText("Queensland (bundled)"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
            ) {
                failures += "MapsBundledRow: missing the bundled fallback entry"
            }
        }
        // Every screen opened from the settings card exits through the shared
        // back button; it stays a full 48 dp target.
        val minimumTargetPx = 48f * rule.density.density
        val routeSettingsOpen = rule.onAllNodes(
            hasContentDescription("Maximum shared roads", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().isNotEmpty()
        if (state.voiceSettingsOpen || routeSettingsOpen || state.mapsSettingsOpen) {
            val backButtons = rule.onAllNodes(
                hasContentDescription("Back").and(hasClickAction()),
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            if (backButtons.isEmpty()) {
                failures += "SettingsScreenBackReachable: missing clickable Back"
            } else if (backButtons.none {
                    // Layout size, not clipped bounds: the Maps screen scrolls,
                    // so its header can be partially out of view without the
                    // 48 dp target contract being broken.
                    it.size.width >= minimumTargetPx && it.size.height >= minimumTargetPx
                }
            ) {
                failures += "SettingsScreenBackTargetSize: Back is smaller than 48 dp"
            }
        }
        if (!state.guidanceActive) {
            listOf("Planning settings", "Saved routes", "Use current location").forEach { description ->
                val railButtons = rule.onAllNodes(
                    hasContentDescription(description).and(hasClickAction()),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                if (railButtons.isEmpty()) {
                    failures += "PlannerActionsAccessible: missing clickable $description"
                } else if (railButtons.none {
                        it.boundsInRoot.width >= minimumTargetPx &&
                            it.boundsInRoot.height >= minimumTargetPx
                    }
                ) {
                    failures += "PlannerActionsTargetSize: $description is smaller than 48 dp"
                }
            }
            val settingsItems = listOf(
                "Route settings",
                "Load map file",
                "Import GPX route",
                "Sound settings",
            )
            if (state.settingsMenuOpen) {
                settingsItems.forEach { label ->
                    val nodes = rule.onAllNodes(
                        hasText(label).and(hasClickAction()),
                    ).fetchSemanticsNodes()
                    if (nodes.isEmpty()) {
                        failures += "SettingsMenuAccessible: missing clickable $label"
                    } else if (nodes.none {
                            it.boundsInRoot.width >= minimumTargetPx &&
                                it.boundsInRoot.height >= minimumTargetPx
                        }
                    ) {
                        failures += "SettingsMenuTargetSize: $label is smaller than 48 dp"
                    }
                }
            } else if (listOf("Load map file", "Import GPX route", "Sound settings").any { label ->
                    rule.onAllNodes(hasText(label), useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            ) {
                failures += "SettingsMenuVisibility: menu items visible while menu is closed"
            }
        }
        return failures
    }

    private fun hasComposeHierarchy(): Boolean =
        runCatching {
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(RouteUiStateKey),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false)

    fun softWarnings(state: StateFingerprint): List<String> = buildList {
        if (state.stateName == "success" && state.mapInstalled && !state.mapReady) {
            add("MapReadyBeforeRouteRender: route success before map readiness")
        }
        // Only meaningful when a basemap is installed: without tiles there is
        // no style to load, so "not loaded yet" would be a false alarm.
        if (state.guidanceActive && state.darkRideMapEnabled && state.mapInstalled &&
            !state.darkGuidanceStyleReady
        ) {
            add("DarkGuidanceStyleLoading: selected dark style has not loaded yet")
        }
    }
}
