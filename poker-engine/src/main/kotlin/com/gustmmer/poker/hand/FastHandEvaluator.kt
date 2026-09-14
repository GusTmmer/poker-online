package com.gustmmer.poker.hand

import com.gustmmer.poker.deck.Card

/**
 * Allocation-free best-of-N hand scorer for the bots' Monte Carlo loops. Orders hands exactly like
 * [com.gustmmer.poker.hand.TexasHoldEmHandEvaluator] (cross-checked in tests) but returns a plain Int:
 * a higher score is a better hand, equal scores tie.
 *
 * Cards are encoded as `rank.ordinal * 4 + suit.ordinal` (see [code]).
 */
internal object FastHandEvaluator {

    private const val HIGH_CARD = 0
    private const val ONE_PAIR = 1
    private const val TWO_PAIR = 2
    private const val TRIPS = 3
    private const val STRAIGHT = 4
    private const val FLUSH = 5
    private const val FULL_HOUSE = 6
    private const val QUADS = 7
    private const val STRAIGHT_FLUSH = 8

    fun code(card: Card): Int = card.rank.ordinal * 4 + card.suit.ordinal

    /** Scores the best hand among `cards[0 until count]`. */
    fun score(cards: IntArray, count: Int): Int {
        val rankCounts = IntArray(13)
        val suitCounts = IntArray(4)
        val suitMasks = IntArray(4)
        var rankMask = 0
        for (i in 0 until count) {
            val rank = cards[i] shr 2
            val suit = cards[i] and 3
            rankCounts[rank]++
            suitCounts[suit]++
            suitMasks[suit] = suitMasks[suit] or (1 shl rank)
            rankMask = rankMask or (1 shl rank)
        }

        val flushSuit = (0 until 4).firstOrNull { suitCounts[it] >= 5 }
        if (flushSuit != null) {
            val high = straightHigh(suitMasks[flushSuit])
            if (high >= 0) return make(STRAIGHT_FLUSH, high)
        }

        var quad = -1
        var trip1 = -1
        var trip2 = -1
        var pair1 = -1
        var pair2 = -1
        for (rank in 12 downTo 0) {
            when (rankCounts[rank]) {
                4 -> if (quad < 0) quad = rank
                3 -> if (trip1 < 0) trip1 = rank else if (trip2 < 0) trip2 = rank
                2 -> if (pair1 < 0) pair1 = rank else if (pair2 < 0) pair2 = rank
            }
        }

        if (quad >= 0) return make(QUADS, (quad shl 4) or topRanks(rankMask and (1 shl quad).inv(), 1))
        if (trip1 >= 0 && (trip2 >= 0 || pair1 >= 0)) return make(FULL_HOUSE, (trip1 shl 4) or maxOf(trip2, pair1))
        if (flushSuit != null) return make(FLUSH, topRanks(suitMasks[flushSuit], 5))
        val straight = straightHigh(rankMask)
        if (straight >= 0) return make(STRAIGHT, straight)
        if (trip1 >= 0) return make(TRIPS, (trip1 shl 8) or topRanks(rankMask and (1 shl trip1).inv(), 2))
        if (pair2 >= 0) {
            val kickers = rankMask and (1 shl pair1).inv() and (1 shl pair2).inv()
            return make(TWO_PAIR, (pair1 shl 8) or (pair2 shl 4) or topRanks(kickers, 1))
        }
        if (pair1 >= 0) return make(ONE_PAIR, (pair1 shl 12) or topRanks(rankMask and (1 shl pair1).inv(), 3))
        return make(HIGH_CARD, topRanks(rankMask, 5))
    }

    /** Highest straight's top rank in [mask] (the wheel A-2-3-4-5 counts as 5-high), or -1. */
    private fun straightHigh(mask: Int): Int {
        for (high in 12 downTo 4) {
            val run = 0b11111 shl (high - 4)
            if (mask and run == run) return high
        }
        val wheel = 0b1111 or (1 shl 12)
        return if (mask and wheel == wheel) 3 else -1
    }

    /** Packs the [n] highest ranks of [mask], most significant first, 4 bits each. */
    private fun topRanks(mask: Int, n: Int): Int {
        var packed = 0
        var taken = 0
        var rank = 12
        while (rank >= 0 && taken < n) {
            if (mask and (1 shl rank) != 0) {
                packed = (packed shl 4) or rank
                taken++
            }
            rank--
        }
        return packed shl (4 * (n - taken))
    }

    /** [packed] holds the category's deciding ranks, most significant first (at most 20 bits). */
    private fun make(category: Int, packed: Int) = (category shl 20) or packed
}
