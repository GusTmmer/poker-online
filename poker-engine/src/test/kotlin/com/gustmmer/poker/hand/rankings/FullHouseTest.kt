package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FullHouseTest {
    @Test
    fun `should use highest triple and highest pair`() {
        val input = "A♠, A♥, A♦, K♠, K♥, Q♠, Q♥".toCards()
        val expected = "A♠, A♥, A♦, K♠, K♥".toCards()

        val actual = FullHouse().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle multiple triples`() {
        val input = "A♠, A♥, A♦, K♠, K♥, K♦, Q♠".toCards()
        val expected = "A♠, A♥, A♦, K♠, K♥".toCards()

        val actual = FullHouse().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should handle multiple pairs`() {
        val input = "A♠, A♥, A♦, K♠, K♥, Q♠, Q♥".toCards()
        val expected = "A♠, A♥, A♦, K♠, K♥".toCards()

        val actual = FullHouse().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `should return null when no triple`() {
        val input = "A♠, A♥, K♠, K♥, Q♠, Q♥, J♠".toCards()

        val result = FullHouse().highestMatchingPlayerHandOrNull(input)
        assertNull(result)
    }

    @Test
    fun `should return null when no pair`() {
        val input = "A♠, A♥, A♦, K♠, Q♠, J♠, 10♠".toCards()

        val result = FullHouse().highestMatchingPlayerHandOrNull(input)
        assertNull(result)
    }

    @Test
    fun `full house comparison with different triples`() {
        val higherFullHouse = "A♠, A♥, A♦, K♠, K♥".toCards()
        val lowerFullHouse = "K♠, K♥, K♦, A♠, A♥".toCards()

        val expectedHigherHand = FullHouse().highestMatchingPlayerHandOrNull(higherFullHouse)!!
        val expectedLowerHand = FullHouse().highestMatchingPlayerHandOrNull(lowerFullHouse)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }

    @Test
    fun `full house comparison with same triple but different pairs`() {
        val higherFullHouse = "A♠, A♥, A♦, K♠, K♥".toCards()
        val lowerFullHouse = "A♠, A♥, A♦, Q♠, Q♥".toCards()

        val expectedHigherHand = FullHouse().highestMatchingPlayerHandOrNull(higherFullHouse)!!
        val expectedLowerHand = FullHouse().highestMatchingPlayerHandOrNull(lowerFullHouse)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}