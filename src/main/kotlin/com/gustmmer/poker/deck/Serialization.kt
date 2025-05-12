package com.gustmmer.poker.deck

private val cardAsStrRegex = Regex("""([2-9AKQJ]|10)-?([HDCS♥♦♣♠])""")
private val cardSeparationRegex = Regex("""[\s,]+""")

fun String.toCards(): List<Card> = split(cardSeparationRegex).map { token ->
    val match = token.trim().let { cardAsStrRegex.matchEntire(it) }
        ?: throw IllegalArgumentException("Invalid card format: ${token.trim()}")

    val (rankStr, suitStr) = match.destructured
    val rank = Rank.fromSymbol(rankStr)
    val suit = Suit.fromSymbol(suitStr.first())

    Card.card(suit, rank)
}