package com.example.pdk.core

enum class Suit(val symbol: String) {
    Spades("S"),
    Hearts("H"),
    Diamonds("D"),
    Clubs("C"),
}

enum class Rank(val value: Int, val label: String) {
    Three(3, "3"),
    Four(4, "4"),
    Five(5, "5"),
    Six(6, "6"),
    Seven(7, "7"),
    Eight(8, "8"),
    Nine(9, "9"),
    Ten(10, "10"),
    Jack(11, "J"),
    Queen(12, "Q"),
    King(13, "K"),
    Ace(14, "A"),
    Two(15, "2"),
}

data class Card(val rank: Rank, val suit: Suit) {
    val id: String get() = rank.label + suit.symbol
}

typealias Cards = List<Card>

fun Card.sortValue(): Int = rank.value * 10 + suit.ordinal
fun Card.isSpadeThree(): Boolean = rank == Rank.Three && suit == Suit.Spades
fun Cards.sortedByGameOrder(): List<Card> = sortedBy { it.sortValue() }
fun Cards.toCardText(): String = joinToString(" ") { it.id }

fun rankFromLabel(label: String): Rank? = Rank.entries.firstOrNull { it.label == label }

enum class PlayerId {
    Player,
    Ai1,
    Ai2,
}

fun PlayerId.index(): Int = ordinal
fun playerFromIndex(index: Int): PlayerId = PlayerId.entries[Math.floorMod(index, 3)]
fun nextCounterClockwise(player: PlayerId): PlayerId = playerFromIndex(player.index() + 2)

fun playerDisplayName(player: PlayerId, humanName: String = "李姐"): String = when (player) {
    PlayerId.Player -> humanName.ifBlank { "李姐" }
    PlayerId.Ai1 -> "AI1"
    PlayerId.Ai2 -> "AI2"
}
