package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ThreeOfAKindTest {

    @Test
    fun `should use highest three of a kind with highest kickers`() {
        val input = "A♠, A♥, A♦, K♠, Q♠, J♠, 10♠".toCards()
        val expected = "A♠, A♥, A♦, K♠, Q♠".toCards()

        val actual = ThreeOfAKind().highestMatchingPlayerHandOrNull(input)!!.cards

        assertEquals(expected, actual)
    }

    @Test
    fun `should return null when no three of a kind exists`() {
        val input = "A♠, A♥, K♠, K♥, Q♠, J♠, 10♠".toCards()

        val actual = ThreeOfAKind().highestMatchingPlayerHandOrNull(input)

        assertNull(actual)
    }

    @Test
    fun `three of a kind comparison`() {
        val higherThreeOfAKind = "A♠, A♥, A♦, K♠, Q♠".toCards()
        val lowerThreeOfAKind = "K♠, K♥, K♦, A♠, Q♠".toCards()

        val expectedHigherHand = ThreeOfAKind().highestMatchingPlayerHandOrNull(higherThreeOfAKind)!!
        val expectedLowerHand = ThreeOfAKind().highestMatchingPlayerHandOrNull(lowerThreeOfAKind)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}