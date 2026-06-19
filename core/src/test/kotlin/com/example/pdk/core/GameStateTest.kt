package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameStateTest {
    @Test
    fun gameStateCanFinishFullThreePlayerAutoplayRound() {
        val state = GameState()
        state.startNewRound("Tester", 20260606u)
        state.toggleAutoplay()

        repeat(600) {
            if (!state.roundOver) {
                state.update(0.5f)
                state.clearEvents()
            }
        }

        assertTrue(state.roundOver)
        val winner = state.lastRoundRecord.winner
        assertEquals(0, state.lastRoundRecord.remainingCards[winner.index()])
        assertEquals(0, state.lastRoundRecord.scores.sum())
    }

    @Test
    fun turnOrderIsCounterclockwise() {
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Three)),
                listOf(c(Rank.Five)),
                listOf(c(Rank.Seven), c(Rank.Eight)),
            ),
            PlayerId.Ai2,
            null,
            PlayerId.Ai2,
        )
        state.update(1f)
        assertEquals(PlayerId.Ai1, state.currentPlayer)
    }

    @Test
    fun passIsRejectedWhenPlayerCanBeat() {
        val previous = PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Four))).pattern
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Five)),
                listOf(c(Rank.Seven)),
                listOf(c(Rank.Eight)),
            ),
            PlayerId.Player,
            previous,
            PlayerId.Ai1,
        )
        assertFalse(state.passHuman())
        assertEquals("要得起必须出", state.toast)
    }

    @Test
    fun winnerLeadsNextRoundAfterOpeningRound() {
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Five)),
                listOf(c(Rank.Seven)),
                listOf(c(Rank.Nine)),
            ),
            PlayerId.Ai2,
            null,
            PlayerId.Ai2,
        )

        state.update(1f)
        assertTrue(state.roundOver)
        assertEquals(PlayerId.Ai2, state.lastRoundRecord.winner)

        state.startNewRound("Tester", 20260606u)

        assertEquals(PlayerId.Ai2, state.currentPlayer)
        assertEquals("AI2 上局获胜先出", state.events.last().message)
    }
}
