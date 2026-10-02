package com.organicmoto.maps.media

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.organicmoto.maps.DataBarTopCorner
import com.organicmoto.maps.DataBarVerticalPadding

/**
 * How the media control center joins onto the ride data bar.
 *
 * The settings cog one screen over solves the same problem: the pill does not
 * sit *beside* the card it opens, it becomes part of it, so the card reads as
 * having grown out of the button. The media panel does the same to the bar, and
 * the only way to get there is to make the two surfaces genuinely continuous
 * rather than merely adjacent:
 *
 *  - [leadingInset] and [trailingInset] are zero, so the panel's left and right
 *    edges are the screen edges — exactly the bar's own extent. Any inset at
 *    all leaves a step at the join, and a step is what made this read as two
 *    stacked cards rather than one shape.
 *  - [fuseDepth] reaches the panel down into the bar's empty top padding, and
 *    the bar is drawn after the panel so it paints over that overlap. The seam
 *    is therefore covered twice over: the geometry overlaps, and the bar's own
 *    opaque surface hides the antialiased boundary.
 *  - [barShadow] drops the bar's shadow while the panel is open. Otherwise the
 *    bar casts its top-edge shadow onto the panel and that line is exactly the
 *    seam this is trying to remove. The panel keeps its own shadow so the
 *    fused slab still sits above the map.
 *  - [panelTopCorner] is the bar's own [DataBarTopCorner], so the fused
 *    silhouette is one consistently rounded slab from the panel's top down to
 *    the screen edge.
 *
 * Values are derived from the data bar's geometry rather than restated, so
 * retuning the bar cannot silently strand the panel above it.
 * [MediaPanelJoinTest] (JVM) pins these relationships and
 * [com.organicmoto.maps.ui.MediaPanelJoinTest] (instrumented) proves the real
 * laid-out edges are flush on screen.
 */
internal object MediaPanelJoin {

    /** The panel is full-bleed, like the bar: no step at the join. */
    val leadingInset: Dp get() = 0.dp

    /** The panel is full-bleed, like the bar: no step at the join. */
    val trailingInset: Dp get() = 0.dp

    /**
     * The fused slab's top corner radius, identical to the bar's own so the
     * silhouette reads as one consistently shaped surface.
     */
    val panelTopCorner: Dp get() = DataBarTopCorner

    /** Square at the join; rounded only when the bar stands alone. */
    fun barTopCorner(open: Boolean): Dp = if (open) 0.dp else DataBarTopCorner

    /**
     * Shadow elevation of the data bar. Zero while the panel is open: the bar's
     * top-edge shadow would fall across the panel and reinstate the seam.
     */
    fun barShadow(open: Boolean): Dp = if (open) 0.dp else 6.dp

    /**
     * How far the panel reaches into the bar's empty top padding. This covers
     * its base vertical padding; an additional navigation-inset gap balances
     * the ride row vertically. The overlap never reaches a metric or a control.
     */
    val fuseDepth: Dp get() = DataBarVerticalPadding
}
