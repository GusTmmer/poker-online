package com.gustmmer.poker.hand

import com.gustmmer.poker.bot.BotSpot
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.toCards
import com.gustmmer.poker.round.AllIn
import com.gustmmer.poker.round.PokerRoundStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShowdownOddsTest {

    private fun odds(board: String, vararg hands: String): Map<Int, Double> = ShowdownOdds.compute(
        hands.withIndex().associate { (i, h) -> i to h.toCards() },
        if (board.isBlank()) emptyList() else board.toCards(),
    )

    // ── compute ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `aces are about an 82 percent favourite over kings before the flop`() {
        val result = odds("", "AS AH", "KS KH")
        assertEquals(0.82, result.getValue(0), 0.01)
        assertEquals(1.0, result.values.sum(), 1e-9)
    }

    @Test
    fun `flop odds are exact - they match a brute-force count with the engine evaluator`() {
        val board = "QS 7H 2D".toCards()
        val hands = mapOf(0 to "AS KS".toCards(), 1 to "JD JC".toCards(), 2 to "7C 8C".toCards())

        val dead = hands.values.flatten() + board
        val remaining = Card.cards.filterNot { it in dead }
        val shares = DoubleArray(3)
        var boards = 0
        for (i in remaining.indices) for (j in i + 1 until remaining.size) {
            val full = board + remaining[i] + remaining[j]
            val ranked = hands.mapValues { (_, pocket) -> TexasHoldEmHandEvaluator.getMatchingPokerHand(full, pocket) }
            val best = ranked.values.max()
            val winners = ranked.filterValues { it.compareTo(best) == 0 }.keys
            winners.forEach { shares[it] += 1.0 / winners.size }
            boards++
        }

        val result = ShowdownOdds.compute(hands, board)
        hands.keys.forEach { assertEquals(shares[it] / boards, result.getValue(it), 1e-12) }
    }

    @Test
    fun `on the river the best hand has it all`() {
        assertEquals(mapOf(0 to 1.0, 1 to 0.0), odds("KD 7C 2H 3S 4D", "KS KH", "AS AH"))
    }

    @Test
    fun `when the board plays, the pot is split`() {
        assertEquals(mapOf(0 to 0.5, 1 to 0.5), odds("AS KS QS JS 10S", "2C 3D", "4H 5C"))
    }

    @Test
    fun `is deterministic, including the sampled pre-flop case`() {
        assertEquals(odds("", "AS KD", "QC QH", "7S 6S"), odds("", "AS KD", "QC QH", "7S 6S"))
    }

    @Test
    fun `a six-way pre-flop calculation is quick`() {
        val hands = arrayOf("AS AH", "KS KH", "QS QH", "JS JH", "10C 9C", "8D 7D")
        odds("", *hands) // warm-up
        val start = System.nanoTime()
        odds("", *hands)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 150, "took $ms ms")
    }

    // ── forRunout ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pre-flop all-in gets odds for the bare board, flop, turn and river`() {
        val spot = BotSpot(listOf("AS AH", "KS KH"), board = "2C 7D 9H JC 3S")
            .act(::AllIn) // button shoves
            .call() // big blind calls
        assertEquals(PokerRoundStage.SHOWDOWN, spot.round.pokerRoundStage)

        val runout = ShowdownOdds.forRunout(spot.round)
        assertEquals(listOf(0, 3, 4, 5), runout.map { it.boardCards })
        runout.forEach { assertEquals(1.0, it.equities.values.sum(), 1e-9) }
        assertEquals(0.82, runout.first().equities.getValue(0), 0.01)
        assertEquals(mapOf(0 to 1.0, 1 to 0.0), runout.last().equities)
        // Each street's odds only know the cards shown by then.
        assertEquals(ShowdownOdds.compute(pocketsOf(spot), "2C 7D 9H".toCards()), runout[1].equities)
    }

    @Test
    fun `a flop all-in starts from the flop, and folded hands stay unknown`() {
        val spot = BotSpot(listOf("2C 3D", "AS AH", "KS KH"), board = "QD 7C 2H 4S 9D")
            .callAround() // limped pre-flop
            .act(::AllIn) // seat 1 shoves the flop
            .call() // seat 2 calls
            .fold() // seat 0 folds
        assertEquals(PokerRoundStage.SHOWDOWN, spot.round.pokerRoundStage)

        val runout = ShowdownOdds.forRunout(spot.round)
        assertEquals(listOf(3, 4, 5), runout.map { it.boardCards })
        assertEquals(setOf(1, 2), runout.first().equities.keys)
        assertEquals(ShowdownOdds.compute(pocketsOf(spot), "QD 7C 2H".toCards()), runout.first().equities)
    }

    @Test
    fun `no odds for a showdown on the river or a hand won without one`() {
        val river = BotSpot(listOf("2C 3D", "AS AH", "KS KH")).apply { repeat(4) { callAround() } }
        assertEquals(PokerRoundStage.SHOWDOWN, river.round.pokerRoundStage)
        assertTrue(ShowdownOdds.forRunout(river.round).isEmpty())

        val folded = BotSpot(listOf("2C 3D", "AS AH", "KS KH")).fold().fold()
        assertTrue(ShowdownOdds.forRunout(folded.round).isEmpty())
    }

    @Test
    fun `no odds while the hand is still being bet`() {
        val live = BotSpot(listOf("2C 3D", "AS AH", "KS KH")).callAround()
        assertTrue(ShowdownOdds.forRunout(live.round).isEmpty())
    }

    private fun pocketsOf(spot: BotSpot) =
        spot.round.players.filter { it.isActive() }.associate { it.id to it.pocketCards }
}
