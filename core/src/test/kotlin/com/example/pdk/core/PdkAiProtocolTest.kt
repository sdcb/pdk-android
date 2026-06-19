package com.example.pdk.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdkAiProtocolTest {
    @Test
    fun parsesReasoningAndPlayCardsToolCall() {
        val response = """
            {
              "id": "chatcmpl_top_level",
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "reasoning_content": "对子比单张更稳。",
                    "tool_calls": [
                      {
                        "id": "call_42",
                        "type": "function",
                        "function": {
                          "name": "play_cards",
                          "arguments": "{\"ranks\":[\"5\",\"5\"],\"talk\":\"走一手\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val parsed = PdkAiProtocol.parseResponse(response)

        assertTrue(parsed.ok)
        assertEquals("call_42", parsed.toolCallId)
        assertEquals("play_cards", parsed.toolName)
        assertEquals(GameAction("play", listOf("5", "5"), "走一手"), parsed.action)
        assertEquals("对子比单张更稳。", parsed.reasoningContent)
    }

    @Test
    fun buildsToolResultJsonForRejectedMove() {
        val json = PdkAiProtocol.buildToolResultJson(GameAction("play", listOf("A"), "试一下"), false, "要得起必须出")

        assertEquals("{\"accepted\":false,\"action\":\"play\",\"ranks\":[\"A\"],\"reason\":\"要得起必须出\"}", json)
    }
}
