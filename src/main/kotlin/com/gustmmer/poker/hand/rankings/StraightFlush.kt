package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Rank

class StraightFlush : PokerHandEvaluator() {
    override val ranking = HandRanking.STRAIGHT_FLUSH

    override fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand? {
        val potentialCards = cardPool.groupBySuit().firstGroupWithMinSizeOrNull(5)?.sortedDescending()
            ?: return null

        val cardToEvaluate = if (potentialCards.first().rank == Rank.ACE)
            potentialCards + potentialCards.first()
        else
            potentialCards

        val longestSeq = getLongestSequence(cardToEvaluate)

        return longestSeq.take(5).takeIf { it.size == 5 }?.let { PokerHand(ranking, it) }
    }

    override fun compare(o1: PokerHand, o2: PokerHand): Int {
        assert(o1.ranking == o2.ranking)
        return o1.cards.first().compareTo(o2.cards.first())
    }
}