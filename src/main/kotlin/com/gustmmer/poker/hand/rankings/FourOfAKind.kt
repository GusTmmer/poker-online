package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class FourOfAKind : PokerHandEvaluator() {
    override val ranking = HandRanking.FOUR_OF_A_KIND

    private val comparator = compareBy<List<Card>> { it.first() }.thenBy { it.last() }

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val fourOfAKind = cardPool.groupByRank().firstGroupWithSizeOrNull(4) ?: return null

        val kickerPool = (cardPool.toSet() - fourOfAKind.toSet())

        return PokerHand(ranking, fourOfAKind + kickerPool.max())
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return comparator.compare(o1.cards, o2.cards)
    }
}