package com.example.pdk.core

data class TurnSnapshot(
    val hands: List<List<Card>>,
    val lastCards: List<Card>,
    val lastPattern: HandPattern?,
    val lastMovePlayer: PlayerId,
    val currentPlayer: PlayerId,
    val passCount: Int,
)

data class TurnDecisionTrace(
    val reasoningContent: String = "",
    val toolCallId: String = "",
    val toolName: String = "",
    val toolArgumentsJson: String = "",
    val toolResultJson: String = "",
    val requestLogPath: String = "",
    val responseLogPath: String = "",
    val errorMessage: String = "",
)

data class ExternalAiRequest(
    val turnNo: Int,
    val player: PlayerId,
    val humanName: String,
    val snapshot: TurnSnapshot,
    val history: List<TurnRecord>,
)

data class ExternalAiResult(
    val ok: Boolean = false,
    val requestedAction: GameAction = GameAction("pass"),
    val reasoningContent: String = "",
    val toolCallId: String = "",
    val toolName: String = "",
    val toolArgumentsJson: String = "",
    val requestLogPath: String = "",
    val responseLogPath: String = "",
    val errorMessage: String = "",
)

interface ExternalAiController {
    fun canHandle(player: PlayerId): Boolean
    fun hasPending(): Boolean
    fun start(request: ExternalAiRequest)
    fun tryGetResult(): ExternalAiResult?
    fun cancel()
}

data class AiProviderSettings(
    val type: String = "openai",
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "",
)

data class PdkAiMessage(
    val role: String,
    val content: String = "",
    val reasoningContent: String = "",
    val toolCallId: String = "",
    val name: String = "",
    val toolCalls: List<PdkAiToolCall> = emptyList(),
)

data class PdkAiToolCall(
    val id: String,
    val name: String = "play_cards",
    val argumentsJson: String,
)

data class PdkAiResponse(
    val ok: Boolean,
    val action: GameAction = GameAction("pass"),
    val reasoningContent: String = "",
    val toolCallId: String = "",
    val toolName: String = "",
    val toolArgumentsJson: String = "",
    val rawResponse: String = "",
    val errorMessage: String = "",
)

object PdkAiProtocol {
    fun buildMessages(request: ExternalAiRequest): List<PdkAiMessage> = listOf(
        PdkAiMessage("system", systemPrompt()),
        PdkAiMessage("user", currentPrompt(request)),
    )

    fun buildRequestJson(provider: AiProviderSettings, messages: List<PdkAiMessage>): String = buildString {
        append('{')
        append("\"model\":").append(jsonString(provider.model)).append(',')
        append("\"stream\":false,")
        append("\"temperature\":0,")
        append("\"messages\":[")
        messages.forEachIndexed { index, message ->
            if (index > 0) append(',')
            append('{')
            append("\"role\":").append(jsonString(message.role)).append(',')
            append("\"content\":").append(jsonString(message.content))
            append('}')
        }
        append("],")
        append("\"tools\":[")
        append(playCardsToolJson())
        append(',')
        append(recordForcedMoveToolJson())
        append("],")
        append("\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"play_cards\"}}")
        append('}')
    }

    fun parseResponse(body: String): PdkAiResponse {
        val reasoning = findJsonString(body, "reasoning_content")
        if (reasoning.isBlank()) {
            return PdkAiResponse(false, rawResponse = body, errorMessage = "AI response has no reasoning_content")
        }
        val toolCall = Regex(
            "\"tool_calls\"\\s*:\\s*\\[\\s*\\{.*?\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\".*?\"function\"\\s*:\\s*\\{.*?\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\".*?\"arguments\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            RegexOption.DOT_MATCHES_ALL,
        ).find(body)
        val toolId = toolCall?.groupValues?.get(1)?.let(::decodeJsonString).orEmpty()
        val toolName = toolCall?.groupValues?.get(2)?.let(::decodeJsonString).orEmpty()
        val rawArguments = toolCall?.groupValues?.get(3)?.let(::decodeJsonString).orEmpty()
        if (toolName != "play_cards" || rawArguments.isBlank()) {
            return PdkAiResponse(false, reasoningContent = reasoning, rawResponse = body, errorMessage = "AI response has no play_cards tool call")
        }
        val arguments = rawArguments
        val ranks = Regex("\"ranks\"\\s*:\\s*\\[(.*?)]").find(arguments)
            ?.groupValues?.get(1)
            ?.let { inner -> Regex("\"(.*?)\"").findAll(inner).map { it.groupValues[1] }.toList() }
            ?: emptyList()
        val talk = findJsonString(arguments, "talk")
        return PdkAiResponse(
            ok = true,
            action = GameAction("play", ranks, talk),
            reasoningContent = reasoning,
            toolCallId = toolId,
            toolName = toolName,
            toolArgumentsJson = arguments,
            rawResponse = body,
        )
    }

    fun buildToolResultJson(move: GameAction, accepted: Boolean, reason: String): String =
        "{\"accepted\":$accepted,\"action\":${jsonString(move.action)},\"ranks\":[${move.ranks.joinToString(",") { jsonString(it) }}],\"reason\":${jsonString(reason)}}"

    private fun systemPrompt(): String =
        "你正在扮演三人跑得快中的一名 AI 玩家。固定规则：黑桃3先出；要得起必须出；牌型有单张、对子、顺子、连对、三带二、飞机、炸弹；炸弹可压非炸弹。真实决策只调用 play_cards。"

    private fun currentPrompt(request: ExternalAiRequest): String {
        val snapshot = request.snapshot
        val self = request.player.index()
        val otherAi = if (request.player == PlayerId.Ai1) PlayerId.Ai2 else PlayerId.Ai1
        val previous = snapshot.lastPattern
        return buildString {
            append("现在轮到你决策。\n")
            append("你的手牌: ").append(snapshot.hands[self].joinToString(" ") { it.rank.label }).append('\n')
            append("剩余张数: ")
            append(request.humanName.ifBlank { "玩家" }).append('=').append(snapshot.hands[0].size)
            append(", 你=").append(snapshot.hands[self].size)
            append(", 另一名 AI=").append(snapshot.hands[otherAi.index()].size).append('\n')
            if (previous != null) {
                append("当前要压的牌: ")
                append(playerDisplayName(snapshot.lastMovePlayer, request.humanName))
                append(' ')
                append(snapshot.lastCards.joinToString(" ") { it.rank.label })
                append("，牌型 ")
                append(PaoDeKuaiRules.patternDescription(previous))
                append('\n')
            } else {
                append("当前是领出，可以主动选择任意合法牌型。\n")
            }
            val context = AiContext(
                leading = previous == null,
                previous = previous ?: HandPattern(),
                ownRemainingCards = snapshot.hands[self].size,
                currentPlayerIndex = self,
                nextPlayerRemainingCards = snapshot.hands[nextCounterClockwise(request.player).index()].size,
                minOpponentRemainingCards = snapshot.hands.mapIndexedNotNull { index, cards -> if (index == self) null else cards.size }.minOrNull() ?: 0,
                remainingCards = snapshot.hands.map { it.size },
                playedCards = request.history.flatMap { it.finalCards },
            )
            val recommendations = BasicAiStrategy().recommendMoves(snapshot.hands[self], context, 3)
            append("本地决策树 AI 的前 3 个建议（仅供参考）：\n")
            recommendations.forEachIndexed { index, choice ->
                append("- ").append(index + 1).append(". ")
                if (choice.pass) {
                    append("不要")
                } else {
                    append("出 ").append(choice.cards.joinToString(" ") { it.rank.label })
                    append("，牌型 ").append(PaoDeKuaiRules.patternDescription(choice.pattern))
                }
                append('\n')
            }
        }
    }

    private fun playCardsToolJson(): String =
        "{\"type\":\"function\",\"function\":{\"name\":\"play_cards\",\"description\":\"当你有多个合法选择时，选择本次要出的牌。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"ranks\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"3\",\"4\",\"5\",\"6\",\"7\",\"8\",\"9\",\"10\",\"J\",\"Q\",\"K\",\"A\",\"2\"]}},\"talk\":{\"type\":\"string\",\"maxLength\":24}},\"required\":[\"ranks\"]}}}"

    private fun recordForcedMoveToolJson(): String =
        "{\"type\":\"function\",\"function\":{\"name\":\"record_forced_move\",\"description\":\"记录规则强制动作。\",\"parameters\":{\"type\":\"object\",\"properties\":{\"reason\":{\"type\":\"string\",\"enum\":[\"cannot_beat\",\"only_legal_move\"]},\"ranks\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},\"required\":[\"reason\",\"ranks\"]}}}"

    private fun jsonString(value: String): String = "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n") + "\""

    private fun findJsonString(text: String, key: String): String {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(text) ?: return ""
        return decodeJsonString(match.groupValues[1])
    }

    private fun decodeJsonString(value: String): String =
        value
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
}
