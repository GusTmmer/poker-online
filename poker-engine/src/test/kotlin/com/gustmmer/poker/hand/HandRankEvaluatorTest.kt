package com.gustmmer.poker.hand

import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.hand.rankings.HandRanking.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import kotlin.math.sqrt

class HandRankEvaluatorTest {

    @Test
    @Disabled
    fun `shuffle distribution test`() {
        val sampleSize = 10_000_000

        val distribution = generateSequence(1746057536625) { it + 1 }.flatMap { seed ->
            Deck.shuffledWithSeed(seed).run {
                buildList {
                    repeat(7) {
                        add(draw(7).sortedDescending().joinToString { it.toString() })
                    }
                }
            }
        }.take(sampleSize).groupingBy { it }.eachCount()

        println("Max: ${distribution.maxOf { it.value }}")
        println("Min: ${distribution.minOf { it.value }}")

        val avg = distribution.values.average()

        val variance = distribution.values.map { (it - avg) * (it - avg) }.average()
        val stdDev = sqrt(variance)

        println("StdDev: $stdDev")
    }

    @Test
    @Disabled
    fun `validate poker hand type distribution`() {
        val sampleSize = 10_000_000

        val rankDist = generateSequence(1746057536625) { it + 1 }.map { seed ->
            with(Deck.shuffledWithSeed(seed).draw(7)) {
                PocketTake2HandEvaluator.getMatchingPokerHand(take(5), takeLast(2))
            }
        }.take(sampleSize).groupingBy { it.ranking }.eachCount()

        assertDistribution(0.03110, rankDist[STRAIGHT_FLUSH]!!, sampleSize)
        assertDistribution(0.16807, rankDist[FOUR_OF_A_KIND]!!, sampleSize)
        assertDistribution(2.59611, rankDist[FULL_HOUSE]!!, sampleSize)
        assertDistribution(3.02549, rankDist[FLUSH]!!, sampleSize)
        assertDistribution(4.61938, rankDist[STRAIGHT]!!, sampleSize)
        assertDistribution(4.82707, rankDist[THREE_OF_A_KIND]!!, sampleSize)
        assertDistribution(23.49554, rankDist[TWO_PAIR]!!, sampleSize)
        assertDistribution(43.82255, rankDist[ONE_PAIR]!!, sampleSize)
        assertDistribution(17.41254, rankDist[HIGH_CARD]!!, sampleSize)
    }

    private fun assertDistribution(
        expectedPercentage: Double,
        observed: Int,
        totalSamples: Int,
        alpha: Double = 0.01
    ) {
        val expected = expectedPercentage / 100.0 * totalSamples
        val chiSquared = if (expected == 0.0) 0.0 else ((observed - expected) * (observed - expected)) / expected

        val criticalValue = chiSquareCriticalValue(df = 1, alpha = alpha)

        assertTrue(chiSquared <= criticalValue)
    }

    private fun chiSquareCriticalValue(df: Int, alpha: Double): Double {
        val table = mapOf(
            0.05 to mapOf(1 to 3.84),
            0.01 to mapOf(1 to 6.63)
        )

        return table[alpha]?.get(df) ?: error("Unsupported degrees of freedom or alpha")
    }
}
