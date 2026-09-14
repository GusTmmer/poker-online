package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreflopChartTest {

    @Test
    fun `ranks every one of the 169 starting-hand classes exactly once`() {
        assertEquals(169, PreflopChart.ORDER.size)
        assertEquals(PreflopChart.allClasses().toSet(), PreflopChart.ORDER.toSet())
    }

    @Test
    fun `aces are the best hand and 32 offsuit the worst`() {
        assertEquals(0.0, PreflopChart.percentile("AS AH".toCards()))
        assertEquals("AA", PreflopChart.ORDER.first())
        assertEquals("32o", PreflopChart.ORDER.last())
        assertEquals((1326 - 12) / 1326.0, PreflopChart.percentile("3S 2H".toCards()), 1e-9)
    }

    @Test
    fun `percentiles follow common starting-hand wisdom`() {
        fun pct(hand: String) = PreflopChart.percentile(hand.toCards())
        assertTrue(pct("AS KS") < pct("AS KD"), "suited beats offsuit")
        assertTrue(pct("KS KD") < pct("AS KS"), "kings beat ace-king")
        assertTrue(pct("AS KD") < pct("JS 10S"))
        assertTrue(pct("JS 10S") < pct("7S 2D"))
        assertTrue(pct("AS KD") < 0.10, "ace-king is a top-10% hand")
        assertTrue(pct("7S 2D") > 0.90, "seven-deuce is a bottom-10% hand")
    }

    @Test
    fun `suits don't matter beyond suited or offsuit, nor does card order`() {
        assertEquals(PreflopChart.percentile("AH KH".toCards()), PreflopChart.percentile("KS AS".toCards()))
        assertEquals(PreflopChart.percentile("AH KD".toCards()), PreflopChart.percentile("KC AS".toCards()))
        assertEquals("AKs", PreflopChart.classOf("KH AH".toCards()))
    }
}
