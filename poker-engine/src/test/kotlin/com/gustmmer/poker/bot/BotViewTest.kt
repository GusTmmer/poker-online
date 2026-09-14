package com.gustmmer.poker.bot

import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.deck.toCards
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.PlayerCommand
import com.gustmmer.poker.round.Raise
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.PokerRoundState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BotViewTest {

    @Test
    fun `hidden cards - opponents' hands and the undealt deck - change neither the view nor the decision`() {
        // Same bot hand (seat 3) and the same flop; everything the bot must not see differs.
        val a = BotSpot(listOf("2C 3D", "4H 5S", "6C 7D", "AS KD", "9H 9S", "QC QH"), board = "KS 7C 2H 10D JS")
        val b = BotSpot(listOf("AH AC", "KH KC", "8D 8S", "AS KD", "5C 4D", "JD 10C"), board = "KS 7C 2H 3S 6H")

        listOf(a, b).forEach { it.callAround().callUntil(3) } // limped pre-flop, checked to seat 3 on the flop

        assertEquals(PokerRoundStage.BET_FLOP, a.round.pokerRoundStage)
        assertEquals(a.view(), b.view())
        repeat(3) {
            val da = BotStrategy.decide(a.view(), BotPersonality.BALANCED.profile, FixedRoll.NEVER)
            val db = BotStrategy.decide(b.view(), BotPersonality.BALANCED.profile, FixedRoll.NEVER)
            assertEquals(da.command.describe(), db.command.describe())
        }
    }

    @Test
    fun `the view has no path to hidden state`() {
        val forbidden = setOf(Deck::class.java, Player::class.java, PokerRoundState::class.java)
        listOf(BotView::class.java, OpponentView::class.java).forEach { type ->
            type.declaredFields.forEach { field ->
                assertTrue(field.type !in forbidden, "${type.simpleName}.${field.name} exposes ${field.type.simpleName}")
            }
        }
        val spot = BotSpot(listOf("AS KD", "4H 5S", "2C 3D"), board = "KS 7C 2H 10D JS")
        assertEquals("AS KD".toCards(), spot.view().pocketCards)
        assertTrue(spot.view().communityCards.isEmpty(), "no board is visible pre-flop")
        spot.callAround()
        assertEquals("KS 7C 2H".toCards(), spot.view().communityCards, "only the flop is visible on the flop")
    }

    @Test
    fun `positions around a six-handed table`() {
        val spot = BotSpot(listOf("2C 3D", "4H 5S", "6C 7D", "8H 9S", "JC QH", "AS KD"))
        val positions = spot.players.map { BotView.positionOf(spot.round, it) }
        assertEquals(
            listOf(Position.LATE, Position.BLINDS, Position.BLINDS, Position.EARLY, Position.MIDDLE, Position.LATE),
            positions,
        )
    }

    @Test
    fun `heads-up the button is in position and the other seat is the blind`() {
        val spot = BotSpot(listOf("2C 3D", "4H 5S"))
        assertEquals(listOf(Position.LATE, Position.BLINDS), spot.players.map { BotView.positionOf(spot.round, it) })
    }

    @Test
    fun `reads the public action log`() {
        val spot = BotSpot(listOf("2C 3D", "4H 5S", "6C 7D", "8H 9S"))
        // Seat 3 (under the gun) opens, the button calls.
        spot.raise(200).call()
        val view = spot.view() // small blind to act

        assertEquals(listOf(HandActionType.SMALL_BLIND, HandActionType.BIG_BLIND, HandActionType.RAISE, HandActionType.CALL),
            view.actions.map { it.type })
        assertFalse(view.unopened)
        assertEquals(1, view.raisesThisStreet)
        assertEquals(300, view.streetBet)
        assertEquals(50 + 100 + 300 + 300, view.potTotal)
        assertEquals(250, view.toCall)
        assertEquals(3, view.opponents.size)
    }
}

internal fun PlayerCommand.describe(): String = type.name + ((this as? Raise)?.value?.let { " $it" } ?: "")
