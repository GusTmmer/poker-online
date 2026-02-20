package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class ThreeOfAKind : PokerHandEvaluator() {
    override val ranking = HandRanking.THREE_OF_A_KIND

    private val comparator = compareBy<List<Card>> { it.first() }.thenBy(::compareHandsByRankOnly) { it.drop(3) }

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        // If there are two triples in the pool, it's a full-house
        // Therefore, only need to concern oneself with a single triple
        val triple = cardPool.groupByRank().firstGroupWithSizeOrNull(3) ?: return null

        val remainderCardPool = cardPool.toSet() - triple.toSet()

        return PokerHand(ranking, triple + remainderCardPool.sortedDescending().take(2))
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return comparator.compare(o1.cards, o2.cards)
    }
}