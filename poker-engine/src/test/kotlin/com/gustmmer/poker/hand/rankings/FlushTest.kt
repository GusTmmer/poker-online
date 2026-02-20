package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FlushTest {
    @Test
    fun `should use highest flush`() {
        val input = "A♠, K♠, Q♠, J♠, 10♠, 9♠, 8♠".toCards()
        val expected = "A♠, K♠, Q♠, J♠, 10♠".toCards()

        val actual = Flush().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle multiple suits`() {
        val input = "A♠, K♥, Q♠, J♦, 10♠, 9♠, 8♠".toCards()
        val expected = "A♠, Q♠, 10♠, 9♠, 8♠".toCards()

        val actual = Flush().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle more than five cards of same suit`() {
        val input = "A♠, K♠, Q♠, J♠, 10♠, 9♠, 8♠".toCards()
        val expected = "A♠, K♠, Q♠, J♠, 10♠".toCards()

        val actual = Flush().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should return null when no flush`() {
        val input = "A♠, K♥, Q♦, J♣, 10♠, 9♥, 8♦".toCards()

        val result = Flush().highestMatchingPlayerHandOrNull(input)
        assertNull(result)
    }

    @Test
    fun `flush comparison`() {
        val higherFlush = "A♠, K♠, Q♠, J♠, 9♠".toCards()
        val lowerFlush = "K♠, Q♠, J♠, 10♠, 9♠".toCards()

        val expectedHigherHand = Flush().highestMatchingPlayerHandOrNull(higherFlush)!!
        val expectedLowerHand = Flush().highestMatchingPlayerHandOrNull(lowerFlush)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}