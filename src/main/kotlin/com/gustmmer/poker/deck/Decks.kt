package com.gustmmer.poker.deck

import kotlin.random.Random

open class Deck protected constructor(private val cards: List<Card>) {

    private var headIndex = 0

    companion object {
        fun shuffled(): Deck = Deck(Card.cards.shuffled(Random(System.nanoTime())))

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