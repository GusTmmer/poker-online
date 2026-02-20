package com.gustmmer.poker.round

enum class PokerRoundStage {
    INIT,
    BET_BLINDS,
    BET_FLOP,
    BET_TURN,
    BET_RIVER,
    SHOWDOWN,
    ;

    companion object {
        private val betStages = setOf(BET_BLINDS, BET_FLOP, BET_TURN, BET_RIVER)
    }

    fun next() = when (this) {
        INIT -> BET_BLINDS
        BET_BLINDS -> BET_FLOP
        BET_FLOP -> BET_TURN
        BET_TURN -> BET_RIVER
        BET_RIVER -> SHOWDOWN
        SHOWDOWN -> SHOWDOWN
    }

    fun isBettingRound() = this in betStages

    fun isLastBettingRound() = this == BET_RIVER
}