package com.gustmmer.poker.deck

enum class Rank(private val symbol: String) {
    TWO("2"),
    THREE("3"),
    FOUR("4"),
    FIVE("5"),
    SIX("6"),
    SEVEN("7"),
    EIGHT("8"),
    NINE("9"),
    TEN("10"),
    JACK("J"),
    QUEEN("Q"),
    KING("K"),
    ACE("A");

    override fun toString(): String {
        return this.symbol
    }

    companion object {
        private val symbolMap: Map<String, Rank> = buildMap {
            for (rank in Rank.entries) {
                put(rank.symbol, rank)
            }
        }

        fun fromSymbol(symbol: String): Rank =
            symbolMap[symbol] ?: throw IllegalArgumentException("Invalid rank symbol: $symbol")
    }
}