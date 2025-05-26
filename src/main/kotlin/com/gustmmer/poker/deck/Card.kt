package com.gustmmer.poker.deck


class Card private constructor(val rank: Rank, val suit: Suit) : Comparable<Card> {

    companion object {
        val cards: List<Card> = Suit.entries.flatMap { suit -> Rank.entries.map { rank -> Card(rank, suit) } }

        private val cardMap: Map<Suit, Map<Rank, Card>> = cards.groupBy { it.suit }
            .mapValues { (_, cardsSameSuit) -> cardsSameSuit.associateBy { it.rank } }

        fun card(suit: Suit, rank: Rank): Card = cardMap[suit]!![rank]!!
    }

    override fun compareTo(other: Card): Int {
        return compareBy<Card> { it.rank }.compare(this, other)
    }

    override fun toString(): String {
        return "$rank$suit"
    }
}

/**
 * Assumes cyclic natural order 2, 3, ... J, Q, K, A
 * 2 is next to 'A'
 */
fun Card.isRankImmediatelyAfterThan(other: Card): Boolean {
    if (this.rank == Rank.TWO) {
        return other.rank == Rank.ACE
    }

    return (this.rank.ordinal - other.rank.ordinal) == 1
}

/**
 * Assumes cyclic natural order 2, 3, ... J, Q, K, A
 * 2 is next to 'A'
 */
fun Card.isRankImmediatelyBeforeThan(other: Card): Boolean {
    if (this.rank == Rank.ACE) {
        return other.rank == Rank.TWO
    }

    return (other.rank.ordinal - this.rank.ordinal) == 1
}

operator fun Card.plus(other: Card): List<Card> {
    return listOf(this, other)
}
