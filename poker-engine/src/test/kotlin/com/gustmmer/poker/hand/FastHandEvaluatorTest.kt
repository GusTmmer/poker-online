package com.gustmmer.poker.hand

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.sign
import kotlin.random.Random

class FastHandEvaluatorTest {

    private fun score(cards: List<Card>) =
        FastHandEvaluator.score(cards.map(FastHandEvaluator::code).toIntArray(), cards.size)

    @Test
    fun `orders random 7-card hands exactly like the engine evaluator`() {
        val rng = Random(42)
        repeat(20_000) {
            val dealt = Card.cards.shuffled(rng).take(9)
            val board = dealt.take(5)
            val a = dealt.subList(5, 7)
            val b = dealt.subList(7, 9)

            val engine = TexasHoldEmHandEvaluator.getMatchingPokerHand(board, a)
                .compareTo(TexasHoldEmHandEvaluator.getMatchingPokerHand(board, b)).sign
            val fast = score(a + board).compareTo(score(b + board)).sign

            assertEquals(engine, fast, "board=$board a=$a b=$b")
        }
    }

    @Test
    fun `the wheel is the lowest straight and a steel wheel the lowest straight flush`() {
        assertTrue(score("AH 2D 3C 4S 5H 9D KC".toCards()) < score("2H 3D 4C 5S 6H 9D KC".toCards()))
        assertTrue(score("AH 2H 3H 4H 5H 9D KC".toCards()) < score("2H 3H 4H 5H 6H 9D KC".toCards()))
    }
}
