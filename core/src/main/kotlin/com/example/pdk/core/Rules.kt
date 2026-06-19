package com.example.pdk.core

import kotlin.random.Random

enum class PatternType(val displayName: String) {
    Invalid("无效"),
    Single("单张"),
    Pair("对子"),
    Straight("顺子"),
    ConsecutivePairs("连对"),
    TripleWithOne("三带一"),
    TripleWithPair("三带二"),
    Plane("飞机"),
    Bomb("炸弹"),
}

data class HandPattern(
    val type: PatternType = PatternType.Invalid,
    val mainRank: Rank = Rank.Three,
    val cardCount: Int = 0,
    val groupCount: Int = 0,
    val lastHandShort: Boolean = false,
) {
    val isValid: Boolean get() = type != PatternType.Invalid
}

data class PatternResult(
    val pattern: HandPattern = HandPattern(),
    val reason: String = "",
)

data class MoveValidation(
    val ok: Boolean = false,
    val pattern: HandPattern = HandPattern(),
    val reason: String = "",
)

object PaoDeKuaiRules {
    fun createDeck(): List<Card> {
        val deck = mutableListOf<Card>()
        val ranks = listOf(
            Rank.Three, Rank.Four, Rank.Five, Rank.Six, Rank.Seven, Rank.Eight,
            Rank.Nine, Rank.Ten, Rank.Jack, Rank.Queen, Rank.King, Rank.Ace, Rank.Two,
        )
        for (rank in ranks) {
            for (suit in Suit.entries) {
                if (rank == Rank.Two && suit != Suit.Spades) continue
                if (rank == Rank.Ace && suit == Suit.Clubs) continue
                deck += Card(rank, suit)
            }
        }
        return deck
    }

    fun deal(seed: UInt): MutableList<MutableList<Card>> {
        val deck = createDeck().toMutableList()
        val random = Random(seed.toInt())
        for (i in deck.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = deck[i]
            deck[i] = deck[j]
            deck[j] = tmp
        }
        return MutableList(3) { player ->
            deck.drop(player * 16).take(16).sortedByGameOrder().toMutableList()
        }
    }

    fun findFirstPlayerBySpadeThree(hands: List<List<Card>>): PlayerId {
        val index = hands.indexOfFirst { hand -> hand.any { it.isSpadeThree() } }
        return playerFromIndex(if (index >= 0) index else 0)
    }

    fun identifyPattern(
        cards: List<Card>,
        handSizeBeforePlay: Int = -1,
        allowShortFinal: Boolean = false,
    ): PatternResult {
        if (cards.isEmpty()) return invalid("没有选择牌")
        val counts = cards.groupingBy { it.rank }.eachCount().toSortedMap(compareBy { it.value })
        val total = cards.size

        if (total == 1) return valid(PatternType.Single, cards.first().rank, total)
        if (total == 2 && counts.size == 1) return valid(PatternType.Pair, cards.first().rank, total)
        if (total == 3 && counts.size == 1) {
            if (allowShortFinal && handSizeBeforePlay == 3) {
                return valid(PatternType.TripleWithOne, cards.first().rank, total, lastShort = true)
            }
            return invalid("三张主体只能三带二，最后一手牌不足时除外")
        }

        if (total == 4) {
            if (counts.size == 1) {
                val rank = cards.first().rank
                if (rank == Rank.Ace || rank == Rank.Two) return invalid("没有 A 或 2 炸弹")
                return valid(PatternType.Bomb, rank, total)
            }
            counts.entries.firstOrNull { it.value == 3 }?.let { triple ->
                if (allowShortFinal && handSizeBeforePlay == total) {
                    return valid(PatternType.TripleWithOne, triple.key, total, lastShort = true)
                }
                return invalid("三张主体只能三带二，最后一手牌不足时除外")
            }
        }

        if (total == 5) {
            counts.entries.firstOrNull { it.value == 3 }?.let {
                return valid(PatternType.TripleWithPair, it.key, total)
            }
        }

        tryIdentifyPlane(counts, total, handSizeBeforePlay, allowShortFinal)?.let { return it }

        if (total >= 5 && counts.size == total) {
            val ranks = counts.keys.toList()
            if (isConsecutive(ranks)) return valid(PatternType.Straight, ranks.maxBy { it.value }, total)
        }

        if (total >= 4 && total % 2 == 0) {
            val pairRanks = mutableListOf<Rank>()
            for ((rank, count) in counts) {
                if (count != 2) {
                    pairRanks.clear()
                    break
                }
                pairRanks += rank
            }
            if (pairRanks.size >= 2 && isConsecutive(pairRanks)) {
                return valid(PatternType.ConsecutivePairs, pairRanks.maxBy { it.value }, total)
            }
        }

        return invalid("牌型不符合当前固定跑得快规则")
    }

    fun validateLead(cards: List<Card>, handSizeBeforePlay: Int = -1): MoveValidation {
        val result = identifyPattern(cards, handSizeBeforePlay, allowShortFinal = true)
        return if (result.pattern.isValid) MoveValidation(true, result.pattern) else MoveValidation(false, result.pattern, result.reason)
    }

    fun validateFollow(
        cards: List<Card>,
        previous: HandPattern,
        handSizeBeforePlay: Int = -1,
    ): MoveValidation {
        val result = identifyPattern(cards, handSizeBeforePlay, allowShortFinal = false)
        if (!result.pattern.isValid) return MoveValidation(false, result.pattern, result.reason)
        if (!canBeat(result.pattern, previous)) return MoveValidation(false, result.pattern, "牌型或点数压不过上家")
        return MoveValidation(true, result.pattern)
    }

    fun canBeat(candidate: HandPattern, previous: HandPattern): Boolean {
        if (!candidate.isValid || !previous.isValid) return false
        if (candidate.type == PatternType.Bomb && previous.type != PatternType.Bomb) return true
        if (candidate.type != PatternType.Bomb && previous.type == PatternType.Bomb) return false
        if (!sameComparisonClass(candidate, previous)) return false
        return candidate.mainRank.value > previous.mainRank.value
    }

    fun hasAnyFollowMove(hand: List<Card>, previous: HandPattern, handSizeBeforePlay: Int = -1): Boolean {
        if (!previous.isValid || hand.isEmpty()) return false
        val n = hand.size
        if (n >= 63) return false
        val sourceHandSize = if (handSizeBeforePlay >= 0) handSizeBeforePlay else n
        val limit = 1L shl n
        for (mask in 1L until limit) {
            val cards = hand.filterIndexed { index, _ -> (mask and (1L shl index)) != 0L }
            if (validateFollow(cards, previous, sourceHandSize).ok) return true
        }
        return false
    }

    fun patternDescription(pattern: HandPattern): String {
        if (!pattern.isValid) return PatternType.Invalid.displayName
        return buildString {
            append(pattern.type.displayName)
            append(' ')
            append(pattern.mainRank.label)
            if (pattern.lastHandShort) append(" (最后一手不足带牌)")
        }
    }

    private fun invalid(reason: String) = PatternResult(HandPattern(), reason)

    private fun valid(
        type: PatternType,
        rank: Rank,
        count: Int,
        lastShort: Boolean = false,
        groupCount: Int = 0,
    ) = PatternResult(HandPattern(type, rank, count, groupCount, lastShort))

    private fun isConsecutive(ranks: List<Rank>): Boolean {
        if (ranks.isEmpty() || ranks.any { it == Rank.Two }) return false
        return ranks.zipWithNext().all { (a, b) -> b.value == a.value + 1 }
    }

    private fun tryIdentifyPlane(
        counts: Map<Rank, Int>,
        total: Int,
        handSizeBeforePlay: Int,
        allowShortFinal: Boolean,
    ): PatternResult? {
        val tripleRanks = counts.filter { it.key != Rank.Two && it.value >= 3 }.keys.toList()
        if (tripleRanks.size < 2) return null
        var best: PatternResult? = null
        var bestScore = Int.MIN_VALUE
        for (start in tripleRanks.indices) {
            for (end in start + 1 until tripleRanks.size) {
                val run = tripleRanks.subList(start, end + 1)
                if (!isConsecutive(run)) break
                val groupCount = run.size
                val kickerCount = total - groupCount * 3
                if (kickerCount < 0 || kickerCount > groupCount * 2) continue
                val lastShort = kickerCount < groupCount
                if (lastShort && !(allowShortFinal && handSizeBeforePlay == total)) continue
                val score = groupCount * 100 + run.maxBy { it.value }.value
                if (score > bestScore) {
                    bestScore = score
                    best = valid(PatternType.Plane, run.maxBy { it.value }, total, lastShort, groupCount)
                }
            }
        }
        return best
    }

    private fun sameComparisonClass(lhs: HandPattern, rhs: HandPattern): Boolean {
        if (lhs.type != rhs.type) return false
        return when (lhs.type) {
            PatternType.Plane -> lhs.groupCount == rhs.groupCount
            PatternType.Straight, PatternType.ConsecutivePairs -> lhs.cardCount == rhs.cardCount
            else -> true
        }
    }
}

data class BombScoreEvent(val by: PlayerId = PlayerId.Player, val score: Int = 20)
data class SpringInfo(val enabled: Boolean = false, val losers: List<PlayerId> = emptyList())
data class RoundScoreInput(
    val winner: PlayerId,
    val remainingCards: List<Int>,
    val hasPlayedCards: List<Boolean>,
    val bombs: List<BombScoreEvent> = emptyList(),
)
data class RoundScoreResult(val scores: List<Int>, val spring: SpringInfo)

fun calculateRoundScore(input: RoundScoreInput): RoundScoreResult {
    val scores = MutableList(3) { 0 }
    for (bomb in input.bombs) {
        val by = bomb.by.index()
        scores[by] += bomb.score
        for (i in 0 until 3) if (i != by) scores[i] -= bomb.score / 2
    }
    val winner = input.winner.index()
    val springLosers = mutableListOf<PlayerId>()
    for (i in 0 until 3) {
        if (i == winner) continue
        if (!input.hasPlayedCards[i]) {
            springLosers += playerFromIndex(i)
            scores[i] -= 32
            scores[winner] += 32
        } else {
            val remaining = if (input.remainingCards[i] == 1) 0 else input.remainingCards[i]
            scores[i] -= remaining
            scores[winner] += remaining
        }
    }
    return RoundScoreResult(scores, SpringInfo(springLosers.isNotEmpty(), springLosers))
}
