package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GameStateExternalAiTest {
    @Test
    fun externalAiCanChooseAmongMultipleLegalMoves() {
        val previous = PaoDeKuaiRules.identifyPattern(listOf(c(Rank.Four))).pattern
        val controller = ImmediateExternalAiController(
            ExternalAiResult(
                ok = true,
                requestedAction = GameAction("play", listOf("6"), "压一张"),
                reasoningContent = "6 比 5 更能保留小牌。",
                toolCallId = "call_1",
                toolName = "play_cards",
                toolArgumentsJson = "{\"ranks\":[\"6\"],\"talk\":\"压一张\"}",
            ),
        )
        val state = GameState()
        state.testSetRound(
            listOf(
                listOf(c(Rank.Three)),
                listOf(c(Rank.Five), c(Rank.Six)),
                listOf(c(Rank.Seven)),
            ),
            currentPlayer = PlayerId.Ai1,
            previousPattern = previous,
            lastMovePlayer = PlayerId.Player,
        )
        state.setExternalAiController(controller)

        state.advanceUntil { controller.hasPending() }
        state.advanceUntil { turnRecords.isNotEmpty() }

        assertEquals(listOf(Rank.Six), state.lastCards.map { it.rank })
        assertEquals(TurnDecisionSource.LlmAi, state.turnRecords.single().source)
        assertEquals("call_1", state.turnRecords.single().trace.toolCallId)
        assertFalse(controller.hasPending())
    }

    private class ImmediateExternalAiController(
        private val response: ExternalAiResult,
    ) : ExternalAiController {
        private var pending = false
        private var delivered = false

        override fun canHandle(player: PlayerId): Boolean = player == PlayerId.Ai1

        override fun hasPending(): Boolean = pending

        override fun start(request: ExternalAiRequest) {
            pending = true
            delivered = false
        }

        override fun tryGetResult(): ExternalAiResult? {
            if (!pending || delivered) return null
            delivered = true
            pending = false
            return response
        }

        override fun cancel() {
            pending = false
            delivered = true
        }
    }
}
