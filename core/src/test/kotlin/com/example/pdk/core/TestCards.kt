package com.example.pdk.core

fun c(rank: Rank, suit: Suit = Suit.Spades): Card = Card(rank, suit)
fun countRank(cards: List<Card>, rank: Rank): Int = cards.count { it.rank == rank }

fun leadContext(handSize: Int, nextRemaining: Int = 10, minOpponentRemaining: Int = 10): AiContext =
    AiContext(
        leading = true,
        ownRemainingCards = handSize,
        currentPlayerIndex = 1,
        nextPlayerRemainingCards = nextRemaining,
        minOpponentRemainingCards = minOpponentRemaining,
        remainingCards = listOf(10, handSize, nextRemaining),
    )

fun followContext(previous: HandPattern, handSize: Int): AiContext =
    AiContext(
        leading = false,
        previous = previous,
        ownRemainingCards = handSize,
        currentPlayerIndex = 1,
        nextPlayerRemainingCards = 10,
        minOpponentRemainingCards = 10,
        remainingCards = listOf(10, handSize, 10),
    )

fun GameState.advanceUntil(
    maxFrames: Int = 500,
    dtSeconds: Float = 1f,
    clearEventsEachFrame: Boolean = false,
    condition: GameState.() -> Boolean,
) {
    repeat(maxFrames) {
        if (condition()) return
        update(dtSeconds)
        if (clearEventsEachFrame) clearEvents()
        Thread.sleep(1)
    }
    if (!condition()) error("condition was not reached within $maxFrames frames")
}
