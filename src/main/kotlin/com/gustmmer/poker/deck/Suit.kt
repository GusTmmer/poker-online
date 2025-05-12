package com.gustmmer.poker.deck

enum class Suit(val symbols: Set<Char>) {
    HEARTS(setOf('H', '♥')),
    DIAMONDS(setOf('D', '♦')),
    CLUBS(setOf('C', '♣')),
    SPADES(setOf('S', '♠'));

    companion object {
        private val symbolMap: Map<Char, Suit> = buildMap {
            for (suit in Suit.entries) {
                for (symbol in suit.symbols) {
                    put(symbol, suit)
                }
            }
        }

        fun fromSymbol(symbol: Char): Suit =
            symbolMap[symbol] ?: throw IllegalArgumentException("Invalid suit symbol: $symbol")
    }

    override fun toString(): String {
        return symbols.first { !it.isLetter() }.toString()
    }
}