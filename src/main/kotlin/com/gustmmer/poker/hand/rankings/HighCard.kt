package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card

class HighCard : PokerHandEvaluator() {
    override val ranking = HandRanking.HIGH_CARD

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand {
        return PokerHand(ranking, cardPool.sortedDescending().take(5))
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return compareHandsByRankOnly(o1.cards, o2.cards)
    }
}