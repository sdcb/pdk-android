package com.example.pdk.core

import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

data class PlayerState(
    var name: String,
    val hand: MutableList<Card> = mutableListOf(),
    var hasPlayedCards: Boolean = false,
)

enum class GameEventType {
    RoundStarted,
    CardsPlayed,
    Passed,
    InvalidMove,
    Hint,
    Bomb,
    RoundEnded,
    Talk,
}

data class GameEvent(
    val type: GameEventType,
    val player: PlayerId = PlayerId.Player,
    val message: String = "",
    val cards: List<Card> = emptyList(),
)

enum class TurnDecisionSource {
    System,
    LocalAi,
    LlmAi,
    Human,
}

enum class TurnDecisionReason {
    NormalChoice,
    OnlyLegalMove,
    CannotBeat,
    LlmFallback,
    HumanInput,
}

data class GameAction(
    val action: String,
    val ranks: List<String> = emptyList(),
    val talk: String = "",
)

data class TurnRecord(
    val turnNo: Int,
    val actor: PlayerId,
    val source: TurnDecisionSource,
    val reason: TurnDecisionReason,
    val finalAction: GameAction,
    val finalCards: List<Card>,
    val finalPattern: HandPattern?,
    val accepted: Boolean,
    val trace: TurnDecisionTrace = TurnDecisionTrace(),
)

data class RoundRecord(
    val winner: PlayerId = PlayerId.Player,
    val scores: List<Int> = listOf(0, 0, 0),
    val remainingCards: List<Int> = listOf(0, 0, 0),
    val spring: Boolean = false,
    val bombs: Int = 0,
    val springLosers: Int = 0,
)

private data class LocalAiResult(
    val generation: Int,
    val player: PlayerId,
    val turnNo: Int,
    val choices: List<AiMoveChoice>,
    val leading: Boolean,
)

class GameState {
    val players: List<PlayerState> = listOf(
        PlayerState("李姐"),
        PlayerState("AI1"),
        PlayerState("AI2"),
    )
    val selectedIndices: MutableSet<Int> = sortedSetOf()
    val hintIndices: MutableList<Int> = mutableListOf()
    val events: MutableList<GameEvent> = mutableListOf()
    val turnRecords: MutableList<TurnRecord> = mutableListOf()
    val playedCards: MutableList<Card> = mutableListOf()
    val passObservations: MutableList<PassObservation?> = MutableList(3) { null }

    var currentPlayer: PlayerId = PlayerId.Player
        private set
    var lastMovePlayer: PlayerId = PlayerId.Player
        private set
    var lastPattern: HandPattern? = null
        private set
    var lastCards: List<Card> = emptyList()
        private set
    var roundOver: Boolean = true
        private set
    var autoplay: Boolean = false
        private set
    var toast: String = ""
        private set
    var talkText: String = ""
        private set
    var talkPlayer: PlayerId = PlayerId.Ai1
        private set
    var lastRoundRecord: RoundRecord = RoundRecord()
        private set

    private val basicAi = BasicAiStrategy()
    private val strongAi = StrongAiStrategy()
    private val localAiKinds: MutableList<LocalAiKind> = MutableList(3) { LocalAiKind.Basic }
    private val passHistory: MutableList<MutableList<PassObservation>> = MutableList(3) { mutableListOf() }
    private var externalAi: ExternalAiController? = null
    private var externalAiPending = false
    private var localAiPending = false
    private var localAiGeneration = 0
    private var localAiWorker: Thread? = null
    private val localAiResult = AtomicReference<LocalAiResult?>(null)
    private val bombs = mutableListOf<BombScoreEvent>()
    private var passCount = 0
    private var trickLeader: PlayerId = PlayerId.Player
    private var roundLeader: PlayerId = PlayerId.Player
    private var aiDelay = 0f
    private var talkCooldown = 0f
    private var nextTurnNo = 1
    private var nextRoundLeader: PlayerId? = null
    private val random = Random.Default

    val isHumanTurn: Boolean get() = currentPlayer == PlayerId.Player && !roundOver
    val isInLeadState: Boolean get() = currentPlayerLeads()

    fun startNewRound(playerName: String = "李姐", seed: UInt = 0u) {
        val effectiveSeed = if (seed == 0u) newRoundSeed() else seed
        val hands = PaoDeKuaiRules.deal(effectiveSeed)
        players[0].name = playerName.ifBlank { "李姐" }
        for (i in 0 until 3) {
            players[i].hand.clear()
            players[i].hand += hands[i]
            players[i].hasPlayedCards = false
        }
        selectedIndices.clear()
        hintIndices.clear()
        events.clear()
        turnRecords.clear()
        playedCards.clear()
        passObservations.fill(null)
        passHistory.forEach { it.clear() }
        cancelLocalAi()
        bombs.clear()
        lastCards = emptyList()
        lastPattern = null
        passCount = 0
        roundOver = false
        autoplay = false
        aiDelay = 0.35f
        talkCooldown = 0f
        talkText = ""
        toast = ""
        nextTurnNo = 1
        val openingPlayer = nextRoundLeader
        currentPlayer = openingPlayer ?: PaoDeKuaiRules.findFirstPlayerBySpadeThree(hands)
        lastMovePlayer = currentPlayer
        trickLeader = currentPlayer
        roundLeader = currentPlayer
        val openingMessage = if (openingPlayer == null) {
            "${playerDisplayName(currentPlayer, players[0].name)} 持有黑桃 3 先出"
        } else {
            "${playerDisplayName(currentPlayer, players[0].name)} 上局获胜先出"
        }
        events += GameEvent(GameEventType.RoundStarted, currentPlayer, openingMessage)
    }

    private fun newRoundSeed(): UInt {
        val time = System.nanoTime()
        val mixed = time xor (time ushr 32) xor random.nextInt().toLong()
        return mixed.toUInt()
    }

    fun update(dtSeconds: Float) {
        if (roundOver) return
        if (talkCooldown > 0f) talkCooldown = maxOf(0f, talkCooldown - dtSeconds)
        if (currentPlayer == PlayerId.Player && !autoplay) return
        if (externalAiPending) {
            val result = externalAi?.tryGetResult()
            if (result == null) return
            externalAiPending = false
            if (!applyExternalAiResult(result)) startLocalAiTurn(currentPlayer)
            aiDelay = 0.55f
            return
        }
        if (localAiPending) {
            val result = localAiResult.getAndSet(null) ?: return
            localAiPending = false
            localAiWorker = null
            if (result.generation != localAiGeneration ||
                result.player != currentPlayer ||
                result.turnNo != nextTurnNo
            ) {
                return
            }
            if (result.player != PlayerId.Player &&
                externalAi?.canHandle(result.player) == true &&
                result.choices.count { !it.pass } > 1
            ) {
                startExternalAiTurn()
            } else {
                applyLocalAiChoices(result.player, result.turnNo, result.choices, result.leading)
            }
            aiDelay = if (currentPlayer == PlayerId.Player) 0.25f else 0.55f
            return
        }
        aiDelay -= dtSeconds
        if (aiDelay > 0f) return
        startLocalAiTurn(currentPlayer)
    }

    fun setExternalAiController(controller: ExternalAiController?) {
        externalAi?.cancel()
        externalAi = controller
        externalAiPending = false
    }

    fun setLocalAiKind(player: PlayerId, kind: LocalAiKind) {
        cancelLocalAi()
        localAiKinds[player.index()] = kind
    }

    fun setLocalAiKind(player: PlayerId, name: String) {
        setLocalAiKind(player, localAiKindFromName(name))
    }

    fun toggleAutoplay() {
        autoplay = !autoplay
        if (autoplay) clearSelection()
    }

    fun togglePlayerCard(handIndex: Int) {
        if (!isHumanTurn || handIndex !in players[0].hand.indices) return
        if (!selectedIndices.add(handIndex)) selectedIndices.remove(handIndex)
        hintIndices.clear()
    }

    fun clearSelection() {
        selectedIndices.clear()
        hintIndices.clear()
    }

    fun playSelected(): Boolean {
        if (!isHumanTurn) return false
        val cards = selectedCards()
        val handSize = players[0].hand.size
        val validation = if (currentPlayerLeads()) {
            PaoDeKuaiRules.validateLead(cards, handSize)
        } else {
            PaoDeKuaiRules.validateFollow(cards, lastPattern ?: HandPattern(), handSize)
        }
        if (!validation.ok) {
            toast = validation.reason
            events += GameEvent(GameEventType.InvalidMove, PlayerId.Player, toast, cards)
            return false
        }
        val beforeTurn = nextTurnNo
        playCards(PlayerId.Player, cards, validation.pattern)
        turnRecords += TurnRecord(
            beforeTurn,
            PlayerId.Player,
            TurnDecisionSource.Human,
            TurnDecisionReason.HumanInput,
            GameAction("play", cards.map { it.rank.label }),
            cards,
            validation.pattern,
            true,
        )
        nextTurnNo++
        clearSelection()
        return true
    }

    fun passHuman(): Boolean = pass(PlayerId.Player)

    fun applyHint(): Boolean {
        if (!isHumanTurn) return false
        val choice = chooseLocalMove(PlayerId.Player)
        if (choice.pass) {
            toast = "没有可出的牌"
            events += GameEvent(GameEventType.Hint, PlayerId.Player, toast)
            return false
        }
        val recommended = sortedSetOf<Int>()
        choice.cards.forEach { card ->
            val index = players[0].hand.indexOfFirst { it == card }
            if (index >= 0) recommended += index
        }
        if (selectedIndices == recommended) {
            selectedIndices.clear()
            hintIndices.clear()
            toast = "已取消提示选择"
        } else {
            selectedIndices.clear()
            selectedIndices += recommended
            hintIndices.clear()
            hintIndices += recommended
            toast = "已按 AI 逻辑选中推荐牌"
        }
        events += GameEvent(GameEventType.Hint, PlayerId.Player, toast, choice.cards)
        return true
    }

    fun selectBestPatternFromDraggedCards(handIndices: List<Int>): Boolean {
        if (!isHumanTurn) return false
        data class Candidate(val indices: Set<Int>, val pattern: HandPattern, val score: Int)

        val hand = players[0].hand
        val dragIndices = handIndices
            .filter { it in hand.indices }
            .distinct()
            .sorted()
        if (dragIndices.isEmpty() || dragIndices.size >= 63) return false

        fun scorePattern(pattern: HandPattern): Int =
            pattern.cardCount * 100_000 + dragPatternTieBreaker(pattern.type) + pattern.mainRank.value

        fun cardsFor(indices: Set<Int>): List<Card> = indices.sorted().map { hand[it] }

        val candidates = mutableListOf<Candidate>()
        val dragLimit = 1L shl dragIndices.size
        for (mask in 1L until dragLimit) {
            val indices = sortedSetOf<Int>()
            dragIndices.forEachIndexed { bit, index ->
                if ((mask and (1L shl bit)) != 0L) indices += index
            }
            val cards = cardsFor(indices)
            val validation = PaoDeKuaiRules.validateLead(cards, hand.size)
            val pattern = if (validation.ok) validation.pattern else identifyDragOnlyPattern(cards) ?: continue
            candidates += Candidate(indices, pattern, scorePattern(pattern))
        }

        val additiveCandidates = mutableListOf<Candidate>()
        if (selectedIndices.isNotEmpty()) {
            for (mask in 1L until dragLimit) {
                val indices = sortedSetOf<Int>()
                indices += selectedIndices
                dragIndices.forEachIndexed { bit, index ->
                    if ((mask and (1L shl bit)) != 0L) indices += index
                }
                if (indices == selectedIndices) continue
                val validation = PaoDeKuaiRules.validateLead(cardsFor(indices), hand.size)
                if (validation.ok) {
                    additiveCandidates += Candidate(indices, validation.pattern, scorePattern(validation.pattern))
                }
            }
        }

        if (candidates.isEmpty() && additiveCandidates.isEmpty()) return false
        val chosen = candidates.maxByOrNull { it.score }
        val chosenIndices = chosen?.indices ?: emptySet()
        val chosenPattern = chosen?.pattern ?: HandPattern()
        if (chosenIndices.isNotEmpty() && selectedIndices == chosenIndices) {
            selectedIndices.clear()
            hintIndices.clear()
            toast = "已取消拖拽选择"
            return true
        }

        val finalCandidate = if (selectedIndices.isNotEmpty() && additiveCandidates.isNotEmpty()) {
            additiveCandidates.maxBy { it.score }
        } else {
            chosen ?: additiveCandidates.maxBy { it.score }
        }
        val finalIndices = if (selectedIndices.isNotEmpty() && additiveCandidates.isEmpty()) {
            sortedSetOf<Int>().apply {
                addAll(selectedIndices)
                addAll(chosenIndices)
            }
        } else {
            finalCandidate.indices
        }
        val finalPattern = if (selectedIndices.isNotEmpty() && additiveCandidates.isEmpty()) chosenPattern else finalCandidate.pattern

        selectedIndices.clear()
        selectedIndices += finalIndices
        hintIndices.clear()
        hintIndices += selectedIndices
        toast = "已按拖拽路线选中 ${dragPatternDescription(finalPattern)}"
        events += GameEvent(GameEventType.Hint, PlayerId.Player, toast, cardsFor(selectedIndices))
        return true
    }

    fun canCurrentPlayerPass(): Boolean = !currentPlayerLeads() && !hasPlayableFollow(currentPlayer)

    fun clearEvents() {
        events.clear()
    }

    fun testSetRound(
        hands: List<List<Card>>,
        currentPlayer: PlayerId,
        previousPattern: HandPattern?,
        lastMovePlayer: PlayerId,
    ) {
        for (i in 0 until 3) {
            players[i].hand.clear()
            players[i].hand += hands[i].sortedByGameOrder()
            players[i].hasPlayedCards = false
        }
        this.currentPlayer = currentPlayer
        this.lastMovePlayer = lastMovePlayer
        this.trickLeader = if (previousPattern == null) currentPlayer else lastMovePlayer
        this.roundLeader = currentPlayer
        this.lastPattern = previousPattern
        this.lastCards = emptyList()
        playedCards.clear()
        passObservations.fill(null)
        passHistory.forEach { it.clear() }
        roundOver = false
        passCount = 0
        selectedIndices.clear()
        hintIndices.clear()
        events.clear()
        turnRecords.clear()
        nextTurnNo = 1
    }

    private fun selectedCards(): List<Card> = selectedIndices.mapNotNull { players[0].hand.getOrNull(it) }

    private fun identifyDragOnlyPattern(cards: List<Card>): HandPattern? {
        val counts = cards.groupingBy { it.rank }.eachCount()
        if (cards.size == 3 && counts.size == 1) {
            return HandPattern(PatternType.TripleWithOne, cards.first().rank, cards.size, 1, lastHandShort = true)
        }
        if (cards.size < 6 || cards.size % 3 != 0) return null
        if (counts.values.any { it != 3 } || counts.keys.any { it == Rank.Two }) return null
        val ranks = counts.keys.sortedBy { it.value }
        if (ranks.zipWithNext().any { (a, b) -> b.value != a.value + 1 }) return null
        return HandPattern(PatternType.Plane, ranks.last(), cards.size, ranks.size, lastHandShort = true)
    }

    private fun dragPatternTieBreaker(type: PatternType): Int = when (type) {
        PatternType.Straight -> 7000
        PatternType.Plane -> 6500
        PatternType.ConsecutivePairs -> 6000
        PatternType.TripleWithPair -> 5000
        PatternType.Bomb -> 4000
        PatternType.TripleWithOne -> 3000
        PatternType.Pair -> 2000
        PatternType.Single -> 1000
        PatternType.Invalid -> 0
    }

    private fun dragPatternDescription(pattern: HandPattern): String = when {
        pattern.type == PatternType.TripleWithOne && pattern.cardCount == 3 -> "三张 ${pattern.mainRank.label}"
        pattern.type == PatternType.Plane && pattern.cardCount == pattern.groupCount * 3 -> "飞机主体 ${pattern.mainRank.label}"
        else -> PaoDeKuaiRules.patternDescription(pattern)
    }

    private fun currentPlayerLeads(): Boolean = lastPattern == null || lastMovePlayer == currentPlayer

    private fun makeAiContext(player: PlayerId): AiContext {
        val remaining = players.map { it.hand.size }
        val playerIndex = player.index()
        val next = nextCounterClockwise(player).index()
        val opponents = remaining.filterIndexed { index, _ -> index != playerIndex }
        return AiContext(
            leading = currentPlayerLeads(),
            previous = lastPattern ?: HandPattern(),
            ownRemainingCards = remaining[playerIndex],
            currentPlayerIndex = playerIndex,
            lastMovePlayerIndex = lastMovePlayer.index(),
            trickLeaderIndex = if (currentPlayerLeads()) currentPlayer.index() else trickLeader.index(),
            roundLeaderIndex = roundLeader.index(),
            currentTrickPassCount = passCount,
            nextPlayerRemainingCards = remaining[next],
            minOpponentRemainingCards = opponents.filter { it > 0 }.minOrNull() ?: 0,
            remainingCards = remaining,
            playedCards = playedCards,
            passObservations = passObservations.toList(),
            passHistory = passHistory.map { it.toList() },
        )
    }

    private fun hasPlayableFollow(player: PlayerId): Boolean {
        val pattern = lastPattern ?: return false
        return PaoDeKuaiRules.hasAnyFollowMove(players[player.index()].hand, pattern, players[player.index()].hand.size)
    }

    private fun startLocalAiTurn(player: PlayerId) {
        cancelLocalAi()
        val generation = ++localAiGeneration
        val turnNo = nextTurnNo
        val hand = players[player.index()].hand.toList()
        val context = makeAiContext(player)
        val kind = localAiKinds[player.index()]
        localAiPending = true
        localAiWorker = Thread {
            val choices = recommendLocalMoves(kind, hand, context, limit = 2)
            localAiResult.set(LocalAiResult(generation, player, turnNo, choices, context.leading))
        }.also { thread ->
            thread.isDaemon = true
            thread.name = "PdkLocalAi"
            thread.start()
        }
    }

    private fun applyLocalAiChoices(player: PlayerId, turnNo: Int, choices: List<AiMoveChoice>, leading: Boolean) {
        val legalChoiceCount = choices.count { !it.pass }
        val source = if (legalChoiceCount == 0 && !leading) TurnDecisionSource.System else TurnDecisionSource.LocalAi
        var reason = if (legalChoiceCount == 1) TurnDecisionReason.OnlyLegalMove else TurnDecisionReason.NormalChoice
        val choice = choices.firstOrNull() ?: AiMoveChoice(pass = true)
        if (choice.pass) reason = TurnDecisionReason.CannotBeat

        if (choice.pass) {
            if (pass(player)) {
                turnRecords += TurnRecord(turnNo, player, source, reason, GameAction("pass"), emptyList(), null, true)
                nextTurnNo++
            }
        } else {
            playCards(player, choice.cards, choice.pattern)
            turnRecords += TurnRecord(
                turnNo,
                player,
                source,
                reason,
                GameAction("play", choice.cards.map { it.rank.label }),
                choice.cards,
                choice.pattern,
                true,
            )
            nextTurnNo++
        }
    }

    private fun snapshot(): TurnSnapshot = TurnSnapshot(
        hands = players.map { it.hand.toList() },
        lastCards = lastCards,
        lastPattern = lastPattern,
        lastMovePlayer = lastMovePlayer,
        currentPlayer = currentPlayer,
        passCount = passCount,
    )

    private fun startExternalAiTurn() {
        externalAiPending = true
        externalAi?.start(
            ExternalAiRequest(
                turnNo = nextTurnNo,
                player = currentPlayer,
                humanName = players[0].name,
                snapshot = snapshot(),
                history = turnRecords.toList(),
            ),
        )
    }

    private fun applyExternalAiResult(result: ExternalAiResult): Boolean {
        val actor = currentPlayer
        val trace = TurnDecisionTrace(
            reasoningContent = result.reasoningContent,
            toolCallId = result.toolCallId,
            toolName = result.toolName,
            toolArgumentsJson = result.toolArgumentsJson,
            requestLogPath = result.requestLogPath,
            responseLogPath = result.responseLogPath,
            errorMessage = result.errorMessage,
        )
        if (!result.ok) return applyLlmFallback(actor, result.requestedAction, trace, result.errorMessage)
        if (result.requestedAction.action == "pass") {
            if (currentPlayerLeads() || hasPlayableFollow(actor)) return applyLlmFallback(actor, result.requestedAction, trace.copy(errorMessage = "LLM pass 不合法"), "LLM pass 不合法")
            val turnNo = nextTurnNo
            if (!pass(actor)) return false
            turnRecords += TurnRecord(turnNo, actor, TurnDecisionSource.LlmAi, TurnDecisionReason.CannotBeat, result.requestedAction, emptyList(), null, true, trace)
            nextTurnNo++
            return true
        }

        val hand = players[actor.index()].hand
        val used = BooleanArray(hand.size)
        val cards = mutableListOf<Card>()
        result.requestedAction.ranks.forEach { label ->
            val rank = rankFromLabel(label) ?: return applyLlmFallback(actor, result.requestedAction, trace.copy(errorMessage = "LLM 返回未知点数"), "LLM 返回未知点数")
            val index = hand.indices.firstOrNull { !used[it] && hand[it].rank == rank }
                ?: return applyLlmFallback(actor, result.requestedAction, trace.copy(errorMessage = "LLM 返回了手牌中不存在的点数"), "LLM 返回了手牌中不存在的点数")
            used[index] = true
            cards += hand[index]
        }
        val validation = if (currentPlayerLeads()) {
            PaoDeKuaiRules.validateLead(cards, hand.size)
        } else {
            PaoDeKuaiRules.validateFollow(cards, lastPattern ?: HandPattern(), hand.size)
        }
        if (!validation.ok) return applyLlmFallback(actor, result.requestedAction, trace.copy(errorMessage = validation.reason), validation.reason)

        val turnNo = nextTurnNo
        playCards(actor, cards, validation.pattern)
        if (result.requestedAction.talk.isNotBlank()) maybeTalk(actor, result.requestedAction.talk)
        turnRecords += TurnRecord(turnNo, actor, TurnDecisionSource.LlmAi, TurnDecisionReason.NormalChoice, result.requestedAction, cards, validation.pattern, true, trace)
        nextTurnNo++
        return true
    }

    private fun applyLlmFallback(actor: PlayerId, requested: GameAction, trace: TurnDecisionTrace, message: String): Boolean {
        val choice = chooseLocalMove(actor)
        val turnNo = nextTurnNo
        if (choice.pass) {
            if (!pass(actor)) return false
            turnRecords += TurnRecord(turnNo, actor, TurnDecisionSource.LlmAi, TurnDecisionReason.LlmFallback, requested, emptyList(), null, false, trace.copy(errorMessage = message))
        } else {
            playCards(actor, choice.cards, choice.pattern)
            turnRecords += TurnRecord(turnNo, actor, TurnDecisionSource.LlmAi, TurnDecisionReason.LlmFallback, requested, choice.cards, choice.pattern, false, trace.copy(errorMessage = message))
        }
        nextTurnNo++
        return true
    }

    private fun cancelLocalAi() {
        localAiGeneration++
        localAiPending = false
        localAiWorker?.interrupt()
        localAiWorker = null
        localAiResult.set(null)
    }

    private fun playCards(player: PlayerId, cards: List<Card>, pattern: HandPattern) {
        if (currentPlayerLeads()) trickLeader = player
        removeCardsFromHand(player, cards)
        players[player.index()].hasPlayedCards = true
        playedCards += cards
        lastCards = cards
        lastPattern = pattern
        lastMovePlayer = player
        passCount = 0
        toast = "${playerDisplayName(player, players[0].name)} 出了 ${PaoDeKuaiRules.patternDescription(pattern)}"
        events += GameEvent(GameEventType.CardsPlayed, player, toast, cards)
        if (pattern.type == PatternType.Bomb) {
            bombs += BombScoreEvent(player, 20)
            events += GameEvent(GameEventType.Bomb, player, "炸弹 +20", cards)
        }
        if (player != PlayerId.Player) maybeTalk(player, if (pattern.type == PatternType.Bomb) "炸一下，醒醒神！" else "轮到我了，看我走一手。")
        if (players[player.index()].hand.isEmpty()) {
            finishRound(player)
        } else {
            advanceTurn()
        }
    }

    private fun pass(player: PlayerId): Boolean {
        if (roundOver || currentPlayerLeads()) {
            toast = "当前需要主动出牌，不能不要"
            events += GameEvent(GameEventType.InvalidMove, player, toast)
            return false
        }
        if (hasPlayableFollow(player)) {
            toast = "要得起必须出"
            events += GameEvent(GameEventType.InvalidMove, player, toast)
            return false
        }
        passCount++
        lastPattern?.let { recordPassObservation(player, it) }
        toast = "${playerDisplayName(player, players[0].name)} 不要"
        events += GameEvent(GameEventType.Passed, player, toast)
        if (player != PlayerId.Player) maybeTalk(player, "先不要，你们继续。")
        if (passCount >= 2) {
            currentPlayer = lastMovePlayer
            lastPattern = null
            lastCards = emptyList()
            trickLeader = currentPlayer
            passCount = 0
            toast = "${playerDisplayName(currentPlayer, players[0].name)} 重新领出"
            return true
        }
        advanceTurn()
        return true
    }

    private fun finishRound(winner: PlayerId) {
        roundOver = true
        val remaining = players.map { it.hand.size }
        val score = calculateRoundScore(
            RoundScoreInput(
                winner = winner,
                remainingCards = remaining,
                hasPlayedCards = players.map { it.hasPlayedCards },
                bombs = bombs,
            ),
        )
        lastRoundRecord = RoundRecord(winner, score.scores, remaining, score.spring.enabled, bombs.size, score.spring.losers.size)
        nextRoundLeader = winner
        toast = "${if (winner == PlayerId.Player) "胜利" else "失败"}  本局分: 玩家 ${score.scores[0]} AI1 ${score.scores[1]} AI2 ${score.scores[2]}"
        events += GameEvent(GameEventType.RoundEnded, winner, toast)
    }

    private fun removeCardsFromHand(player: PlayerId, cards: List<Card>) {
        val hand = players[player.index()].hand
        cards.forEach { card -> hand.remove(card) }
    }

    private fun chooseLocalMove(player: PlayerId): AiMoveChoice =
        when (localAiKinds[player.index()]) {
            LocalAiKind.Basic -> basicAi.chooseMove(players[player.index()].hand, makeAiContext(player))
            LocalAiKind.Strong -> strongAi.chooseMove(players[player.index()].hand, makeAiContext(player))
        }

    private fun recommendLocalMoves(kind: LocalAiKind, hand: List<Card>, context: AiContext, limit: Int): List<AiMoveChoice> =
        when (kind) {
            LocalAiKind.Basic -> BasicAiStrategy().recommendMoves(hand, context, limit)
            LocalAiKind.Strong -> StrongAiStrategy().recommendMoves(hand, context, limit)
        }

    private fun recordPassObservation(player: PlayerId, pattern: HandPattern) {
        val index = player.index()
        val observation = PassObservation(pattern, players[index].hand.size)
        passHistory[index] += observation
        val existing = passObservations[index]
        if (existing != null && existing.pattern.type == PatternType.Single && pattern.type == PatternType.Single) {
            if (pattern.mainRank.value < existing.pattern.mainRank.value) passObservations[index] = observation
            return
        }
        if (existing != null && existing.pattern.type == PatternType.Single && pattern.type != PatternType.Single) return
        passObservations[index] = observation
    }

    private fun advanceTurn() {
        currentPlayer = nextCounterClockwise(currentPlayer)
        if (currentPlayer == PlayerId.Player) events += GameEvent(GameEventType.Talk, PlayerId.Player, "轮到你")
    }

    private fun maybeTalk(player: PlayerId, text: String) {
        if (talkCooldown > 0f && random.nextBoolean()) return
        talkPlayer = player
        talkText = text
        talkCooldown = 5f
        events += GameEvent(GameEventType.Talk, player, text)
    }
}
