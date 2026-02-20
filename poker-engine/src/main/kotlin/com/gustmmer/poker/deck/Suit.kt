package com.gustmmer.poker.deck

enum class Suit(val letter: Char, val symbol: Char) {
    HEARTS('H', '♥'),
    DIAMONDS('D', '♦'),
    CLUBS('C', '♣'),
    SPADES('S', '♠');

    companion object {
        private val symbolMap: Map<Char, Suit> = buildMap {
            for (suit in Suit.entries) {
                put(suit.letter, suit)
                put(suit.symbol, suit)
            }
        }

        fun fromSymbol(symbol: Char): Suit =
            symbolMap[symbol] ?: throw IllegalArgumentException("Invalid suit symbol: $symbol")
    }

    override fun toString(): String {
        return letter.toString()
    }
}