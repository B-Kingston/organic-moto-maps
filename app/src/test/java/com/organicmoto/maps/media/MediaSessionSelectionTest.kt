package com.organicmoto.maps.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract of the deterministic active-session selection. */
class MediaSessionSelectionTest {

    @Test
    fun noSessionsSelectsNothing() {
        assertNull(MediaSessionSelection.select(emptyList()))
    }

    @Test
    fun playingSessionBeatsPausedSession() {
        val sessions = listOf(
            session("paused", isPlaying = false, lastActiveTime = 9_000),
            session("playing", isPlaying = true, lastActiveTime = 1_000),
        )
        assertEquals("playing", MediaSessionSelection.select(sessions))
    }

    @Test
    fun multiplePlayingSessionsTieBreakByRecentActivityThenName() {
        val byActivity = listOf(
            session("a", packageName = "a", isPlaying = true, lastActiveTime = 100),
            session("b", packageName = "b", isPlaying = true, lastActiveTime = 500),
        )
        assertEquals("b", MediaSessionSelection.select(byActivity))

        val byName = listOf(
            session("zeta", packageName = "zeta", isPlaying = true, lastActiveTime = 500),
            session("alpha", packageName = "alpha", isPlaying = true, lastActiveTime = 500),
        )
        assertEquals("alpha", MediaSessionSelection.select(byName))
    }

    @Test
    fun pausedSelectionIsRetainedWhenNothingIsPlaying() {
        val sessions = listOf(
            session("first", packageName = "aaa", isPlaying = false, lastActiveTime = 100),
            session("second", packageName = "bbb", isPlaying = false, lastActiveTime = 900),
        )
        // Without a previous choice the most recent paused session wins.
        assertEquals("second", MediaSessionSelection.select(sessions))
        // A previous paused selection survives so its Resume keeps working.
        assertEquals("first", MediaSessionSelection.select(sessions, previouslySelectedKey = "first"))
    }

    @Test
    fun aPlayingSessionBeatsARetainedPausedSelection() {
        val sessions = listOf(
            session("mine", isPlaying = false, lastActiveTime = 9_000),
            session("other", isPlaying = true, lastActiveTime = 100),
        )
        assertEquals(
            "other",
            MediaSessionSelection.select(sessions, previouslySelectedKey = "mine"),
        )
    }

    @Test
    fun stalePreviousSelectionFallsBackToDeterministicPick() {
        val sessions = listOf(
            session("only", packageName = "only", isPlaying = false, lastActiveTime = 0),
        )
        assertEquals("only", MediaSessionSelection.select(sessions, previouslySelectedKey = "gone"))
    }

    @Test
    fun userPinnedSessionWinsWhilePresentEvenIfAnotherPlays() {
        val sessions = listOf(
            session("chosen", isPlaying = false),
            session("loud", isPlaying = true),
        )
        assertEquals(
            "chosen",
            MediaSessionSelection.select(sessions, pinnedKey = "chosen"),
        )
    }

    @Test
    fun pinnedSessionThatDisappearsIsIgnored() {
        val sessions = listOf(session("loud", isPlaying = true))
        assertEquals("loud", MediaSessionSelection.select(sessions, pinnedKey = "gone"))
    }

    @Test
    fun selectionIsDeterministicAcrossOrderings() {
        val a = session("a", packageName = "a", isPlaying = true, lastActiveTime = 3_000)
        val b = session("b", packageName = "b", isPlaying = true, lastActiveTime = 3_000)
        assertEquals(
            MediaSessionSelection.select(listOf(a, b)),
            MediaSessionSelection.select(listOf(b, a)),
        )
    }

    @Test
    fun firstSessionForKeyUsesThePackageName() {
        assertEquals("spotify", sessionKeyFor("spotify", previousKey = null, taken = emptySet()))
    }

    @Test
    fun secondSessionFromOnePackageGetsAStableSuffix() {
        assertEquals(
            "spotify#1",
            sessionKeyFor("spotify", previousKey = null, taken = setOf("spotify")),
        )
        assertEquals(
            "spotify#2",
            sessionKeyFor("spotify", previousKey = null, taken = setOf("spotify", "spotify#1")),
        )
    }

    @Test
    fun anExistingTokenKeepsItsKeyWhenTheSnapshotReorders() {
        // Two sessions from one package swap positions between snapshots: each
        // token must keep the key it was assigned, never inherit the other's.
        val tokenAKey = sessionKeyFor("app", previousKey = null, taken = emptySet())
        val tokenBKey = sessionKeyFor("app", previousKey = null, taken = setOf(tokenAKey))
        assertEquals("app", tokenAKey)
        assertEquals("app#1", tokenBKey)

        // Second snapshot, reversed order: B is seen first but keeps app#1.
        val reorderedB = sessionKeyFor("app", previousKey = tokenBKey, taken = emptySet())
        val reorderedA = sessionKeyFor("app", previousKey = tokenAKey, taken = setOf(reorderedB))
        assertEquals("app#1", reorderedB)
        assertEquals("app", reorderedA)
    }

    @Test
    fun aStaleKeyThatIsTakenByAnotherLiveSessionIsReplaced() {
        // The token's old key now belongs to a different live session: the new
        // assignment must not steal it (that would swap identities again).
        val replacement = sessionKeyFor("app", previousKey = "app", taken = setOf("app"))
        assertEquals("app#1", replacement)
        assertEquals(
            "app#2",
            sessionKeyFor("app", previousKey = "app", taken = setOf("app", "app#1")),
        )
    }

    @Test
    fun keyAssignmentIsDeterministicForAnySnapshotOrder() {
        fun assign(order: List<String?>): List<String> {
            val taken = mutableSetOf<String>()
            return order.map { key ->
                sessionKeyFor("app", previousKey = key, taken = taken).also { taken.add(it) }
            }
        }
        // Fresh sessions (no previous key) get distinct keys in the order seen,
        // and re-running with those keys as previous values is stable.
        assertEquals(listOf("app", "app#1"), assign(listOf(null, null)))
        val first = assign(listOf(null, null))
        val second = assign(first)
        assertEquals(first, second)
    }
}
