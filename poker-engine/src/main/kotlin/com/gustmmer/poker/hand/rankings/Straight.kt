package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Rank

class Straight : PokerHandEvaluator() {
    override val ranking = HandRanking.STRAIGHT

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val sorted = cardPool.sortedDescending()

        val cardsToEvaluate = if (sorted.first().rank == Rank.ACE)
            sorted + sorted.first()
        else
            sorted

        return getLongestSequence(cardsToEvaluate).take(5).takeIf { it.size == 5 }?.let { PokerHand(ranking, it) }
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return o1.cards.first().compareTo(o2.cards.first())
    }
}