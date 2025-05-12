package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Rank
import com.gustmmer.poker.deck.Suit
import com.gustmmer.poker.deck.isRankImmediatelyAfterThan
import java.util.EnumMap

enum class HandRanking {
    HIGH_CARD,
    ONE_PAIR,
    TWO_PAIR,
    THREE_OF_A_KIND,
    STRAIGHT,
    FLUSH,
    FULL_HOUSE,
    FOUR_OF_A_KIND,
    STRAIGHT_FLUSH,
    ;

    companion object {
        val descendingOrder = HandRanking.entries.sortedDescending()
    }
}

val pokerHandEvaluators = PokerHandEvaluator::class.sealedSubclasses.map { it.constructors.first().call() }
    .associateByTo(EnumMap(HandRanking::class.java)) { it.ranking }

class PokerHand(val ranking: HandRanking, val cards: List<Card>) : Comparable<PokerHand> {
    override fun compareTo(other: PokerHand): Int {
        return compareBy<PokerHand> { it.ranking }
            .thenComparing(pokerHandEvaluators[ranking])
            .compare(this, other)
    }

    override fun toString(): String {
        return "$ranking: $cards"
    }
}

sealed class PokerHandEvaluator : Comparator<PokerHand> {
    abstract val ranking: HandRanking

    abstract fun highestMatchingPlayerHandOrNull(cardPool: List<Card>): PokerHand?
}

/**
 * Sequence must be sorted from higher rank to lower rank
 */
fun getLongestSequence(cardSequence: List<Card>): List<Card> {
    var longestSeq = listOf<Card>()
    var currentSeq = mutableListOf<Card>()

    for (card in cardSequence) {
        if (currentSeq.isEmpty() || currentSeq.last().isRankImmediatelyAfterThan(card)) {
            currentSeq.add(card)
            continue
        }

        if (currentSeq.last().rank == card.rank) {
            continue
        }

        if (currentSeq.size > longestSeq.size) {
            longestSeq = currentSeq
        }

        currentSeq = mutableListOf(card)
    }

    if (currentSeq.size > longestSeq.size) {
        longestSeq = currentSeq
    }

    return longestSeq
}

fun compareHandsByRankOnly(hand1: List<Card>, hand2: List<Card>): Int {
    return hand1.zip(hand2).firstNotNullOfOrNull { (hand1Card, hand2Card) ->
        hand1Card.compareTo(hand2Card).takeIf { it != 0 }
    } ?: 0
}

fun List<Card>.groupByRank() = groupByTo(EnumMap(Rank::class.java)) { it.rank }
fun List<Card>.groupBySuit() = groupByTo(EnumMap(Suit::class.java)) { it.suit }

fun <T : Enum<T>> Map<T, List<Card>>.firstGroupWithSizeOrNull(size: Int) =
    values.firstOrNull { it.size == size }

fun <T : Enum<T>> Map<T, List<Card>>.firstGroupWithMinSizeOrNull(minSize: Int) =
    values.firstOrNull { it.size >= minSize }