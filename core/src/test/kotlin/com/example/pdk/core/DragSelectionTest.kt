package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DragSelectionTest {
    @Test
    fun dragSelectsBestPatternFromDraggedCards() {
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Four), c(Rank.Four, Suit.Clubs), c(Rank.Five), c(Rank.Five, Suit.Clubs), c(Rank.Six)),
                listOf(c(Rank.Seven)),
                listOf(c(Rank.Eight)),
            ),
            currentPlayer = PlayerId.Player,
            previousPattern = null,
            lastMovePlayer = PlayerId.Player,
        )

        assertTrue(state.selectBestPatternFromDraggedCards(listOf(0, 1, 2, 3)))

        assertEquals(setOf(0, 1, 2, 3), state.selectedIndices)
        assertTrue(state.toast.contains("连对"))
    }

    @Test
    fun repeatingSameDragCancelsSelection() {
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Four), c(Rank.Four, Suit.Clubs), c(Rank.Five)),
                listOf(c(Rank.Seven)),
                listOf(c(Rank.Eight)),
            ),
            currentPlayer = PlayerId.Player,
            previousPattern = null,
            lastMovePlayer = PlayerId.Player,
        )

        assertTrue(state.selectBestPatternFromDraggedCards(listOf(0, 1)))
        assertEquals(setOf(0, 1), state.selectedIndices)

        assertTrue(state.selectBestPatternFromDraggedCards(listOf(0, 1)))
        assertTrue(state.selectedIndices.isEmpty())
        assertEquals("已取消拖拽选择", state.toast)
    }
}
