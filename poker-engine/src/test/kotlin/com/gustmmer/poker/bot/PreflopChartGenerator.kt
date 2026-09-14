package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Rank
import com.gustmmer.poker.deck.Suit
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Regenerates [PreflopChart.ORDER]: every starting-hand class ranked by heads-up equity against a random
 * hand. Run with `GENERATE_PREFLOP_CHART=true ./gradlew :poker-engine:test --tests '*PreflopChartGenerator*'`
 * and paste the printed list into [PreflopChart].
 */
class PreflopChartGenerator {

    @Test
    @EnabledIfEnvironmentVariable(named = "GENERATE_PREFLOP_CHART", matches = "true")
    fun generate() {
        val ranked = PreflopChart.allClasses()
            .associateWith { EquityCalculator.equity(representative(it), emptyList(), listOf(1.0), iterations = 100_000) }
            .entries.sortedByDescending { it.value }

        println(ranked.chunked(13).joinToString("\n") { row -> row.joinToString(" ") { it.key } })
    }

    private fun representative(hand: String): List<Card> {
        val ranks = "23456789TJQKA"
        val hi = Rank.entries[ranks.indexOf(hand[0])]
        val lo = Rank.entries[ranks.indexOf(hand[1])]
        val secondSuit = if (hand.endsWith("s")) Suit.SPADES else Suit.HEARTS
        return listOf(Card.card(Suit.SPADES, hi), Card.card(secondSuit, lo))
    }
}
