package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.hand.PocketTake2HandEvaluator
import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PokerHandRankingTest {

        @Test
        fun `detects royal flush`() {
            val input = "10♠, J♠, Q♠, K♠, A♠, 2♣, 3♦"
            val expected = "A♠, K♠, Q♠, J♠, 10♠"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects straight flush`() {
            val input = "7♥, 8♥, 9♥, 10♥, J♥, 2♣, K♦"
            val expected = "J♥, 10♥, 9♥, 8♥, 7♥"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects four of a kind`() {
            val input = "Q♠, Q♥, Q♦, Q♣, 2♠, 5♣, 9♦"
            val expected = "Q♠, Q♥, Q♦, Q♣, 9♦"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects full house`() {
            val input = "3♠, 3♦, 3♥, K♠, K♦, 2♣, 6♣"
            val expected = "3♠, 3♦, 3♥, K♠, K♦"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects flush`() {
            val input = "2♣, 5♣, 7♣, 9♣, K♣, A♦, 10♦"
            val expected = "K♣, 9♣, 7♣, 5♣, 2♣"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects straight`() {
            val input = "9♣, 10♦, J♠, Q♥, K♦, 2♠, 5♣"
            val expected = "K♦, Q♥, J♠, 10♦, 9♣"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects three of a kind`() {
            val input = "8♠, 8♥, 8♦, 2♣, 6♠, J♦, Q♣"
            val expected = "8♠, 8♥, 8♦, Q♣, J♦"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects two pair`() {
            val input = "J♠, J♦, 4♣, 4♥, 2♣, 5♠, 9♦"
            val expected = "J♠, J♦, 4♣, 4♥, 9♦"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects one pair`() {
            val input = "10♠, 10♦, 3♣, 6♥, 7♣, 9♠, K♦"
            val expected = "10♠, 10♦, K♦, 9♠, 7♣"
            assertBestHand(input, expected)
        }

        @Test
        fun `detects high card`() {
            val input = "2♣, 5♦, 7♠, 9♣, J♥, Q♦, A♠"
            val expected = "A♠, Q♦, J♥, 9♣, 7♠"
            assertBestHand(input, expected)
        }

    private fun assertBestHand(input: String, expected: String) {
        val inputCards = input.toCards()
        val expectedPokerHand = expected.toCards()
        val actualPokerHand = PocketTake2HandEvaluator.getMatchingPokerHand(inputCards.take(5), inputCards.takeLast(2))

        assertEquals(expectedPokerHand, actualPokerHand.cards)
    }
}
