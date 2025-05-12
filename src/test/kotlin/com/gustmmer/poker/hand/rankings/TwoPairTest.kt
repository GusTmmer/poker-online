package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TwoPairTest {

    @Test
    fun `should use highest two pairs and highest kicker`() {
        val pool = "A♠, A♥, K♠, K♥, Q♠, Q♥, J♠".toCards()
        val expected = "A♠, A♥, K♠, K♥, Q♠".toCards()

        val actual = TwoPair().highestMatchingPlayerHandOrNull(pool)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle three pairs in pool`() {
        val pool = "2♠, 2♥, 3♠, 3♥, 4♠, 4♥, A♠".toCards()
        val expected = "4♠, 4♥, 3♠, 3♥, A♠".toCards()

        val actual = TwoPair().highestMatchingPlayerHandOrNull(pool)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should return null for no pairs`() {
        val pool = "A♠, K♠, Q♠, J♠, 10♠, 9♠, 8♠".toCards()

        val actual = TwoPair().highestMatchingPlayerHandOrNull(pool)
        assertNull(actual)
    }

    @Test
    fun `should return null for single pair`() {
        val pool = "A♠, A♥, K♠, Q♠, J♠, 10♠, 9♠".toCards()

        val actual = TwoPair().highestMatchingPlayerHandOrNull(pool)
        assertNull(actual)
    }

    @Test
    fun `two pair comparison - different high pairs`() {
        val higherTwoPair = "A♠, A♥, K♠, K♥, Q♠".toCards()
        val lowerTwoPair = "K♠, K♥, Q♠, Q♥, J♠".toCards()

        val expectedHigherHand = TwoPair().highestMatchingPlayerHandOrNull(higherTwoPair)!!
        val expectedLowerHand = TwoPair().highestMatchingPlayerHandOrNull(lowerTwoPair)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }

    @Test
    fun `two pair comparison - same pairs different kicker`() {
        val higherTwoPair = "A♠, A♥, K♠, K♥, Q♠".toCards()
        val lowerTwoPair = "A♠, A♥, K♠, K♥, J♠".toCards()

        val expectedHigherHand = TwoPair().highestMatchingPlayerHandOrNull(higherTwoPair)!!
        val expectedLowerHand = TwoPair().highestMatchingPlayerHandOrNull(lowerTwoPair)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}