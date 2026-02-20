package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class Flush : PokerHandEvaluator() {
    override val ranking = HandRanking.FLUSH

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val flushPool = cardPool.groupBySuit().firstGroupWithMinSizeOrNull(5) ?: return null
        return PokerHand(ranking, flushPool.sortedDescending().take(5))
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return compareHandsByRankOnly(o1.cards, o2.cards)
    }
}