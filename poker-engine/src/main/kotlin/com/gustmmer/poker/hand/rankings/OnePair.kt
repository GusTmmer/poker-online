package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class OnePair : PokerHandEvaluator() {
    override val ranking = HandRanking.ONE_PAIR

    private val comparator = compareBy<List<Card>> { it.first() }.thenBy(::compareHandsByRankOnly) { it.drop(2) }

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val pair = cardPool.groupByRank().firstGroupWithSizeOrNull(2) ?: return null

        val remainingCardPool = cardPool.toSet() - pair.toSet()

        return PokerHand(ranking, pair + remainingCardPool.sortedDescending().take(3))
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return comparator.compare(o1.cards, o2.cards)
    }
}