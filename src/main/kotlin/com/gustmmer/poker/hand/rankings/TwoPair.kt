package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class TwoPair : PokerHandEvaluator() {
    override val ranking = HandRanking.TWO_PAIR

    private val comparator = compareBy<List<Card>> { it.first() }.thenBy { it[2] }.thenBy { it.last() }

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val pairs = cardPool.groupByRank().filterValues { it.size == 2 }.takeIf { it.size >= 2 }
            ?: return null

        val sortedPairs = pairs.toSortedMap(reverseOrder())
        val twoPairs = sortedPairs.sequencedValues().take(2).flatten()

        val remainingCardPool = cardPool.toSet() - twoPairs.toSet()

        return PokerHand(ranking, twoPairs + remainingCardPool.max())
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return comparator.compare(o1.cards, o2.cards)
    }
}