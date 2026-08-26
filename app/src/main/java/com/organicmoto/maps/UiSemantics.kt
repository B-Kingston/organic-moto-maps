package com.organicmoto.maps

import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Test and accessibility state exposed on the route screen. */
val RouteUiStateKey = SemanticsPropertyKey<String>("RouteUiState")
val RouteGenerationKey = SemanticsPropertyKey<Int>("RouteGeneration")
val SelectedRouteKey = SemanticsPropertyKey<Int>("SelectedRoute")
val RouteCountKey = SemanticsPropertyKey<Int>("RouteCount")
val FocusedRouteIndexKey = SemanticsPropertyKey<Int>("FocusedRouteIndex")
val MapRouteCountKey = SemanticsPropertyKey<Int>("MapRouteCount")
val MapReadyKey = SemanticsPropertyKey<Boolean>("MapReady")
