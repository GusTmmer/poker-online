package com.gustmmer.poker.deck

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

private val cardAsStrRegex = Regex("""([2-9AKQJ]|10)-?([HDCS♥♦♣♠])""")
private val cardSeparationRegex = Regex("""[\s,]+""")

/** Thread-safe; shared so each shuffle doesn't pay for a new instance's seeding. */
private val secureRandom = SecureRandom().asKotlinRandom()

object CardListSerializer : KSerializer<List<Card>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CardList", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: List<Card>) {
        val cardsStr = value.joinToString(",") { "${it.rank}${it.suit}" }
        encoder.encodeString(cardsStr)
    }

    override fun deserialize(decoder: Decoder): List<Card> {
        val cardsStr = decoder.decodeString()
        if (cardsStr.isBlank()) return emptyList()
        return cardsStr.split(cardSeparationRegex).map { token ->
            val match = token.trim().let { cardAsStrRegex.matchEntire(it) }
                ?: throw IllegalArgumentException("Invalid card format: ${token.trim()}")

            val (rankStr, suitStr) = match.destructured
            val rank = Rank.fromSymbol(rankStr)
            val suit = Suit.fromSymbol(suitStr.first())

            Card.card(suit, rank)
        }
    }
}

@Serializable
open class Deck protected constructor(
    @Serializable(with = CardListSerializer::class)
    private val cards: List<Card>
) {
    private var headIndex = 0

    companion object {
        /**
         * Shuffles with a cryptographically strong generator. A seeded PRNG would be a real exploit here: its
         * whole deck follows from one 64-bit seed (and `nanoTime` makes that seed guessable), so a player who
         * sees their own cards and the flop could search for it and read everyone else's hand and the turn
         * and river.
         */
        fun shuffled(): Deck = Deck(Card.cards.shuffled(secureRandom))
        fun shuffledWithSeed(seed: Long): Deck = Deck(Card.cards.shuffled(Random(seed)))
    }

    fun draw(count: Int): List<Card> {
        require(count > 0 && headIndex + count <= cards.size) { "Not enough cards" }

        return buildList(count) {
            repeat(count) {
                add(cards[headIndex++])
            }
        }
    }
}