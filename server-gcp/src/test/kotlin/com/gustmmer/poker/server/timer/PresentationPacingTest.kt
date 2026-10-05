package com.gustmmer.poker.server.timer

import com.gustmmer.poker.PresentationCursor
import com.gustmmer.poker.round.HandAction
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.HandActionType.*
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.PokerRoundStage.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PresentationPacingTest {

    private val now = 1_000_000L
    private fun act(stage: PokerRoundStage, type: HandActionType) = HandAction(playerId = 0, stage = stage, type = type)
    private val blinds = listOf(act(BET_BLINDS, SMALL_BLIND), act(BET_BLINDS, BIG_BLIND))

    // Mirrors of the frontend's holds (see PresentationPacing).
    private fun deal(players: Int) = (players * 2 - 1) * 420L + 320 + 250 + 350
    private val flop = 220L + 400 + 80 + 2 * 180 + 2 * 140 + 100 + 350
    private val turnOrRiver = 220L + 400 + 80 + 2 * 140 + 100 + 350
    private val beat = 650L

    @Test
    fun `a new hand waits for its deal, scaling with the players dealt in`() {
        val twoHanded = PresentationPacing.advance(null, 2, blinds, BET_BLINDS, now)
        val sixHanded = PresentationPacing.advance(null, 6, blinds, BET_BLINDS, now)

        assertEquals(now + deal(2), twoHanded.busyUntil)
        assertEquals(now + deal(6), sixHanded.busyUntil)
        assertEquals(2, twoHanded.actionsSeen)
    }

    @Test
    fun `each action since the last turn adds its callout, a check adds nothing`() {
        val cursor = PresentationCursor(actionsSeen = 2, busyUntil = 0)
        val actions = blinds + act(BET_BLINDS, CALL) + act(BET_BLINDS, FOLD) + act(BET_BLINDS, RAISE)

        assertEquals(now + 3 * beat, PresentationPacing.advance(cursor, 3, actions, BET_BLINDS, now).busyUntil)

        val checked = blinds + act(BET_FLOP, CHECK)
        assertEquals(now, PresentationPacing.advance(PresentationCursor(2, 0), 3, checked, BET_FLOP, now).busyUntil)
    }

    @Test
    fun `the action that closes a street holds for the street's cards instead of its callout`() {
        val cursor = PresentationCursor(actionsSeen = 3, busyUntil = 0)
        val toFlop = blinds + act(BET_BLINDS, CALL) + act(BET_BLINDS, CHECK)
        assertEquals(now + flop, PresentationPacing.advance(cursor, 2, toFlop, BET_FLOP, now).busyUntil)

        val toTurn = toFlop + act(BET_FLOP, BET) + act(BET_FLOP, CALL)
        assertEquals(
            now + beat + turnOrRiver,
            PresentationPacing.advance(PresentationCursor(4, 0), 2, toTurn, BET_TURN, now).busyUntil,
        )
    }

    @Test
    fun `animations still playing carry over into the next turn's wait`() {
        // A six-way deal just went out; the first player (a bot) folded a second later.
        val dealt = PresentationPacing.advance(null, 6, blinds, BET_BLINDS, now)
        val oneSecondLater = now + 1_000
        val afterFold = PresentationPacing.advance(dealt, 6, blinds + act(BET_BLINDS, FOLD), BET_BLINDS, oneSecondLater)

        assertEquals(now + deal(6) + beat, afterFold.busyUntil)
        assertEquals(deal(6) + beat - 1_000, PresentationPacing.headStartMs(afterFold, oneSecondLater))
    }

    @Test
    fun `a screen that caught up long ago adds no wait, and the head start is capped`() {
        val stale = PresentationCursor(actionsSeen = 2, busyUntil = now - 60_000)
        val idle = PresentationPacing.advance(stale, 2, blinds, BET_BLINDS, now)
        assertEquals(0L, PresentationPacing.headStartMs(idle, now))

        val far = PresentationCursor(actionsSeen = 2, busyUntil = now + 60_000)
        assertEquals(PresentationPacing.MAX_HEAD_START_MS, PresentationPacing.headStartMs(far, now))
    }
}
