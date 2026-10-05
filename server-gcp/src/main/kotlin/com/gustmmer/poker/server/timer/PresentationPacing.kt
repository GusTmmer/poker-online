package com.gustmmer.poker.server.timer

import com.gustmmer.poker.PresentationCursor
import com.gustmmer.poker.round.HandAction
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.PokerRoundStage

/**
 * How long the players' screens are still animating the hand — the deal, a street being dealt, each action's
 * callout — so a turn's clock can start once the table actually shows it's that player's turn.
 *
 * The server moves on the moment an action commits, but every client plays the resulting frames one at a
 * time: `processFrame`'s pacing hold in `frontend/src/components/PixiTable/PixiPokerTable.tsx` holds each
 * frame until the previous one's animation has played. This replays that hold from the hand's public action
 * log. Each committed action is one frame; a frame that also deals a street holds for the deal instead of the
 * action's callout (the client holds for the longer of the two). The durations mirror the frontend's
 * constants (`PixiTable/constants.ts`, `PixiPokerTable.tsx`) and must move with them.
 */
object PresentationPacing {
    private const val DEAL_INTERVAL_MS = 420L
    private const val DEAL_FLIGHT_MS = 320L
    private const val DEAL_CROSSFADE_PAD_MS = 250L
    private const val STAGE_BEAT_MS = 350L
    private const val ACTION_BEAT_MS = 650L
    private const val DECK_DELAY_MS = 220L
    private const val SLIDE_MS = 400L
    private const val FLIP_PAUSE_MS = 80L
    private const val FLIP_STAGGER_MS = 180L
    private const val FLIP_HALF_MS = 140L
    private const val STREET_SETTLE_PAD_MS = 100L

    /** Upper bound on a head start, so a mismodelled lag can never hand a player an unbounded clock. */
    const val MAX_HEAD_START_MS = 8_000L

    /**
     * Advances [cursor] past everything the hand has done since (the whole hand when null: its deal to
     * [dealtIn] players, then every action), as of [now]. [actions] is the hand's action log and [stage] the
     * stage it is in now. The returned cursor's `busyUntil` is when the screens will have caught up.
     */
    fun advance(
        cursor: PresentationCursor?,
        dealtIn: Int,
        actions: List<HandAction>,
        stage: PokerRoundStage,
        now: Long,
    ): PresentationCursor {
        // New frames start playing once both they've arrived (about now) and the screen is free.
        var busyUntil = maxOf(now, cursor?.busyUntil ?: 0L)
        if (cursor == null) busyUntil += dealMs(dealtIn)
        for (i in (cursor?.actionsSeen ?: 0) until actions.size) {
            val action = actions[i]
            // Posted with the deal, in the same frame.
            if (action.type == HandActionType.SMALL_BLIND || action.type == HandActionType.BIG_BLIND) continue
            val stageAfter = actions.getOrNull(i + 1)?.stage ?: stage
            busyUntil += if (stageAfter != action.stage) streetsDealtMs(action.stage, stageAfter) else actionMs(action)
        }
        return PresentationCursor(actionsSeen = actions.size, busyUntil = busyUntil)
    }

    /** How long a turn armed at [now] should wait before its countdown starts, given the [advanced] cursor. */
    fun headStartMs(advanced: PresentationCursor, now: Long): Long =
        (advanced.busyUntil - now).coerceIn(0L, MAX_HEAD_START_MS)

    /** Two cards to each player dealt in, one at a time, then the crossfade to the real cards. */
    private fun dealMs(dealtIn: Int): Long =
        if (dealtIn < 2) 0L
        else (dealtIn * 2 - 1) * DEAL_INTERVAL_MS + DEAL_FLIGHT_MS + DEAL_CROSSFADE_PAD_MS + STAGE_BEAT_MS

    /** A check moves no chips and gets no callout; every other action does. */
    private fun actionMs(action: HandAction): Long = if (action.type == HandActionType.CHECK) 0L else ACTION_BEAT_MS

    /** The board cards dealt going from [from] to [to]: three for the flop, one each for the turn and river. */
    private fun streetsDealtMs(from: PokerRoundStage, to: PokerRoundStage): Long {
        val cards = mapOf(PokerRoundStage.BET_FLOP to 3, PokerRoundStage.BET_TURN to 1, PokerRoundStage.BET_RIVER to 1)
        return cards.filterKeys { it > from && it <= to }.values.sumOf { streetMs(it) }
    }

    private fun streetMs(cards: Int): Long =
        DECK_DELAY_MS + SLIDE_MS + FLIP_PAUSE_MS + (cards - 1) * FLIP_STAGGER_MS + FLIP_HALF_MS * 2 +
            STREET_SETTLE_PAD_MS + STAGE_BEAT_MS
}
