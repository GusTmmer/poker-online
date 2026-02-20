package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class FullHouse : PokerHandEvaluator() {
    override val ranking = HandRanking.FULL_HOUSE

    // Compare triple then pair(after triple)
    private val comparator = compareBy<List<Card>> { it.first() }.thenBy { it[3] }

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val byRank = cardPool.groupByRank()

        val triples = byRank.filterValues { it.size == 3 }.takeIf { it.isNotEmpty() } ?: return null
        if (triples.size == 2) {
            return PokerHand(ranking, triples.values.flatten().sortedDescending().take(5))
        }

        val pairs = byRank.filterValues { it.size == 2 }.takeIf { it.isNotEmpty() } ?: return null
        val highestPair = pairs[pairs.keys.max()]!!

        return PokerHand(ranking, triples.values.first() + highestPair)
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return comparator.compare(o1.cards, o2.cards)
    }
}