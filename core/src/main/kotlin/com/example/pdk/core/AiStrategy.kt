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
    val lastMovePlayerIndex: Int = 0,
    val trickLeaderIndex: Int = 0,
    val roundLeaderIndex: Int = 0,
    val currentTrickPassCount: Int = 0,
    val nextPlayerRemainingCards: Int = 0,
    val minOpponentRemainingCards: Int = 0,
    val remainingCards: List<Int> = listOf(0, 0, 0),
    val playedCards: List<Card> = emptyList(),
    val passObservations: List<PassObservation?> = listOf(null, null, null),
    val passHistory: List<List<PassObservation>> = listOf(emptyList(), emptyList(), emptyList()),
)

enum class LocalAiKind {
    Basic,
    Strong,
}

fun normalizeLocalAiName(value: String): String = when (value.trim().lowercase()) {
    "", "local", "basic" -> "basic"
    "strong" -> "strong"
    else -> value
}

fun localAiKindFromName(value: String): LocalAiKind = when (normalizeLocalAiName(value)) {
    "strong" -> LocalAiKind.Strong
    else -> LocalAiKind.Basic
}

class BasicAiStrategy {
    fun chooseMove(hand: List<Card>, context: AiContext): AiMoveChoice =
        recommendMoves(hand, context, 1).firstOrNull()
            ?: AiMoveChoice(true, reason = if (context.leading) "没有可出的牌型" else "压不过，选择不要")

    fun recommendMoves(hand: List<Card>, context: AiContext, limit: Int = 3): List<AiMoveChoice> {
        val candidates = generateCandidates(hand, context, exhaustive = true)
        if (candidates.isEmpty()) {
            return listOf(AiMoveChoice(true, reason = if (context.leading) "没有可出的牌型" else "压不过，选择不要"))
        }
        val recommendations = mutableListOf<AiMoveChoice>()
        val seen = mutableSetOf<String>()
        candidates
            .sortedWith(basicCandidateComparator)
            .forEach { candidate ->
                if (recommendations.size >= limit.coerceAtLeast(0)) return@forEach
                if (!seen.add(candidate.key)) return@forEach
                recommendations += AiMoveChoice(
                    pass = false,
                    cards = candidate.cards,
                    pattern = candidate.pattern,
                    reason = "基础 AI 推荐 ${candidate.pattern.type.displayName}",
                    disruptionPenalty = candidate.disruptionPenalty,
                )
            }
        return recommendations
    }
}

class StrongAiStrategy {
    fun chooseMove(hand: List<Card>, context: AiContext): AiMoveChoice =
        recommendMoves(hand, context, 1).firstOrNull()
            ?: AiMoveChoice(true, reason = if (context.leading) "强 AI 没有可出的牌型" else "强 AI 压牌失败")

    fun recommendMoves(hand: List<Card>, context: AiContext, limit: Int = 3): List<AiMoveChoice> {
        val candidates = rankStrongCandidates(hand, context)
        if (candidates.isEmpty()) {
            return listOf(AiMoveChoice(true, reason = if (context.leading) "强 AI 没有可出的牌型" else "强 AI 压牌失败"))
        }
        return candidates
            .take(limit.coerceAtLeast(0))
            .map { candidate ->
                AiMoveChoice(
                    pass = false,
                    cards = candidate.cards,
                    pattern = candidate.pattern,
                    reason = "强 AI 推荐 ${candidate.pattern.type.displayName}",
                    disruptionPenalty = candidate.disruptionPenalty,
                )
            }
    }
}

private data class Candidate(
    val cards: List<Card>,
    val pattern: HandPattern,
    val remainder: List<Card>,
    val disruptionPenalty: Int,
    var score: Int,
) {
    val key: String = candidateKey(this)
}

private val basicCandidateComparator =
    compareByDescending<Candidate> { it.score }
        .thenByDescending { it.cards.size }
        .thenBy { it.pattern.mainRank.value }

private val strongCandidateComparator =
    compareByDescending<Candidate> { it.score }
        .thenByDescending { it.cards.size }
        .thenByDescending { patternBaseScore(it.pattern.type) }
        .thenBy { it.pattern.mainRank.value }

private data class HandView(
    val cards: List<Card>,
    val byRank: Map<Rank, List<Card>>,
    val ranks: List<Rank>,
) {
    fun take(rank: Rank, count: Int): List<Card> = byRank[rank]?.take(count).orEmpty()
    fun count(rank: Rank): Int = byRank[rank]?.size ?: 0
}

private fun generateCandidates(hand: List<Card>, context: AiContext, exhaustive: Boolean = false): MutableList<Candidate> {
    val n = hand.size
    if (n == 0 || n > 20) return mutableListOf()
    val handCounts = countRanks(hand)
    val result = mutableListOf<Candidate>()
    val seen = mutableSetOf<String>()
    val cardLists = if (exhaustive) {
        generateExhaustiveCandidateCardLists(hand, context)
    } else {
        generateCandidateCardLists(hand, context)
    }
    for (cards in cardLists) {
        if (!exhaustive && !seen.add(candidateCardKey(cards))) continue
        val validation = if (context.leading) {
            PaoDeKuaiRules.validateLead(cards, n)
        } else {
            PaoDeKuaiRules.validateFollow(cards, context.previous, n)
        }
        if (!validation.ok) continue
        val played = cards.toSet()
        val remainder = hand.filter { it !in played }
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
        candidate.score += tacticalAdjustment(candidate, context)
        result += candidate
    }
    return result
}

private fun generateExhaustiveCandidateCardLists(hand: List<Card>, context: AiContext): List<List<Card>> {
    val n = hand.size
    val masks = if (context.leading) {
        (1L until (1L shl n)).asIterable()
    } else {
        followCandidateMasks(n, context.previous)
    }
    return masks.map { mask ->
        buildList {
            for (i in 0 until n) {
                if ((mask and (1L shl i)) != 0L) add(hand[i])
            }
        }
    }
}

private fun handView(hand: List<Card>): HandView {
    val byRank = hand.sortedByGameOrder().groupBy { it.rank }.toSortedMap(compareBy { it.value })
    return HandView(hand.sortedByGameOrder(), byRank, byRank.keys.toList())
}

private fun generateCandidateCardLists(hand: List<Card>, context: AiContext): List<List<Card>> {
    val view = handView(hand)
    return if (context.leading) generateLeadCardLists(view, hand.size) else generateFollowCardLists(view, hand.size, context.previous)
}

private fun generateLeadCardLists(view: HandView, handSize: Int): List<List<Card>> = buildList {
    addSingles(view)
    addPairs(view)
    addStraights(view, minLength = 5)
    addConsecutivePairs(view, minGroups = 2)
    addTripleWithPair(view, minRank = null)
    addFinalShortTriples(view, handSize, minRank = null)
    addPlanes(view, handSize, expectedGroups = null, minRank = null)
    addBombs(view, minRank = null)
}

private fun generateFollowCardLists(view: HandView, handSize: Int, previous: HandPattern): List<List<Card>> = buildList {
    when (previous.type) {
        PatternType.Single -> addSingles(view, previous.mainRank)
        PatternType.Pair -> addPairs(view, previous.mainRank)
        PatternType.Straight -> addStraights(view, minLength = previous.cardCount, maxLength = previous.cardCount, minRank = previous.mainRank)
        PatternType.ConsecutivePairs -> addConsecutivePairs(
            view,
            minGroups = previous.cardCount / 2,
            maxGroups = previous.cardCount / 2,
            minRank = previous.mainRank,
        )
        PatternType.TripleWithPair -> addTripleWithPair(view, previous.mainRank)
        PatternType.TripleWithOne -> addFinalShortTriples(view, handSize, previous.mainRank)
        PatternType.Plane -> addPlanes(view, handSize, expectedGroups = previous.groupCount, minRank = previous.mainRank)
        PatternType.Bomb -> addBombs(view, previous.mainRank)
        PatternType.Invalid -> Unit
    }
    if (previous.type != PatternType.Bomb) addBombs(view, minRank = null)
}

private fun MutableList<List<Card>>.addSingles(view: HandView, minRank: Rank? = null) {
    for (rank in view.ranks) {
        if (minRank != null && rank.value <= minRank.value) continue
        add(view.take(rank, 1))
    }
}

private fun MutableList<List<Card>>.addPairs(view: HandView, minRank: Rank? = null) {
    for (rank in view.ranks) {
        if (view.count(rank) < 2) continue
        if (minRank != null && rank.value <= minRank.value) continue
        add(view.take(rank, 2))
    }
}

private fun MutableList<List<Card>>.addBombs(view: HandView, minRank: Rank? = null) {
    for (rank in view.ranks) {
        if (rank == Rank.Ace || rank == Rank.Two || view.count(rank) < 4) continue
        if (minRank != null && rank.value <= minRank.value) continue
        add(view.take(rank, 4))
    }
}

private fun MutableList<List<Card>>.addTripleWithPair(view: HandView, minRank: Rank? = null) {
    val triples = view.ranks.filter { view.count(it) >= 3 && (minRank == null || it.value > minRank.value) }
    val pairs = view.ranks.filter { view.count(it) >= 2 }
    for (triple in triples) {
        for (pair in pairs) {
            if (pair == triple) continue
            add(view.take(triple, 3) + view.take(pair, 2))
        }
    }
}

private fun MutableList<List<Card>>.addFinalShortTriples(view: HandView, handSize: Int, minRank: Rank? = null) {
    if (handSize !in 3..4) return
    for (rank in view.ranks) {
        if (view.count(rank) < 3) continue
        if (minRank != null && rank.value <= minRank.value) continue
        val core = view.take(rank, 3)
        if (handSize == 3) {
            add(core)
        } else {
            for (kicker in view.cards.filter { it !in core }) add(core + kicker)
        }
    }
}

private fun MutableList<List<Card>>.addStraights(
    view: HandView,
    minLength: Int,
    maxLength: Int = Int.MAX_VALUE,
    minRank: Rank? = null,
) {
    val available = view.ranks.filter { it != Rank.Two && view.count(it) >= 1 }
    for (run in consecutiveRuns(available, minLength, maxLength)) {
        if (minRank != null && run.last().value <= minRank.value) continue
        add(run.flatMap { view.take(it, 1) })
    }
}

private fun MutableList<List<Card>>.addConsecutivePairs(
    view: HandView,
    minGroups: Int,
    maxGroups: Int = Int.MAX_VALUE,
    minRank: Rank? = null,
) {
    val available = view.ranks.filter { it != Rank.Two && view.count(it) >= 2 }
    for (run in consecutiveRuns(available, minGroups, maxGroups)) {
        if (minRank != null && run.last().value <= minRank.value) continue
        add(run.flatMap { view.take(it, 2) })
    }
}

private fun MutableList<List<Card>>.addPlanes(
    view: HandView,
    handSize: Int,
    expectedGroups: Int?,
    minRank: Rank?,
) {
    val available = view.ranks.filter { it != Rank.Two && view.count(it) >= 3 }
    val minGroups = expectedGroups ?: 2
    val maxGroups = expectedGroups ?: Int.MAX_VALUE
    for (run in consecutiveRuns(available, minGroups, maxGroups)) {
        if (minRank != null && run.last().value <= minRank.value) continue
        val core = run.flatMap { view.take(it, 3) }
        val remaining = view.cards.filter { it !in core }
        val groups = run.size
        val maxKickers = minOf(groups * 2, remaining.size)
        for (kickerCount in 0..maxKickers) {
            if (kickerCount < groups && core.size + kickerCount != handSize) continue
            combinations(remaining, kickerCount) { kickers -> add(core + kickers) }
        }
    }
}

private fun consecutiveRuns(ranks: List<Rank>, minLength: Int, maxLength: Int): List<List<Rank>> {
    if (minLength <= 0 || ranks.size < minLength) return emptyList()
    val result = mutableListOf<List<Rank>>()
    val sorted = ranks.sortedBy { it.value }
    for (start in sorted.indices) {
        val run = mutableListOf(sorted[start])
        for (end in start until sorted.size) {
            if (end > start) {
                val previous = sorted[end - 1]
                val current = sorted[end]
                if (current.value != previous.value + 1) break
                run += current
            }
            if (run.size >= minLength) {
                if (run.size > maxLength) break
                result += run.toList()
            }
        }
    }
    return result
}

private fun <T> combinations(items: List<T>, choose: Int, consume: (List<T>) -> Unit) {
    if (choose < 0 || choose > items.size) return
    if (choose == 0) {
        consume(emptyList())
        return
    }
    val selected = ArrayList<T>(choose)
    fun visit(start: Int, remaining: Int) {
        if (remaining == 0) {
            consume(selected.toList())
            return
        }
        val lastStart = items.size - remaining
        for (index in start..lastStart) {
            selected += items[index]
            visit(index + 1, remaining - 1)
            selected.removeAt(selected.lastIndex)
        }
    }
    visit(0, choose)
}

private fun candidateCardKey(cards: List<Card>): String = cards.sortedByGameOrder().joinToString("|") { it.id }

private fun candidateKey(candidate: Candidate): String = buildString {
    append(candidate.pattern.type)
    append(':')
    append(candidate.pattern.mainRank.label)
    append(':')
    append(candidate.pattern.cardCount)
    append(':')
    append(candidate.pattern.groupCount)
    countRanks(candidate.cards).forEach { (rank, count) ->
        append(':')
        append(rank.label)
        append(count)
    }
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

private fun countRank(cards: List<Card>, rank: Rank): Int = cards.count { it.rank == rank }

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
        PatternType.TripleWithPair,
        -> mapOf(pattern.mainRank to 3)
        PatternType.Plane -> {
            val tripleRanks = playedCounts.filter { it.key != Rank.Two && it.value >= 3 }.keys.toList()
            for (start in tripleRanks.indices) {
                for (end in start until tripleRanks.size) {
                    val run = tripleRanks.subList(start, end + 1)
                    if (run.size > pattern.groupCount) break
                    if (!isConsecutive(run)) break
                    if (run.size == pattern.groupCount && run.last() == pattern.mainRank) return run.associateWith { 3 }
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
    val urgentDefense = context.nextPlayerRemainingCards == 1 || context.minOpponentRemainingCards in 1..2

    if (context.leading && context.nextPlayerRemainingCards == 1) {
        if (candidate.pattern.type == PatternType.Single) {
            score -= 900
            score += candidate.pattern.mainRank.value * 55
        } else {
            score += 420 + candidate.pattern.cardCount * 25
        }
    }
    if (!context.leading &&
        context.nextPlayerRemainingCards == 1 &&
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

private fun sameObservationClass(lhs: HandPattern, rhs: HandPattern): Boolean {
    if (lhs.type != rhs.type) return false
    return when (lhs.type) {
        PatternType.Straight,
        PatternType.ConsecutivePairs,
        PatternType.Plane,
        -> lhs.cardCount == rhs.cardCount && lhs.groupCount == rhs.groupCount
        PatternType.Single,
        PatternType.Pair,
        PatternType.TripleWithOne,
        PatternType.TripleWithPair,
        PatternType.Bomb,
        -> lhs.cardCount == rhs.cardCount
        PatternType.Invalid -> false
    }
}

private fun observationProvesCannotBeat(candidate: Candidate, observation: PassObservation): Boolean {
    if (observation.remainingCards <= 0) return false
    if (candidate.pattern.type == PatternType.Bomb && observation.pattern.type != PatternType.Bomb) return true
    if (!sameObservationClass(candidate.pattern, observation.pattern)) return false
    return candidate.pattern.mainRank.value >= observation.pattern.mainRank.value
}

private fun isWholeHandLead(cards: List<Card>): Boolean =
    cards.isNotEmpty() && PaoDeKuaiRules.validateLead(cards, cards.size).ok

private fun canFinishWithinTwoLeads(cards: List<Card>): Boolean {
    val n = cards.size
    if (n <= 0) return true
    if (isWholeHandLead(cards)) return true
    if (n > 16) return false

    val limit = 1 shl n
    for (mask in 1 until limit - 1) {
        val first = mutableListOf<Card>()
        val second = mutableListOf<Card>()
        for (i in 0 until n) {
            if ((mask and (1 shl i)) != 0) first += cards[i] else second += cards[i]
        }
        if (PaoDeKuaiRules.validateLead(first, n).ok && PaoDeKuaiRules.validateLead(second, second.size).ok) return true
    }
    return false
}

private fun finishPlanBonus(remainder: List<Card>): Int = when {
    remainder.isEmpty() -> 0
    isWholeHandLead(remainder) -> 3600 + remainder.size * 80
    canFinishWithinTwoLeads(remainder) -> 1700 + remainder.size * 35
    else -> 0
}

private fun cardSetMask(cards: List<Card>): Long {
    var mask = 0L
    for (card in cards) {
        val rankOffset = card.rank.value - Rank.Three.value
        val bit = rankOffset * 4 + card.suit.ordinal
        mask = mask or (1L shl bit)
    }
    return mask
}

private fun appendMasksWithCardCount(n: Int, count: Int, masks: MutableList<Long>) {
    if (count <= 0 || count > n || n >= 63) return
    var mask = (1L shl count) - 1L
    val limit = 1L shl n
    while (mask < limit) {
        masks += mask
        val smallest = mask and -mask
        val ripple = mask + smallest
        if (ripple == 0L) break
        mask = (((mask xor ripple) ushr 2) / smallest) or ripple
    }
}

private fun followCandidateMasks(n: Int, previous: HandPattern): List<Long> {
    val counts = mutableListOf(4)
    if (previous.type == PatternType.Plane) {
        val minCards = previous.groupCount * 4
        val maxCards = previous.groupCount * 5
        for (count in minCards..maxCards) {
            if (count !in counts) counts += count
        }
    } else if (previous.cardCount !in counts) {
        counts += previous.cardCount
    }
    return buildList {
        counts.forEach { appendMasksWithCardCount(n, it, this) }
    }.sorted()
}

private fun patternKey(pattern: HandPattern): Long {
    var key = pattern.type.ordinal.toLong()
    key = (key shl 8) or pattern.mainRank.value.toLong()
    key = (key shl 8) or pattern.cardCount.toLong()
    key = (key shl 8) or pattern.groupCount.toLong()
    return key
}

private data class FollowCacheKey(val handMask: Long, val pattern: Long, val handSize: Int)

private val followMoveCache = mutableMapOf<FollowCacheKey, Boolean>()
private val minimumLeadCountCache = mutableMapOf<Long, Int>()

private fun cachedHasAnyFollowMove(hand: List<Card>, previous: HandPattern, handSizeBeforePlay: Int): Boolean {
    val key = FollowCacheKey(cardSetMask(hand), patternKey(previous), handSizeBeforePlay)
    return followMoveCache.getOrPut(key) {
        PaoDeKuaiRules.hasAnyFollowMove(hand, previous, handSizeBeforePlay)
    }
}

private fun minimumLeadCount(cards: List<Card>): Int {
    val n = cards.size
    if (n == 0) return 0
    if (n > 16) return 8
    val cacheKey = cardSetMask(cards)
    minimumLeadCountCache[cacheKey]?.let { return it }

    val fullMask = (1 shl n) - 1
    val legalMasks = mutableListOf<Int>()
    for (mask in 1..fullMask) {
        val play = mutableListOf<Card>()
        for (i in 0 until n) if ((mask and (1 shl i)) != 0) play += cards[i]
        if (PaoDeKuaiRules.validateLead(play, n).ok) legalMasks += mask
    }

    val inf = 99
    val dp = IntArray(fullMask + 1) { inf }
    dp[0] = 0
    for (mask in 0..fullMask) {
        val current = dp[mask]
        if (current >= inf) continue
        val remaining = fullMask xor mask
        for (legal in legalMasks) {
            if ((legal and remaining) == legal) {
                val next = mask or legal
                dp[next] = minOf(dp[next], current + 1)
            }
        }
    }
    return dp[fullMask].also { minimumLeadCountCache[cacheKey] = it }
}

private fun leadCountPlanBonus(remainder: List<Card>): Int {
    val count = minimumLeadCount(remainder)
    if (count == 0) return 0
    var score = -count * 650
    score += when (count) {
        1 -> 4000
        2 -> 2200
        3 -> 600
        else -> 0
    }
    return score
}

private fun unknownBombRankCount(candidate: Candidate, context: AiContext): Int {
    var bombs = 0
    for (rank in Rank.entries) {
        if (rank.value !in Rank.Three.value..Rank.King.value) continue
        if (unknownRankCount(candidate, context, rank) >= 4) bombs++
    }
    return bombs
}

private fun unknownPatternBeaterPressureForPattern(candidate: Candidate, pattern: HandPattern, context: AiContext): Int {
    var pressure = 0
    when (pattern.type) {
        PatternType.Single -> {
            for (rank in Rank.entries.filter { it.value > pattern.mainRank.value && it.value <= Rank.Two.value }) {
                pressure += unknownRankCount(candidate, context, rank)
            }
        }
        PatternType.Pair -> {
            for (rank in Rank.entries.filter { it.value > pattern.mainRank.value && it.value <= Rank.Ace.value }) {
                if (unknownRankCount(candidate, context, rank) >= 2) pressure += 2
            }
        }
        PatternType.Straight,
        PatternType.ConsecutivePairs,
        PatternType.Plane,
        PatternType.TripleWithOne,
        PatternType.TripleWithPair,
        -> {
            for (rank in Rank.entries.filter { it.value > pattern.mainRank.value && it.value <= Rank.Ace.value }) {
                if (unknownRankCount(candidate, context, rank) >= 3) pressure += 1
            }
        }
        PatternType.Bomb -> {
            for (rank in Rank.entries.filter { it.value > pattern.mainRank.value && it.value <= Rank.King.value }) {
                if (unknownRankCount(candidate, context, rank) >= 4) pressure += 4
            }
        }
        PatternType.Invalid -> Unit
    }
    if (pattern.type != PatternType.Bomb) pressure += unknownBombRankCount(candidate, context) * 3
    return pressure
}

private fun unknownPatternBeaterPressure(candidate: Candidate, context: AiContext): Int =
    unknownPatternBeaterPressureForPattern(candidate, candidate.pattern, context)

private fun remainderFinishSafetyBonus(candidate: Candidate, context: AiContext): Int {
    if (candidate.remainder.isEmpty()) return 0
    val result = PaoDeKuaiRules.validateLead(candidate.remainder, candidate.remainder.size)
    if (!result.ok) return 0
    val pressure = unknownPatternBeaterPressureForPattern(candidate, result.pattern, context)
    return when {
        pressure == 0 -> 2600 + result.pattern.cardCount * 110
        pressure <= 2 -> 900 + result.pattern.cardCount * 60
        else -> 0
    }
}

private fun containsExactCard(cards: List<Card>, target: Card): Boolean = cards.any { it == target }

private fun mixSeed(seed: Int, value: Int): Int =
    seed xor (value + 0x9e3779b9.toInt() + (seed shl 6) + (seed ushr 2))

private fun candidateSeed(candidate: Candidate, context: AiContext): Int {
    var seed = 2166136261u.toInt()
    seed = mixSeed(seed, context.currentPlayerIndex)
    seed = mixSeed(seed, context.lastMovePlayerIndex)
    seed = mixSeed(seed, candidate.pattern.cardCount)
    seed = mixSeed(seed, candidate.pattern.groupCount)
    seed = mixSeed(seed, candidate.pattern.mainRank.value)
    for (card in candidate.cards) seed = mixSeed(seed, card.rank.value * 5 + card.suit.ordinal)
    for (card in context.playedCards) seed = mixSeed(seed, card.rank.value * 5 + card.suit.ordinal)
    return seed
}

private fun nextRandom(state: IntArray): Int {
    state[0] = state[0] * 1664525 + 1013904223
    return state[0]
}

private fun shuffleSample(cards: MutableList<Card>, seed: Int) {
    if (cards.size <= 1) return
    val state = intArrayOf(seed)
    for (i in cards.size downTo 2) {
        val j = (Integer.toUnsignedLong(nextRandom(state)) % i).toInt()
        val tmp = cards[i - 1]
        cards[i - 1] = cards[j]
        cards[j] = tmp
    }
}

private fun isConsistentWithPassHistory(hand: List<Card>, playerIndex: Int, context: AiContext): Boolean {
    if (playerIndex !in context.passHistory.indices) return true
    for (observation in context.passHistory[playerIndex]) {
        if (cachedHasAnyFollowMove(hand, observation.pattern, observation.remainingCards)) return false
    }
    val latest = context.passObservations.getOrNull(playerIndex)
    if (latest != null && cachedHasAnyFollowMove(hand, latest.pattern, latest.remainingCards)) return false
    return true
}

private fun unknownOpponentCards(hand: List<Card>, context: AiContext): List<Card> =
    PaoDeKuaiRules.createDeck().filter { card ->
        !containsExactCard(hand, card) && !containsExactCard(context.playedCards, card)
    }

private fun sampledControlBonus(candidate: Candidate, context: AiContext, hand: List<Card>): Int {
    val sampleCount = if (context.currentPlayerIndex == context.roundLeaderIndex) 7 else 5
    val nextIndex = nextIndex(context.currentPlayerIndex)
    val otherIndex = (context.currentPlayerIndex + 1) % 3
    val nextCards = context.remainingCards.getOrElse(nextIndex) { 0 }
    val otherCards = context.remainingCards.getOrElse(otherIndex) { 0 }
    val unknown = unknownOpponentCards(hand, context)
    if (unknown.size != nextCards + otherCards) return 0

    var score = 0
    val seed = candidateSeed(candidate, context)
    for (sample in 0 until sampleCount) {
        val shuffled = unknown.toMutableList()
        shuffleSample(shuffled, mixSeed(seed, sample + 1))
        val nextHand = shuffled.take(nextCards)
        val otherHand = shuffled.drop(nextCards).take(otherCards)
        if (!isConsistentWithPassHistory(nextHand, nextIndex, context) ||
            !isConsistentWithPassHistory(otherHand, otherIndex, context)
        ) {
            continue
        }

        val nextCanBeat = cachedHasAnyFollowMove(nextHand, candidate.pattern, nextCards)
        val otherCanBeat = cachedHasAnyFollowMove(otherHand, candidate.pattern, otherCards)
        if (!nextCanBeat && !otherCanBeat) {
            score += if (context.leading) 620 else 520
        } else {
            if (nextCanBeat) {
                score -= if (context.nextPlayerRemainingCards <= 3) 520 else 170
                if (PaoDeKuaiRules.validateFollow(nextHand, candidate.pattern, nextCards).ok) score -= 900
            }
            if (otherCanBeat) {
                score -= if (otherCards <= 3) 460 else 130
                if (PaoDeKuaiRules.validateFollow(otherHand, candidate.pattern, otherCards).ok) score -= 760
            }
        }
    }
    return score / sampleCount
}

private fun nextIndex(index: Int): Int = (index + 2) % 3

private data class RolloutState(
    val hands: MutableList<MutableList<Card>> = MutableList(3) { mutableListOf() },
    var currentIndex: Int = 0,
    var lastMoveIndex: Int = 0,
    var trickLeaderIndex: Int = 0,
    var roundLeaderIndex: Int = 0,
    var lastPattern: HandPattern? = null,
    val playedCards: MutableList<Card> = mutableListOf(),
    val passObservations: MutableList<PassObservation?> = MutableList(3) { null },
    val passHistory: MutableList<MutableList<PassObservation>> = MutableList(3) { mutableListOf() },
    var passCount: Int = 0,
)

private fun removeRolloutCards(hand: MutableList<Card>, cards: List<Card>) {
    for (card in cards) hand.remove(card)
}

private fun recordRolloutPass(state: RolloutState, playerIndex: Int, pattern: HandPattern) {
    val observation = PassObservation(pattern, state.hands[playerIndex].size)
    state.passHistory[playerIndex] += observation
    state.passObservations[playerIndex] = observation
}

private fun rolloutContext(state: RolloutState): AiContext {
    val remaining = state.hands.map { it.size }
    return AiContext(
        leading = state.lastPattern == null,
        previous = state.lastPattern ?: HandPattern(),
        ownRemainingCards = state.hands[state.currentIndex].size,
        currentPlayerIndex = state.currentIndex,
        lastMovePlayerIndex = state.lastMoveIndex,
        trickLeaderIndex = if (state.lastPattern == null) state.currentIndex else state.trickLeaderIndex,
        roundLeaderIndex = state.roundLeaderIndex,
        currentTrickPassCount = state.passCount,
        nextPlayerRemainingCards = state.hands[nextIndex(state.currentIndex)].size,
        minOpponentRemainingCards = remaining.filterIndexed { index, _ -> index != state.currentIndex }.minOrNull() ?: 0,
        remainingCards = remaining,
        playedCards = state.playedCards,
        passObservations = state.passObservations.toList(),
        passHistory = state.passHistory.map { it.toList() },
    )
}

private fun chooseRolloutMove(hand: List<Card>, context: AiContext, strongSelf: Boolean): AiMoveChoice {
    if (!strongSelf) return BasicAiStrategy().chooseMove(hand, context)

    val candidates = generateCandidates(hand, context, exhaustive = true)
    if (candidates.isEmpty()) {
        return AiMoveChoice(true, reason = if (context.leading) "rollout strong no lead" else "rollout strong pass")
    }
    candidates.forEach { it.score += strongAdjustment(it, context) }
    candidates.sortWith(
        compareByDescending<Candidate> { it.score }
            .thenByDescending { it.cards.size }
            .thenBy { it.pattern.mainRank.value },
    )
    if (context.leading && context.currentPlayerIndex == context.roundLeaderIndex) deduplicateCandidates(candidates)

    val planLimit = minOf(if (context.leading) 10 else 8, candidates.size)
    for (i in 0 until planLimit) {
        val candidate = candidates[i]
        if (!context.leading || candidate.remainder.size <= 12) {
            val planBonus = leadCountPlanBonus(candidate.remainder)
            val leadPlanWeight = if (context.currentPlayerIndex == context.roundLeaderIndex) 3 else 5
            candidate.score += if (context.leading) planBonus * leadPlanWeight else planBonus
        }
        if (!context.leading && context.currentTrickPassCount > 0) {
            candidate.score += sampledControlBonus(candidate, context, hand)
        }
    }
    candidates.subList(0, planLimit).sortWith(
        compareByDescending<Candidate> { it.score }
            .thenByDescending { it.cards.size }
            .thenBy { it.pattern.mainRank.value },
    )

    val best = candidates.first()
    return AiMoveChoice(false, best.cards, best.pattern, "rollout strong", best.disruptionPenalty)
}

private fun rolloutWinner(state: RolloutState, strongIndex: Int): Int {
    for (turn in 0 until 240) {
        val hand = state.hands[state.currentIndex]
        val choice = if (state.currentIndex == strongIndex) {
            chooseRolloutMove(hand, rolloutContext(state), strongSelf = true)
        } else {
            BasicAiStrategy().chooseMove(hand, rolloutContext(state))
        }
        if (choice.pass) {
            val pattern = state.lastPattern ?: return -1
            recordRolloutPass(state, state.currentIndex, pattern)
            state.passCount++
            if (state.passCount >= 2) {
                state.currentIndex = state.lastMoveIndex
                state.lastPattern = null
                state.trickLeaderIndex = state.currentIndex
                state.passCount = 0
            } else {
                state.currentIndex = nextIndex(state.currentIndex)
            }
            continue
        }

        val handSizeBefore = hand.size
        val validation = if (state.lastPattern == null) {
            PaoDeKuaiRules.validateLead(choice.cards, handSizeBefore)
        } else {
            PaoDeKuaiRules.validateFollow(choice.cards, state.lastPattern ?: HandPattern(), handSizeBefore)
        }
        if (!validation.ok) return -1
        if (state.lastPattern == null) state.trickLeaderIndex = state.currentIndex
        removeRolloutCards(hand, choice.cards)
        state.playedCards += choice.cards
        state.lastPattern = validation.pattern
        state.lastMoveIndex = state.currentIndex
        state.passCount = 0
        if (hand.isEmpty()) return state.currentIndex
        state.currentIndex = nextIndex(state.currentIndex)
    }
    return -1
}

private fun scoreRolloutDistribution(
    candidate: Candidate,
    context: AiContext,
    self: Int,
    next: Int,
    other: Int,
    nextHand: List<Card>,
    otherHand: List<Card>,
): Int {
    val state = RolloutState()
    state.hands[self] += candidate.remainder
    state.hands[next] += nextHand
    state.hands[other] += otherHand
    state.currentIndex = next
    state.lastMoveIndex = self
    state.trickLeaderIndex = self
    state.roundLeaderIndex = context.roundLeaderIndex
    state.lastPattern = candidate.pattern
    state.playedCards += context.playedCards
    state.playedCards += candidate.cards
    context.passObservations.forEachIndexed { index, observation -> state.passObservations[index] = observation }
    context.passHistory.forEachIndexed { index, history -> state.passHistory[index] += history }

    val winner = rolloutWinner(state, self)
    val selfWinScore = if (self == context.roundLeaderIndex) 3800 else 2600
    return when {
        winner == self -> selfWinScore
        winner >= 0 -> if (winner == other) -700 else -2300
        else -> 0
    }
}

private fun enumeratedRolloutBonus(
    candidate: Candidate,
    context: AiContext,
    unknown: List<Card>,
    self: Int,
    next: Int,
    other: Int,
    nextCards: Int,
): Int? {
    val n = unknown.size
    if (n > 14 || nextCards < 0 || nextCards > n) return null

    var possible = 0
    val limit = 1 shl n
    for (mask in 0 until limit) {
        if (Integer.bitCount(mask) == nextCards) {
            possible++
            if (possible > 4000) return null
        }
    }

    var total = 0
    var count = 0
    for (mask in 0 until limit) {
        if (Integer.bitCount(mask) != nextCards) continue
        val nextHand = mutableListOf<Card>()
        val otherHand = mutableListOf<Card>()
        for (i in 0 until n) {
            if ((mask and (1 shl i)) != 0) nextHand += unknown[i] else otherHand += unknown[i]
        }
        if (!isConsistentWithPassHistory(nextHand, next, context) ||
            !isConsistentWithPassHistory(otherHand, other, context)
        ) {
            continue
        }
        total += scoreRolloutDistribution(candidate, context, self, next, other, nextHand, otherHand)
        count++
    }
    return if (count == 0) null else total / count
}

private fun rolloutBonus(candidate: Candidate, context: AiContext, hand: List<Card>): Int {
    if (candidate.remainder.size > 10 && context.minOpponentRemainingCards > 8) return 0

    val self = context.currentPlayerIndex
    val next = nextIndex(self)
    val other = nextIndex(next)
    val sampleCount = 5
    val nextCards = context.remainingCards.getOrElse(next) { 0 }
    val otherCards = context.remainingCards.getOrElse(other) { 0 }
    val unknown = unknownOpponentCards(hand, context)
    if (unknown.size != nextCards + otherCards) return 0

    enumeratedRolloutBonus(candidate, context, unknown, self, next, other, nextCards)?.let { return it }

    var score = 0
    var validSamples = 0
    val seed = candidateSeed(candidate, context)
    for (sample in 0 until sampleCount * 4) {
        if (validSamples >= sampleCount) break
        val shuffled = unknown.toMutableList()
        shuffleSample(shuffled, mixSeed(seed, sample + 17))
        val nextHand = shuffled.take(nextCards)
        val otherHand = shuffled.drop(nextCards).take(otherCards)
        if (!isConsistentWithPassHistory(nextHand, next, context) ||
            !isConsistentWithPassHistory(otherHand, other, context)
        ) {
            continue
        }
        validSamples++

        val state = RolloutState()
        state.hands[self] += candidate.remainder
        state.hands[next] += nextHand
        state.hands[other] += otherHand
        state.currentIndex = next
        state.lastMoveIndex = self
        state.trickLeaderIndex = self
        state.roundLeaderIndex = context.roundLeaderIndex
        state.lastPattern = candidate.pattern
        state.playedCards += context.playedCards
        state.playedCards += candidate.cards
        context.passObservations.forEachIndexed { index, observation -> state.passObservations[index] = observation }
        context.passHistory.forEachIndexed { index, history -> state.passHistory[index] += history }

        val winner = rolloutWinner(state, self)
        val selfWinScore = if (self == context.roundLeaderIndex) 3800 else 2600
        if (winner == self) {
            score += selfWinScore
        } else if (winner >= 0) {
            score += if (winner == other) -700 else -2300
        }
    }
    return if (validSamples > 0) score / validSamples else 0
}

private fun shouldUseRollout(candidate: Candidate, context: AiContext): Boolean =
    candidate.remainder.size <= 10 || context.minOpponentRemainingCards <= 8

private fun strongAdjustment(candidate: Candidate, context: AiContext): Int {
    var score = 0
    if (context.leading &&
        candidate.pattern.type == PatternType.Bomb &&
        candidate.remainder.isNotEmpty() &&
        candidate.remainder.size <= 8
    ) {
        score += 1600 + leadCountPlanBonus(candidate.remainder)
    }

    if (context.leading &&
        context.currentPlayerIndex == context.roundLeaderIndex &&
        context.ownRemainingCards >= 13 &&
        candidate.pattern.cardCount >= 5 &&
        candidate.remainder.size <= 11
    ) {
        score += 950 + candidate.pattern.cardCount * 85
    }

    if (!context.leading && context.currentTrickPassCount > 0 && candidate.remainder.isNotEmpty()) {
        val pressure = unknownPatternBeaterPressure(candidate, context)
        score += when {
            pressure == 0 -> 950 + candidate.pattern.cardCount * 70
            pressure <= 2 && candidate.remainder.size <= 8 -> 280 + candidate.pattern.cardCount * 35
            else -> 0
        }
    }

    if (!context.leading &&
        context.previous.type == PatternType.Single &&
        candidate.pattern.type == PatternType.Single &&
        context.ownRemainingCards > 10 &&
        context.minOpponentRemainingCards > 5 &&
        context.previous.mainRank.value <= Rank.Seven.value &&
        candidate.pattern.mainRank.value >= Rank.King.value
    ) {
        val previousRank = context.previous.mainRank.value
        val candidateRank = candidate.pattern.mainRank.value
        val lowerBeaterExists = candidate.remainder.any { it.rank.value > previousRank && it.rank.value < candidateRank }
        if (lowerBeaterExists) score -= if (candidate.pattern.mainRank == Rank.Two) 1900 else 1150
    }

    if (!context.leading &&
        candidate.remainder.isNotEmpty() &&
        context.minOpponentRemainingCards > 2 &&
        (candidate.pattern.type == PatternType.Single || candidate.pattern.type == PatternType.Pair)
    ) {
        val playedCounts = countRanks(candidate.cards)
        val before = candidate.remainder + candidate.cards
        val beforeCounts = countRanks(before)
        for ((rank, playedCount) in playedCounts) {
            val beforeCount = beforeCounts[rank] ?: 0
            if (beforeCount >= 4 && playedCount < 4) {
                score -= 1700 + rank.value * 24
            } else if (beforeCount == 3 && playedCount < 3) {
                score -= 1180 + rank.value * 24
            } else if (beforeCount == 2 && playedCount == 1) {
                score -= 420 + rank.value * 12
            }
        }
    }

    return score
}

private fun rankStrongCandidates(hand: List<Card>, context: AiContext): List<Candidate> {
    val candidates = generateCandidates(hand, context, exhaustive = true)
    if (candidates.isEmpty()) return emptyList()

    candidates.forEach { it.score += strongAdjustment(it, context) }
    candidates.sortWith(strongCandidateComparator)
    if (context.leading && context.currentPlayerIndex == context.roundLeaderIndex) deduplicateCandidates(candidates)

    val planLimit = minOf(if (context.leading) 24 else 18, candidates.size)
    for (i in 0 until planLimit) {
        val candidate = candidates[i]
        if (!context.leading || candidate.remainder.size <= 12) {
            val planBonus = leadCountPlanBonus(candidate.remainder)
            val leadPlanWeight = if (context.currentPlayerIndex == context.roundLeaderIndex) 3 else 5
            candidate.score += if (context.leading) planBonus * leadPlanWeight else planBonus
        }
        if (!context.leading && context.currentTrickPassCount > 0 && candidate.remainder.size <= 10) {
            candidate.score += sampledControlBonus(candidate, context, hand)
        }
        if (!context.leading && candidate.remainder.size <= 8) {
            candidate.score += remainderFinishSafetyBonus(candidate, context) / 2
        }
    }
    candidates.subList(0, planLimit).sortWith(strongCandidateComparator)

    val rolloutLimit = if (context.leading) minOf(8, candidates.size) else minOf(4, candidates.size)
    for (i in 0 until rolloutLimit) {
        val candidate = candidates[i]
        if (candidate.remainder.isEmpty()) continue
        val useFollowRollout = !context.leading && context.currentTrickPassCount > 0 && candidate.remainder.size <= 8
        if (if (context.leading) shouldUseRollout(candidate, context) else useFollowRollout) {
            val bonus = rolloutBonus(candidate, context, hand)
            candidate.score = bonus * 24 + candidate.score / 120
        }
    }
    candidates.subList(0, rolloutLimit).sortWith(strongCandidateComparator)
    return candidates
}

private fun deduplicateCandidates(candidates: MutableList<Candidate>) {
    val seen = mutableSetOf<String>()
    val iterator = candidates.iterator()
    while (iterator.hasNext()) {
        if (!seen.add(iterator.next().key)) iterator.remove()
    }
}
