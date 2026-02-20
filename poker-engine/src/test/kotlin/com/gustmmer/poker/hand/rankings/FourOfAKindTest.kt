package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FourOfAKindTest {
    @Test
    fun `should use highest four of a kind and highest kicker`() {
        val input = "A♠, A♥, A♦, A♣, K♠, Q♠, J♠".toCards()
        val expected = "A♠, A♥, A♦, A♣, K♠".toCards()

        val actual = FourOfAKind().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should return null when no four of a kind`() {
        val input = "A♠, A♥, A♦, K♠, K♥, Q♠, J♠".toCards()

        val result = FourOfAKind().highestMatchingPlayerHandOrNull(input)
        assertNull(result)
    }

    @Test
    fun `four of a kind comparison`() {
        val higherFourOfAKind = "A♠, A♥, A♦, A♣, K♠".toCards()
        val lowerFourOfAKind = "K♠, K♥, K♦, K♣, A♠".toCards()

        val expectedHigherHand = FourOfAKind().highestMatchingPlayerHandOrNull(higherFourOfAKind)!!
        val expectedLowerHand = FourOfAKind().highestMatchingPlayerHandOrNull(lowerFourOfAKind)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}