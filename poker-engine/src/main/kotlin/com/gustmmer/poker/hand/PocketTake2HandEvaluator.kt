package com.gustmmer.poker.hand

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.plus
import com.gustmmer.poker.hand.rankings.HandRanking
import com.gustmmer.poker.hand.rankings.PokerHand
import com.gustmmer.poker.hand.rankings.pokerHandEvaluators

object PocketTake2HandEvaluator {
    fun getMatchingPokerHand(communityCards: List<Card>, pocketCards: List<Card>): PokerHand {
        val pocketCardCombinations = buildList {
            for (i in pocketCards.indices) {
                for (j in i + 1 until pocketCards.size) {
                    add(pocketCards[i] + pocketCards[j])
                }
            }
        }

        return pocketCardCombinations
            .map { it + communityCards }
            .maxOf { cardPool ->
                HandRanking.descendingOrder.firstNotNullOf {
                    pokerHandEvaluators[it]!!.highestMatchingPlayerHandOrNull(cardPool)
                }
            }
    }
}
