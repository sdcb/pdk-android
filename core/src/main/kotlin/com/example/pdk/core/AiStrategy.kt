package com.example.pdk.core

data class AiMoveChoice(
    val pass: Boolean = true,
    val cards: List<Card> = emptyList(),
    val pattern: HandPattern = HandPattern(),
    val reason: String = "",
    val disruptionPenalty: Int = 0,
)

data class PassObservation(
    val pattern: HandPattern,
    val remainingCards: Int,
)

data class AiContext(
    val leading: Boolean = true,
    val previous: HandPattern = HandPattern(),
    val ownRemainingCards: Int = 0,
    val currentPlayerIndex: Int = 0,
    val nextPlayerRemainingCards: Int = 0,
    val minOpponentRemainingCards: Int = 0,
    val remainingCards: List<Int> = listOf(0, 0, 0),
    val playedCards: List<Card> = emptyList(),
    val passObservations: List<PassObservation?> = listOf(null, null, null),
)

class BasicAiStrategy {
    fun chooseMove(hand: List<Card>, context: AiContext): AiMoveChoice =
        recommendMoves(hand, context, 1).firstOrNull()
            ?: AiMoveChoice(true, reason = if (context.leading) "没有可出的牌型" else "压不过，选择不要")

    fun recommendMoves(hand: List<Card>, context: AiContext, limit: Int = 3): List<AiMoveChoice> {
        val candidates = generateCandidates(hand, context)
        if (candidates.isEmpty()) {
            return listOf(AiMoveChoice(true, reason = if (context.leading) "没有可出的牌型" else "压不过，选择不要"))
        }
        return candidates
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenByDescending { it.cards.size }
                    .thenBy { it.pattern.mainRank.value },
            )
            .distinctBy { it.key }
            .take(limit.coerceAtLeast(0))
            .map {
                AiMoveChoice(
                    pass = false,
                    cards = it.cards,
                    pattern = it.pattern,
                    reason = "基础 AI 推荐 ${it.pattern.type.displayName}",
                    disruptionPenalty = it.disruptionPenalty,
                )
            }
    }

    private data class Candidate(
        val cards: List<Card>,
        val pattern: HandPattern,
        val remainder: List<Card>,
        val disruptionPenalty: Int,
        val score: Int,
    ) {
        val key: String = buildString {
            append(pattern.type)
            append(':')
            append(pattern.mainRank.label)
            append(':')
            append(pattern.cardCount)
            append(':')
            append(pattern.groupCount)
            cards.groupingBy { it.rank }.eachCount().toSortedMap(compareBy { it.value }).forEach { (rank, count) ->
                append(':')
                append(rank.label)
                append(count)
            }
        }
    }

    private fun generateCandidates(hand: List<Card>, context: AiContext): List<Candidate> {
        val n = hand.size
        if (n == 0 || n > 20) return emptyList()
        val handCounts = countRanks(hand)
        val result = mutableListOf<Candidate>()
        val limit = 1L shl n
        for (mask in 1L until limit) {
            val cards = hand.filterIndexed { index, _ -> (mask and (1L shl index)) != 0L }
            val validation = if (context.leading) {
                PaoDeKuaiRules.validateLead(cards, n)
            } else {
                PaoDeKuaiRules.validateFollow(cards, context.previous, n)
            }
            if (!validation.ok) continue
            val remainder = hand.filterIndexed { index, _ -> (mask and (1L shl index)) == 0L }
            val disruption = groupDisruptionPenalty(handCounts, cards, validation.pattern)
            val leaves = remainder.size
            var score = patternBaseScore(validation.pattern.type)
            score += cards.size * if (context.leading) 92 else 12
            score -= validation.pattern.mainRank.value * if (context.leading) 2 else 10
            score += evaluateRemainingHand(remainder)
            score -= disruption * if (context.leading) 2 else 3
            if (leaves == 0) score += 100_000 else if (leaves <= 2) score += 450
            if (!context.leading) score += evaluateRemainingHand(remainder)
            val candidate = Candidate(cards, validation.pattern, remainder, disruption, score)
            result += candidate.copy(score = candidate.score + tacticalAdjustment(candidate, context))
        }
        return result
    }

    private fun patternBaseScore(type: PatternType): Int = when (type) {
        PatternType.Straight -> 800
        PatternType.ConsecutivePairs -> 760
        PatternType.Plane -> 720
        PatternType.TripleWithPair -> 650
        PatternType.TripleWithOne -> 600
        PatternType.Pair -> 300
        PatternType.Single -> 150
        PatternType.Bomb -> 80
        PatternType.Invalid -> 0
    }

    private fun countRanks(cards: List<Card>): Map<Rank, Int> =
        cards.groupingBy { it.rank }.eachCount().toSortedMap(compareBy { it.value })

    private fun isConsecutive(ranks: List<Rank>): Boolean =
        ranks.isNotEmpty() && ranks.none { it == Rank.Two } && ranks.zipWithNext().all { (a, b) -> b.value == a.value + 1 }

    private fun bestConsecutiveRunScore(ranks: List<Rank>, minLength: Int, perRankScore: Int): Int {
        if (ranks.size < minLength) return 0
        var best = 0
        for (start in ranks.indices) {
            for (end in start until ranks.size) {
                val run = ranks.subList(start, end + 1)
                if (!isConsecutive(run)) break
                val length = run.size
                if (length >= minLength) best = maxOf(best, length * perRankScore + run.last().value * 4)
            }
        }
        return best
    }

    private fun evaluateRemainingHand(cards: List<Card>): Int {
        if (cards.isEmpty()) return 6000
        val counts = countRanks(cards)
        var score = -cards.size * 10
        var singleCount = 0
        val straightRanks = mutableListOf<Rank>()
        val pairRanks = mutableListOf<Rank>()
        val tripleRanks = mutableListOf<Rank>()
        for ((rank, count) in counts) {
            when (count) {
                1 -> {
                    singleCount++
                    score -= 130 - rank.value * 4
                }
                2 -> score += 220 + rank.value * 5
                3 -> score += 430 + rank.value * 7
                else -> score += 900 + rank.value * 10
            }
            if (rank != Rank.Two) straightRanks += rank
            if (count >= 2 && rank != Rank.Two) pairRanks += rank
            if (count >= 3 && rank != Rank.Two) tripleRanks += rank
        }
        score -= singleCount * singleCount * 35
        score += bestConsecutiveRunScore(straightRanks, 5, 95)
        score += bestConsecutiveRunScore(pairRanks, 2, 115)
        score += bestConsecutiveRunScore(tripleRanks, 2, 190)
        if (cards.size <= 2) score += 320
        return score
    }

    private fun coreUsage(cards: List<Card>, pattern: HandPattern): Map<Rank, Int> {
        val playedCounts = countRanks(cards)
        return when (pattern.type) {
            PatternType.TripleWithOne,
            PatternType.TripleWithPair -> mapOf(pattern.mainRank to 3)
            PatternType.Plane -> {
                val tripleRanks = playedCounts.filter { it.key != Rank.Two && it.value >= 3 }.keys.toList()
                for (start in tripleRanks.indices) {
                    for (end in start until tripleRanks.size) {
                        val run = tripleRanks.subList(start, end + 1)
                        if (run.size > pattern.groupCount) break
                        if (!isConsecutive(run)) break
                        if (run.size == pattern.groupCount && run.last() == pattern.mainRank) {
                            return run.associateWith { 3 }
                        }
                    }
                }
                emptyMap()
            }
            PatternType.Invalid -> emptyMap()
            else -> playedCounts
        }
    }

    private fun groupDisruptionPenalty(
        beforeCounts: Map<Rank, Int>,
        played: List<Card>,
        pattern: HandPattern,
    ): Int {
        val playedCounts = countRanks(played)
        val coreUsage = coreUsage(played, pattern)
        var penalty = 0
        for ((rank, beforeCount) in beforeCounts) {
            val used = playedCounts[rank] ?: 0
            if (used == 0) continue
            val coreUsed = coreUsage[rank] ?: 0
            val kickerUsed = maxOf(0, used - coreUsed)
            if (kickerUsed > 0) {
                penalty += when {
                    beforeCount >= 4 -> 900
                    beforeCount == 3 -> 620
                    beforeCount == 2 -> 360
                    else -> 0
                }
                penalty += rank.value * 8
            }
            penalty += when {
                beforeCount >= 4 && used in 1..3 -> 900
                beforeCount == 3 && used in 1..2 -> 520
                beforeCount == 2 && used == 1 -> 320
                else -> 0
            }
        }
        return penalty
    }

    private fun totalCardsOfRank(rank: Rank): Int = when (rank) {
        Rank.Two -> 1
        Rank.Ace -> 3
        else -> 4
    }

    private fun countRank(cards: List<Card>, rank: Rank): Int = cards.count { it.rank == rank }

    private fun unknownRankCount(candidate: Candidate, context: AiContext, rank: Rank): Int {
        val known = countRank(context.playedCards, rank) + countRank(candidate.cards, rank) + countRank(candidate.remainder, rank)
        return maxOf(0, totalCardsOfRank(rank) - known)
    }

    private fun unknownHigherControlCount(candidate: Candidate, context: AiContext, rank: Rank): Int {
        var count = 0
        if (rank.value < Rank.King.value) count += unknownRankCount(candidate, context, Rank.King)
        if (rank.value < Rank.Ace.value) count += unknownRankCount(candidate, context, Rank.Ace)
        if (rank.value < Rank.Two.value) count += unknownRankCount(candidate, context, Rank.Two)
        return count
    }

    private fun provenSingleControlBonus(candidate: Candidate, context: AiContext): Int {
        if (candidate.pattern.type != PatternType.Single || candidate.pattern.mainRank.value >= Rank.Ace.value) return 0
        var best = 0
        val candidateRank = candidate.pattern.mainRank.value
        context.passObservations.forEachIndexed { index, observation ->
            if (index == context.currentPlayerIndex || context.remainingCards.getOrElse(index) { 0 } <= 0) return@forEachIndexed
            if (observation?.pattern?.type != PatternType.Single) return@forEachIndexed
            val failedRank = observation.pattern.mainRank.value
            if (candidateRank >= failedRank) best = maxOf(best, 760 - (candidateRank - failedRank) * 35)
        }
        return best
    }

    private fun tacticalAdjustment(candidate: Candidate, context: AiContext): Int {
        val leaves = candidate.remainder.size
        var score = 0
        val urgentDefense = context.nextPlayerRemainingCards == 1 ||
            (context.minOpponentRemainingCards in 1..2)

        if (context.leading && context.nextPlayerRemainingCards == 1) {
            if (candidate.pattern.type == PatternType.Single) {
                score -= 900
                score += candidate.pattern.mainRank.value * 55
            } else {
                score += 420 + candidate.pattern.cardCount * 25
            }
        }
        if (!context.leading && context.nextPlayerRemainingCards == 1 &&
            context.previous.type == PatternType.Single &&
            candidate.pattern.type == PatternType.Single
        ) {
            score += candidate.pattern.mainRank.value * 95
        }
        if (context.minOpponentRemainingCards in 1..2) score += candidate.pattern.cardCount * 35

        if (context.leading && !urgentDefense && leaves > 3) {
            val rankValue = candidate.pattern.mainRank.value
            if (candidate.pattern.type == PatternType.Single) {
                score -= rankValue * 42
                if (rankValue >= Rank.Ace.value) score -= 520
                if (candidate.pattern.mainRank == Rank.King || candidate.pattern.mainRank == Rank.Ace) {
                    score += when (unknownHigherControlCount(candidate, context, candidate.pattern.mainRank)) {
                        0 -> 260
                        1 -> 120
                        else -> 0
                    }
                }
            } else if (candidate.pattern.type == PatternType.Pair) {
                score -= rankValue * 18
                if (rankValue >= Rank.Ace.value) score -= 360
            } else {
                score -= rankValue * 3
            }
        }
        if (context.leading && !urgentDefense) score += provenSingleControlBonus(candidate, context)

        if (candidate.pattern.type == PatternType.Bomb) {
            val critical = leaves == 0 || leaves <= 2 || context.minOpponentRemainingCards in 1..2
            score += 180
            if (critical) score += 850 else score -= if (context.leading) 250 else 500
            if (!context.leading && context.previous.type != PatternType.Bomb && !critical) score -= 550
        }
        return score
    }
}
