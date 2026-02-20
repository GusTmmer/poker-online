package com.gustmmer.poker.hand.rankings

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class StraightTest {
    @Test
    fun `should use highest straight from mixed suits`() {
        val input = "A♠, 2♥, 3♠, 4♣, 5♦, 8♣, 7♣".toCards()
        val expected = "5♦, 4♣, 3♠, 2♥, A♠".toCards()

        assertEquals(expected, (Straight().highestMatchingPlayerHandOrNull(input))!!.cards)
    }

    @Test
    fun `should use highest straight when duplicate ranks exist`() {
        val input = "9♠, 10♠, J♦, J♥, Q♣, K♦, A♠".toCards()
        val expected = "A♠, K♦, Q♣, J♦, 10♠".toCards()

        val actual = Straight().highestMatchingPlayerHandOrNull(input)!!.cards

        assertEquals(expected, actual)
    }

    @Test
    fun `should use highest straight when duplicate ranks exist at sequence start`() {
        val input = "A♠, A♥, 2♠, 3♦, 4♣, 5♠, 6♣".toCards()
        val expected = "6♣, 5♠, 4♣, 3♦, 2♠".toCards()

        val actual = Straight().highestMatchingPlayerHandOrNull(input)!!.cards

        assertEquals(expected, actual)
    }

    @Test
    fun `should return null for wrap around straight`() {
        val wrapAroundStraight = "Q♦, K♣, A♠, 2♥, 3♠".toCards()

        assertNull(Straight().highestMatchingPlayerHandOrNull(wrapAroundStraight))
    }

    @Test
    fun `should return null for non-consecutive cards`() {
        val input = "2♠, 4♥, 6♠, 8♣, 10♦, Q♣, A♣".toCards()

        val result = Straight().highestMatchingPlayerHandOrNull(input)
        assertNull(result)
    }

    @Test
    fun `should handle multiple suits with same rank`() {
        val input = "7♠, 7♥, 8♠, 8♥, 9♠, 10♣, J♦".toCards()
        val expected = "J♦, 10♣, 9♠, 8♠, 7♠".toCards()

        val actual = Straight().highestMatchingPlayerHandOrNull(input)!!.cards
        assertEquals(expected, actual)
    }

    @Test
    fun `straight comparison`() {
        val higherStraight = "A♠, K♠, Q♠, J♠, 10♠".toCards()
        val lowerStraight = "K♠, Q♠, J♠, 10♠, 9♠".toCards()

        val expectedHigherHand = Straight().highestMatchingPlayerHandOrNull(higherStraight)!!
        val expectedLowerHand = Straight().highestMatchingPlayerHandOrNull(lowerStraight)!!

        assertTrue(expectedHigherHand > expectedLowerHand)
    }
}