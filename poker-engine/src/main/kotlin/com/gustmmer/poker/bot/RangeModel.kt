package com.gustmmer.poker.bot

import com.gustmmer.poker.round.HandAction
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.PokerRoundStage

/**
 * Reads an opponent's likely starting-hand range off their public actions this hand, as a
 * [PreflopChart] percentile ceiling. Someone who re-raised and keeps betting doesn't hold a random hand —
 * treating them as if they did is the classic way simple equity bots pay off every big bet.
 */
object RangeModel {

    private const val ANY_TWO = 1.0
    private const val LIMP_OR_CALL = 0.45
    private const val OPEN_RAISE = 0.20
    private const val SHORT_ALL_IN = 0.30
    private const val RE_RAISE = 0.07
    private const val FOUR_BET = 0.035

    private const val POSTFLOP_RAISE_FACTOR = 0.6
    private const val POSTFLOP_CALL_FACTOR = 0.85
    private const val FLOOR = 0.03

    fun ceilingFor(playerId: Int, actions: List<HandAction>): Double {
        var ceiling = ANY_TWO
        var raisesSoFar = 0
        for (action in actions) {
            if (action.stage == PokerRoundStage.BET_BLINDS) {
                if (action.playerId == playerId) {
                    ceiling = minOf(ceiling, preflopCeiling(action, raisesSoFar))
                }
                if (action.raised) raisesSoFar++
            } else if (action.playerId == playerId) {
                when {
                    action.raised -> ceiling *= POSTFLOP_RAISE_FACTOR
                    action.type == HandActionType.CALL -> ceiling *= POSTFLOP_CALL_FACTOR
                }
            }
        }
        return ceiling.coerceAtLeast(FLOOR)
    }

    private fun preflopCeiling(action: HandAction, raisesBefore: Int): Double = when {
        action.type == HandActionType.SMALL_BLIND || action.type == HandActionType.BIG_BLIND -> ANY_TWO
        action.type == HandActionType.CHECK || action.type == HandActionType.FOLD -> ANY_TWO
        !action.raised -> if (raisesBefore == 0) LIMP_OR_CALL else OPEN_RAISE // calling a raise
        action.type == HandActionType.ALL_IN && raisesBefore == 0 -> SHORT_ALL_IN
        raisesBefore == 0 -> OPEN_RAISE
        raisesBefore == 1 -> RE_RAISE
        else -> FOUR_BET
    }
}
