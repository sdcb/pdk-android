package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RulesTest {
    @Test
    fun fixedDeckHas48CardsWithOnlySpadeTwoAndNoClubAce() {
        val deck = PaoDeKuaiRules.createDeck()
        assertEquals(48, deck.size)
        assertEquals(1, deck.count { it.rank == Rank.Two })
        assertTrue(deck.any { it.isSpadeThree() })
        assertFalse(deck.any { it.rank == Rank.Ace && it.suit == Suit.Clubs })
        assertEquals(Card(Rank.Two, Suit.Spades), deck.first { it.rank == Rank.Two })
    }

    @Test
    fun recognizesCorePatterns() {
        assertEquals(PatternType.Single, PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Five))).pattern.type)
        assertEquals(PatternType.Pair, PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Five), c(Rank.Five, Suit.Hearts))).pattern.type)
        assertEquals(
            PatternType.Straight,
            PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Ten), c(Rank.Jack), c(Rank.Queen), c(Rank.King), c(Rank.Ace))).pattern.type,
        )
        assertFalse(
            PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Jack), c(Rank.Queen), c(Rank.King), c(Rank.Ace), c(Rank.Two))).pattern.isValid,
        )
        assertEquals(
            PatternType.ConsecutivePairs,
            PaoDeKuaiRules.identifyPattern(
                listOf(
                    c(Rank.Three), c(Rank.Three, Suit.Hearts),
                    c(Rank.Four), c(Rank.Four, Suit.Hearts),
                    c(Rank.Five), c(Rank.Five, Suit.Hearts),
                ),
            ).pattern.type,
        )
        assertEquals(
            PatternType.TripleWithPair,
            PaoDeKuaiRules.identifyPattern(
                listOf(c(Rank.Nine), c(Rank.Nine, Suit.Hearts), c(Rank.Nine, Suit.Diamonds), c(Rank.Five), c(Rank.Six)),
            ).pattern.type,
        )
        val plane = PaoDeKuaiRules.identifyPattern(
            listOf(
                c(Rank.Three), c(Rank.Three, Suit.Hearts), c(Rank.Three, Suit.Diamonds),
                c(Rank.Four), c(Rank.Four, Suit.Hearts), c(Rank.Four, Suit.Diamonds),
                c(Rank.Five), c(Rank.Six),
            ),
        ).pattern
        assertEquals(PatternType.Plane, plane.type)
        assertEquals(2, plane.groupCount)
    }

    @Test
    fun bombsAndComparisonFollowFixedRules() {
        val bomb = PaoDeKuaiRules.identifyPattern(
            listOf(c(Rank.King), c(Rank.King, Suit.Hearts), c(Rank.King, Suit.Diamonds), c(Rank.King, Suit.Clubs)),
        ).pattern
        assertEquals(PatternType.Bomb, bomb.type)
        assertFalse(
            PaoDeKuaiRules.identifyPattern(
                listOf(c(Rank.Ace), c(Rank.Ace, Suit.Hearts), c(Rank.Ace, Suit.Diamonds)),
            ).pattern.isValid,
        )
        val pair5 = PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Five), c(Rank.Five, Suit.Hearts))).pattern
        val pair6 = PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Six), c(Rank.Six, Suit.Hearts))).pattern
        assertTrue(PaoDeKuaiRules.canBeat(pair6, pair5))
        assertFalse(PaoDeKuaiRules.canBeat(pair5, pair6))
        assertTrue(PaoDeKuaiRules.canBeat(bomb, pair6))
    }

    @Test
    fun leadAllowsShortFinalTriplesButFollowDoesNot() {
        val bareTriple = listOf(c(Rank.Nine), c(Rank.Nine, Suit.Hearts), c(Rank.Nine, Suit.Diamonds))
        val lead = PaoDeKuaiRules.validateLead(bareTriple, bareTriple.size)
        assertTrue(lead.ok)
        assertTrue(lead.pattern.lastHandShort)

        val previous = PaoDeKuaiRules.identifyPattern(
            listOf(c(Rank.Eight), c(Rank.Eight, Suit.Hearts), c(Rank.Eight, Suit.Diamonds), c(Rank.Five), c(Rank.Six)),
        ).pattern
        val follow = PaoDeKuaiRules.validateFollow(
            listOf(c(Rank.Nine), c(Rank.Nine, Suit.Hearts), c(Rank.Nine, Suit.Diamonds), c(Rank.Four)),
            previous,
            4,
        )
        assertFalse(follow.ok)
    }
}
