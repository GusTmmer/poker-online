package com.gustmmer.poker.deck

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource


class CardKtTest {

    @ParameterizedTest
    @CsvSource(
        "ACE, TWO, false",
        "TWO, ACE, true",
        "KING, ACE, false",
        "ACE, KING, true"
    )
    fun isImmediatelyAfterThan(reference: Rank, before: Rank, isExpected: Boolean) {
        val refCard = Card.card(Suit.SPADES, reference)
        val beforeCard = Card.card(Suit.CLUBS, before)

        assertEquals(isExpected, refCard.isRankImmediatelyAfterThan(beforeCard))
    }

    @ParameterizedTest
    @CsvSource(
        "ACE, TWO, true",
        "TWO, ACE, false",
        "KING, ACE, true",
        "ACE, KING, false"
    )
    fun isImmediatelyBeforeThan(reference: Rank, after: Rank, isExpected: Boolean) {
        val refCard = Card.card(Suit.SPADES, reference)
        val afterCard = Card.card(Suit.CLUBS, after)

        assertEquals(isExpected, refCard.isRankImmediatelyBeforeThan(afterCard))
    }
}