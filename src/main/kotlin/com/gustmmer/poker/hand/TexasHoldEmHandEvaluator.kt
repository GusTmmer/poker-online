package com.gustmmer.poker.hand

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.plus
import com.gustmmer.poker.hand.rankings.HandRanking
import com.gustmmer.poker.hand.rankings.PokerHand
import com.gustmmer.poker.hand.rankings.pokerHandEvaluators

object TexasHoldEmHandEvaluator {
    fun getMatchingPokerHand(communityCards: List<Card>, pocketCards: List<Card>): PokerHand {
        assert(pocketCards.size == 2)

        val cardPool = pocketCards + communityCards

        return HandRanking.descendingOrder.firstNotNullOf {
            pokerHandEvaluators[it]!!.highestMatchingPlayerHandOrNull(cardPool)
        }
    }
}
