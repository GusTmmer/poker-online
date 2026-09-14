package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EquityCalculatorTest {

    private fun equity(pocket: String, board: String = "", vararg ceilings: Double) =
        EquityCalculator.equity(pocket.toCards(), if (board.isBlank()) emptyList() else board.toCards(), ceilings.toList())

    @Test
    fun `matches well-known pre-flop equities`() {
        assertEquals(0.85, equity("AS AH", "", 1.0), 0.03, "aces vs a random hand")
        assertEquals(0.67, equity("AS KS", "", 1.0), 0.05, "suited ace-king vs a random hand")
        assertTrue(equity("AS AH", "", 1.0, 1.0, 1.0) < equity("AS AH", "", 1.0), "equity drops multi-way")
    }

    @Test
    fun `converges to exact equities without sampling bias`() {
        fun precise(pocket: String) = EquityCalculator.equity(pocket.toCards(), emptyList(), listOf(1.0), iterations = 200_000)
        assertEquals(0.852, precise("AS AH"), 0.006)
        assertEquals(0.670, precise("AS KS"), 0.006)
        assertEquals(0.346, precise("7S 2D"), 0.006)
        assertEquals(0.503, precise("2S 2D"), 0.006)
    }

    @Test
    fun `is deterministic for the same visible spot`() {
        assertEquals(equity("QS JS", "10S 9D 2C", 0.3, 1.0), equity("QS JS", "10S 9D 2C", 0.3, 1.0))
    }

    @Test
    fun `a narrow opponent range lowers a medium hand's equity`() {
        val vsAnyTwo = equity("KS JD", "KD 7C 2H", 1.0)
        val vsReRaiser = equity("KS JD", "KD 7C 2H", 0.05)
        assertTrue(vsAnyTwo > 0.8, "top pair is far ahead of a random hand ($vsAnyTwo)")
        assertTrue(vsAnyTwo - vsReRaiser > 0.15, "top pair fares much worse against a re-raiser ($vsReRaiser)")
    }

    @Test
    fun `made nuts on the river always win`() {
        assertEquals(1.0, equity("AS KS", "QS JS 10S 2D 3C", 0.1, 1.0), 1e-9)
    }

    @Test
    fun `no opponents means the pot is already won`() {
        assertEquals(1.0, equity("7S 2D", "", *doubleArrayOf()))
    }

    @Test
    fun `a five-way flop decision is fast enough to run inside a request`() {
        equity("AS KD", "QS JH 4C", 0.2, 0.45, 1.0, 1.0, 1.0) // warm-up
        val start = System.nanoTime()
        repeat(20) { i -> equity("AS KD", "QS JH ${2 + i % 8}C", 0.2, 0.45, 1.0, 1.0, 1.0) }
        val avgMs = (System.nanoTime() - start) / 20 / 1_000_000.0
        assertTrue(avgMs < 50, "average $avgMs ms")
    }
}
