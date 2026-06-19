package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AiStrategyTest {
    @Test
    fun aiLeadUsesFullConsecutivePairsRun() {
        val ai = BasicAiStrategy()
        val hand = listOf(
            c(Rank.Three), c(Rank.Three, Suit.Hearts),
            c(Rank.Four), c(Rank.Four, Suit.Hearts),
            c(Rank.Five), c(Rank.Five, Suit.Hearts),
        )
        val choice = ai.chooseMove(hand, leadContext(hand.size))
        assertFalse(choice.pass)
        assertEquals(PatternType.ConsecutivePairs, choice.pattern.type)
        assertEquals(6, choice.cards.size)
    }

    @Test
    fun aiFollowUsesHighSingletonWhenNextPlayerHasOneCard() {
        val ai = BasicAiStrategy()
        val previous = PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Seven))).pattern
        val hand = listOf(c(Rank.Eight), c(Rank.King))
        val choice = ai.chooseMove(
            hand,
            followContext(previous, hand.size).copy(nextPlayerRemainingCards = 1, minOpponentRemainingCards = 1),
        )
        assertFalse(choice.pass)
        assertEquals(PatternType.Single, choice.pattern.type)
        assertEquals(Rank.King, choice.pattern.mainRank)
    }

    @Test
    fun aiNormalLeadDoesNotThrowHighControlSingletonFirst() {
        val ai = BasicAiStrategy()
        val hand = listOf(
            c(Rank.Three), c(Rank.Three, Suit.Hearts),
            c(Rank.Six),
            c(Rank.Seven), c(Rank.Seven, Suit.Hearts), c(Rank.Seven, Suit.Diamonds),
            c(Rank.Eight), c(Rank.Nine), c(Rank.Ten), c(Rank.Ace),
        )
        val choice = ai.chooseMove(hand, leadContext(hand.size, 10, 10))
        assertFalse(choice.pattern.type == PatternType.Single && choice.pattern.mainRank == Rank.Ace)
        assertEquals(0, countRank(choice.cards, Rank.Ace))
    }
}
