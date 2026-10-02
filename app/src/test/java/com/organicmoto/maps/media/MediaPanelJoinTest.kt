package com.organicmoto.maps.media

import androidx.compose.ui.unit.dp
import com.organicmoto.maps.DataBarTopCorner
import com.organicmoto.maps.DataBarVerticalPadding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media panel must fuse onto the data bar as one continuous silhouette, the
 * way the settings card fuses onto its cog. These are relationships to the
 * bar's own geometry, not taste: an inset or a mismatched radius is exactly what
 * made the panel read as a second, disjointed card floating above the bar.
 */
class MediaPanelJoinTest {

    @Test
    fun `open bar has no rounded bezel at the join`() {
        assertEquals(0.dp, MediaPanelJoin.barTopCorner(true))
        assertEquals(DataBarTopCorner, MediaPanelJoin.barTopCorner(false))
    }

    @Test
    fun `the panel is full-bleed like the bar`() {
        // The bar's surface spans the whole screen width. Any inset on the
        // panel leaves a step at the join on that side.
        assertEquals(0.dp, MediaPanelJoin.leadingInset)
        assertEquals(0.dp, MediaPanelJoin.trailingInset)
    }

    @Test
    fun `the fused silhouette is one consistently rounded shape`() {
        // The panel's top and the bar's top share a radius, so the pair reads as
        // one slab rather than two shapes with different corner languages.
        assertEquals(DataBarTopCorner, MediaPanelJoin.panelTopCorner)
        assertTrue(
            "the fused slab needs a visible top rounding",
            MediaPanelJoin.panelTopCorner > 0.dp,
        )
    }

    @Test
    fun `the bar drops its shadow while the panel is open`() {
        // The bar's top-edge shadow would fall across the panel and redraw the
        // seam as a soft line; the panel supplies the elevation for the pair.
        assertEquals(0.dp, MediaPanelJoin.barShadow(open = true))
        assertTrue(
            "the closed bar must keep its own elevation",
            MediaPanelJoin.barShadow(open = false) > 0.dp,
        )
    }

    @Test
    fun `the overlap covers the bar's empty top padding and no more`() {
        // The bar pads its content from the top. The overlap may consume that
        // padding and nothing beyond it, or it would sit on a metric.
        assertTrue(
            "the join must actually overlap, got ${MediaPanelJoin.fuseDepth}",
            MediaPanelJoin.fuseDepth > 0.dp,
        )
        assertEquals(
            "the overlap should exactly fill the bar's top padding",
            DataBarVerticalPadding,
            MediaPanelJoin.fuseDepth,
        )
    }
}
