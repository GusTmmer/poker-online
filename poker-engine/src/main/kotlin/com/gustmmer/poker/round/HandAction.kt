package com.gustmmer.poker.round

import kotlinx.serialization.Serializable

@Serializable
enum class HandActionType {
    SMALL_BLIND,
    BIG_BLIND,
    FOLD,
    CHECK,
    CALL,

    /** The first chips into a post-flop street that had no bet yet. */
    BET,
    RAISE,
    ALL_IN,
}

/**
 * One public action in a hand — what everyone at the table saw happen. [amount] is the chips the action
 * put in; [raised] is true when it increased the street's bet (a bet, a raise, or an all-in above the call).
 */
@Serializable
data class HandAction(
    val playerId: Int,
    val stage: PokerRoundStage,
    val type: HandActionType,
    val amount: Int = 0,
    val raised: Boolean = false,
)
