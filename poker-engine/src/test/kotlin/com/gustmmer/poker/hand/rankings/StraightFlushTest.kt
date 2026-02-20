package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StraightFlushTest {

    @Test
    fun `should use highest straight flush`() {
        val input = "A♠, K♠, Q♠, J♠, 10♠, 9♠, 8♠".toCards()
        val expected = "A♠, K♠, Q♠, J♠, 10♠".toCards()

        val actual = StraightFlush().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should return null for non-consecutive cards of same suit`() {
        val input = "A♠, K♠, Q♠, J♠, 9♠, 8♠, 7♠".toCards()

        val actual = StraightFlush().highestMatchingPlayerHandOrNull(input)
        assertNull(actual)
    }

    @Test
    fun `royal flush comparison`() {
        val royalFlush1 = "A♠, K♠, Q♠, J♠, 10♠".toCards()
        val royalFlush2 = "A♥, K♥, Q♥, J♥, 10♥".toCards()

        val spadesRoyalFlush = StraightFlush().highestMatchingPlayerHandOrNull(royalFlush1)!!
        val heartsRoyalFlush = StraightFlush().highestMatchingPlayerHandOrNull(royalFlush2)!!

        assertEquals(0, spadesRoyalFlush.compareTo(heartsRoyalFlush))
    }

    @Test
    fun `straight flush comparison`() {
        val higherStraightFlush = "A♠, K♠, Q♠, J♠, 10♠".toCards()
        val lowerStraightFlush = "K♠, Q♠, J♠, 10♠, 9♠".toCards()

        val expectedHigherHand = StraightFlush().highestMatchingPlayerHandOrNull(higherStraightFlush)!!
        val expectedLowerHand = StraightFlush().highestMatchingPlayerHandOrNull(lowerStraightFlush)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}