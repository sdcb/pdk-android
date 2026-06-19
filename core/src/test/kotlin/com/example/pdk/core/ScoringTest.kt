package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    @Test
    fun scoringUsesRemainingCardsBombsAndSpring() {
        val result = calculateRoundScore(
            RoundScoreInput(
                winner = PlayerId.Player,
                remainingCards = listOf(0, 4, 8),
                hasPlayedCards = listOf(true, true, false),
                bombs = listOf(BombScoreEvent(PlayerId.Ai1, 20)),
            ),
        )
        assertTrue(result.spring.enabled)
        assertEquals(listOf(26, 16, -42), result.scores)
    }
}
