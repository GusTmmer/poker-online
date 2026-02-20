package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OnePairTest {

    @Test
    fun `should use highest pair with highest kickers`() {
        val input = "A♠, A♥, K♠, Q♠, J♠, 10♠, 9♠".toCards()
        val expected = "A♠, A♥, K♠, Q♠, J♠".toCards()

        val actual = OnePair().highestMatchingPlayerHandOrNull(input)!!.cards

        assertEquals(expected, actual)
    }

    @Test
    fun `should return null when no pairs exist`() {
        val input = "A♠, K♠, Q♠, J♠, 10♠, 9♠, 8♠".toCards()

        val actual = OnePair().highestMatchingPlayerHandOrNull(input)

        assertNull(actual)
    }

    @Test
    fun `one pair comparison`() {
        val higherPair = "A♠, A♥, K♠, Q♠, J♠".toCards()
        val lowerPair = "K♠, K♥, A♠, Q♠, J♠".toCards()

        val expectedHigherHand = OnePair().highestMatchingPlayerHandOrNull(higherPair)!!
        val expectedLowerHand = OnePair().highestMatchingPlayerHandOrNull(lowerPair)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
} 