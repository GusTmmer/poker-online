package com.gustmmer.poker.bot

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Plays the personalities against each other and checks they come out as distinct styles, near the
 * VPIP/PFR targets their profiles are tuned for. Also a large legality and chip-conservation soak: every
 * bot decision goes through the engine's validation.
 */
class BotPersonalityTuningTest {

    @Test
    fun `personalities play recognisably different styles`() {
        val seats = listOf(
            BotPersonality.AGGRESSIVE, BotPersonality.BALANCED, BotPersonality.DEFENSIVE,
            BotPersonality.AGGRESSIVE, BotPersonality.BALANCED, BotPersonality.DEFENSIVE,
        )
        val stats = BotSimulationHarness(seats).run(hands = 1500)
        stats.forEach { (personality, s) -> println("%-10s %s".format(personality, s)) }

        val agg = stats.getValue(BotPersonality.AGGRESSIVE)
        val bal = stats.getValue(BotPersonality.BALANCED)
        val def = stats.getValue(BotPersonality.DEFENSIVE)

        assertTrue(agg.vpipRate > bal.vpipRate && bal.vpipRate > def.vpipRate, "VPIP ordering")
        assertTrue(agg.pfrRate > bal.pfrRate && bal.pfrRate > def.pfrRate, "PFR ordering")
        assertTrue(agg.stealRate > def.stealRate, "the aggressive bot steals more")

        assertInBand("aggressive VPIP", agg.vpipRate, 0.25, 0.42)
        assertInBand("aggressive PFR", agg.pfrRate, 0.18, 0.32)
        assertInBand("balanced VPIP", bal.vpipRate, 0.14, 0.27)
        assertInBand("balanced PFR", bal.pfrRate, 0.10, 0.20)
        assertInBand("defensive VPIP", def.vpipRate, 0.08, 0.18)
        assertInBand("defensive PFR", def.pfrRate, 0.02, 0.09)
    }

    @Test
    fun `heads-up and short-handed tables play out without illegal moves or lost chips`() {
        listOf(2, 3, 9).forEach { n ->
            BotSimulationHarness(List(n) { BotPersonality.entries[it % 3] }, startingChips = 1_500, seed = n.toLong())
                .run(hands = 300)
        }
    }

    private fun assertInBand(label: String, value: Double, low: Double, high: Double) =
        assertTrue(value in low..high, "$label = ${"%.3f".format(value)}, expected $low..$high")
}
