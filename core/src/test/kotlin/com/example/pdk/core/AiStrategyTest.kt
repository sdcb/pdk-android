package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun strongAiUsesProvenPassInformationForSafeSingletonLead() {
        val ai = StrongAiStrategy()
        val hand = listOf(c(Rank.Queen), c(Rank.King), c(Rank.Ace), c(Rank.Two))
        val context = leadContext(hand.size, 8, 8).copy(
            currentPlayerIndex = 1,
            remainingCards = listOf(8, hand.size, 8),
            passObservations = listOf(
                PassObservation(HandPattern(PatternType.Single, Rank.Queen, 1), 8),
                null,
                PassObservation(HandPattern(PatternType.Single, Rank.Queen, 1), 8),
            ),
            passHistory = listOf(
                listOf(PassObservation(HandPattern(PatternType.Single, Rank.Queen, 1), 8)),
                emptyList(),
                listOf(PassObservation(HandPattern(PatternType.Single, Rank.Queen, 1), 8)),
            ),
        )

        val choice = ai.chooseMove(hand, context)

        assertFalse(choice.pass)
        assertEquals(PatternType.Single, choice.pattern.type)
        assertEquals(Rank.Queen, choice.pattern.mainRank)
    }

    @Test
    fun strongAiUrgentFollowUsesHighSingletonBlocker() {
        val ai = StrongAiStrategy()
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
    fun strongAiCanPlayFullAutoplayRoundsAgainstBasicAi() {
        val rounds = 30
        val wins = MutableList(3) { 0 }

        repeat(rounds) { round ->
            val winner = runSimRound(
                listOf(BasicAiStrategy(), StrongAiStrategy(), BasicAiStrategy()),
                20260619u + round.toUInt(),
            ).winner
            wins[winner.index()]++
        }

        assertEquals(rounds, wins.sum())
        assertTrue(
            wins[PlayerId.Ai1.index()] >= rounds / 3,
            "strong AI should win a meaningful share in the smoke simulation, wins=$wins",
        )
    }

    @Test
    fun strongAiBeatsBasicOver1000AutoplayRounds() {
        if (!runAiFairnessTest()) return

        val rounds = aiFairnessRoundCount()
        val wins = MutableList(3) { 0 }
        val starts = MutableList(3) { 0 }
        var nextLeader: PlayerId? = null

        repeat(rounds) { round ->
            val result = runSimRound(
                listOf(BasicAiStrategy(), StrongAiStrategy(), BasicAiStrategy()),
                20260619u + round.toUInt(),
                if (runContinuousFairnessRounds()) nextLeader else null,
            )
            wins[result.winner.index()]++
            starts[result.leader.index()]++
            if (runContinuousFairnessRounds()) nextLeader = result.winner
        }

        val winRate = wins[PlayerId.Ai1.index()].toDouble() / rounds.toDouble()
        println(
            "AI1 Strong vs Basic over $rounds rounds: " +
                "Player=${wins[0]}, AI1=${wins[1]}, AI2=${wins[2]}, " +
                "AI1 winRate=${"%.1f".format(winRate * 100)}%, " +
                "leaders Player=${starts[0]}, AI1=${starts[1]}, AI2=${starts[2]}",
        )
        assertEquals(rounds, wins.sum())
        assertTrue(
            wins[PlayerId.Ai1.index()] > rounds / 3,
            "AI1 strong should still show an advantage while basic remains competitive, wins=$wins, starts=$starts",
        )
    }
}

private fun chooseMove(strategy: Any, hand: List<Card>, context: AiContext): AiMoveChoice = when (strategy) {
    is BasicAiStrategy -> strategy.chooseMove(hand, context)
    is StrongAiStrategy -> strategy.chooseMove(hand, context)
    else -> error("unsupported strategy")
}

private data class SimRoundResult(val winner: PlayerId, val leader: PlayerId)

private fun runAiFairnessTest(): Boolean {
    val value = System.getenv("PDK_AI_FAIRNESS") ?: System.getProperty("pdk.aiFairness") ?: ""
    return value.isNotBlank() && value != "0" && !value.equals("false", ignoreCase = true)
}

private fun aiFairnessRoundCount(): Int =
    (System.getenv("PDK_AI_FAIRNESS_ROUNDS") ?: System.getProperty("pdk.aiFairnessRounds"))
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
        ?: 1000

private fun runContinuousFairnessRounds(): Boolean {
    val value = System.getenv("PDK_AI_FAIRNESS_CONTINUOUS") ?: System.getProperty("pdk.aiFairnessContinuous")
    return value == null || value.isBlank() || value != "0"
}

private fun runSimRound(strategies: List<Any>, seed: UInt, requestedLeader: PlayerId? = null): SimRoundResult {
    val hands = PaoDeKuaiRules.deal(seed).map { it.toMutableList() }.toMutableList()
    var current = requestedLeader ?: PaoDeKuaiRules.findFirstPlayerBySpadeThree(hands)
    val roundLeader = current
    var lastMovePlayer = current
    var trickLeader = current
    var lastPattern: HandPattern? = null
    val playedCards = mutableListOf<Card>()
    val passObservations = MutableList<PassObservation?>(3) { null }
    val passHistory = MutableList(3) { mutableListOf<PassObservation>() }
    var passCount = 0

    repeat(600) {
        val currentIndex = current.index()
        val remaining = hands.map { it.size }
        val context = AiContext(
            leading = lastPattern == null,
            previous = lastPattern ?: HandPattern(),
            ownRemainingCards = hands[currentIndex].size,
            currentPlayerIndex = currentIndex,
            lastMovePlayerIndex = lastMovePlayer.index(),
            trickLeaderIndex = if (lastPattern == null) current.index() else trickLeader.index(),
            roundLeaderIndex = roundLeader.index(),
            currentTrickPassCount = passCount,
            nextPlayerRemainingCards = hands[nextCounterClockwise(current).index()].size,
            minOpponentRemainingCards = remaining.filterIndexed { index, _ -> index != currentIndex }.minOrNull() ?: 0,
            remainingCards = remaining,
            playedCards = playedCards,
            passObservations = passObservations.toList(),
            passHistory = passHistory.map { it.toList() },
        )
        val choice = chooseMove(strategies[currentIndex], hands[currentIndex], context)
        if (choice.pass) {
            val pattern = lastPattern ?: error("lead turn cannot pass")
            assertFalse(PaoDeKuaiRules.hasAnyFollowMove(hands[currentIndex], pattern, hands[currentIndex].size))
            val observation = PassObservation(pattern, hands[currentIndex].size)
            passHistory[currentIndex] += observation
            passObservations[currentIndex] = observation
            passCount++
            if (passCount >= 2) {
                current = lastMovePlayer
                lastPattern = null
                trickLeader = current
                passCount = 0
            } else {
                current = nextCounterClockwise(current)
            }
            return@repeat
        }

        val handSizeBefore = hands[currentIndex].size
        val previous = lastPattern
        val validation = if (previous == null) {
            PaoDeKuaiRules.validateLead(choice.cards, handSizeBefore)
        } else {
            PaoDeKuaiRules.validateFollow(choice.cards, previous, handSizeBefore)
        }
        assertTrue(validation.ok, validation.reason)
        if (lastPattern == null) trickLeader = current
        choice.cards.forEach { hands[currentIndex].remove(it) }
        playedCards += choice.cards
        lastPattern = validation.pattern
        lastMovePlayer = current
        passCount = 0
        if (hands[currentIndex].isEmpty()) return SimRoundResult(current, roundLeader)
        current = nextCounterClockwise(current)
    }
    error("simulated round did not finish")
}
