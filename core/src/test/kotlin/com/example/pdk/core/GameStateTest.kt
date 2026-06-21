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

        state.advanceUntil(maxFrames = 1200, dtSeconds = 0.5f, clearEventsEachFrame = true) { roundOver }

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
                listOf(c(Rank.Five), c(Rank.Nine)),
                listOf(c(Rank.Seven), c(Rank.Eight)),
            ),
            PlayerId.Ai2,
            null,
            PlayerId.Ai2,
        )
        state.advanceUntil { currentPlayer == PlayerId.Ai1 }
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

        state.advanceUntil { roundOver }
        assertTrue(state.roundOver)
        assertEquals(PlayerId.Ai2, state.lastRoundRecord.winner)

        state.startNewRound("Tester", 20260606u)

        assertEquals(PlayerId.Ai2, state.currentPlayer)
        assertEquals("AI2 上局获胜先出", state.events.last().message)
    }

    @Test
    fun gameStateCanUseStrongLocalAiForConfiguredPlayer() {
        val state = GameState()
        state.setLocalAiKind(PlayerId.Ai1, "strong")
        state.testSetRound(
            listOf(
                listOf(c(Rank.Three), c(Rank.Four)),
                listOf(c(Rank.Five)),
                listOf(c(Rank.Seven), c(Rank.Eight)),
            ),
            PlayerId.Ai1,
            null,
            PlayerId.Ai1,
        )

        state.advanceUntil { playedCards.isNotEmpty() }

        assertTrue(state.playedCards.isNotEmpty())
        assertTrue(state.roundOver || state.currentPlayer != PlayerId.Ai1)
    }

    @Test
    fun aiBombUsesCppTalkPool() {
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Three)),
                cardsOf(Rank.Five, 4),
                listOf(c(Rank.Seven)),
            ),
            PlayerId.Ai1,
            null,
            PlayerId.Ai1,
        )

        state.advanceUntil { events.any { it.type == GameEventType.Talk && it.player == PlayerId.Ai1 } }

        val talks = state.events.filter { it.type == GameEventType.Talk && it.player == PlayerId.Ai1 }.map { it.message }
        assertTrue(talks.any { it in bombTalks })
    }

    @Test
    fun aiPassingLongMoveUsesCannotBeatTalkPool() {
        val previous = PaoDeKuaiRules.identifyPattern(straightCards(Rank.Five, 7)).pattern
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Three)),
                listOf(c(Rank.Three, Suit.Hearts), c(Rank.Four, Suit.Hearts), c(Rank.Six, Suit.Hearts)),
                listOf(c(Rank.Seven)),
            ),
            PlayerId.Ai1,
            previous,
            PlayerId.Player,
        )

        state.advanceUntil { events.any { it.type == GameEventType.Talk && it.player == PlayerId.Ai1 } }

        val talks = state.events.filter { it.type == GameEventType.Talk && it.player == PlayerId.Ai1 }.map { it.message }
        assertTrue(talks.any { it in cannotBeatBigMoveTalks })
    }

    @Test
    fun humanGoodMoveMakesAiReact() {
        val state = GameState()
        state.testSetRound(
            listOf(
                straightCards(Rank.Three, 7),
                listOf(c(Rank.Four, Suit.Hearts)),
                listOf(c(Rank.Five, Suit.Hearts)),
            ),
            PlayerId.Player,
            null,
            PlayerId.Player,
        )
        repeat(state.players[0].hand.size) { state.togglePlayerCard(it) }

        assertTrue(state.playSelected())

        val talks = state.events.filter { it.type == GameEventType.Talk && it.player != PlayerId.Player }.map { it.message }
        assertTrue(talks.any { it in humanGoodStraightTalks("李姐") })
    }

    @Test
    fun roundEndGoodAiHandUsesCppTalkPool() {
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Three)),
                cardsOf(Rank.Five, 3) + cardsOf(Rank.Six, 3),
                listOf(c(Rank.Seven)),
            ),
            PlayerId.Player,
            null,
            PlayerId.Player,
        )
        state.togglePlayerCard(0)

        assertTrue(state.playSelected())

        val talks = state.events.filter { it.type == GameEventType.Talk && it.player == PlayerId.Ai1 }.map { it.message }
        assertTrue(talks.any { it in roundEndGoodPlaneTalks })
    }

    private val bombTalks = setOf(
        "看，我有炸弹，没想到吧？",
        "炸一下，醒醒神！",
        "这炸弹我可憋很久了。",
    )

    private val cannotBeatBigMoveTalks = setOf(
        "这么长一串？我先缓缓。",
        "这谁顶得住啊，我不要了。",
        "你这一下甩这么多，我接不住。",
    )

    private fun humanGoodStraightTalks(playerName: String) = setOf(
        "这顺子也太顺了吧。",
        "$playerName 这条顺子，漂亮得有点过分。",
        "这么长一串，看得我心里发虚。",
    )

    private val roundEndGoodPlaneTalks = setOf(
        "哎呀，我还有个好飞机呢。",
        "飞机还在手里，结果已经结束了。",
        "这把我的飞机没起飞。",
    )
}
