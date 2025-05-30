package com.gustmmer.poker.deck

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = CardSerializer::class)
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

object CardSerializer : KSerializer<Card> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Card", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Card) {
        encoder.encodeString("${value.rank}${value.suit}")
    }

    override fun deserialize(decoder: Decoder): Card {
        val str = decoder.decodeString()
        val rank = Rank.fromSymbol(str.substring(0, str.length - 1))
        val suit = Suit.fromSymbol(str.last())
        return Card.card(suit, rank)
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
