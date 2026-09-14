package com.gustmmer.poker.bot

import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.round.BettingOptions
import com.gustmmer.poker.round.HandAction
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.PokerRoundState

enum class Position { EARLY, MIDDLE, LATE, BLINDS }

/** What a bot can see about an opponent still in the hand. */
data class OpponentView(val playerId: Int, val chips: Int)

/**
 * Everything a computer player is allowed to know when it acts — and the *only* input to
 * [BotStrategy]. It is the information barrier: built from the round state, it copies out the bot's own
 * pocket cards, the face-up board and what happened in public (bets, stacks, positions, the action log),
 * and holds no deck, no [Player] references and no other player's cards.
 */
data class BotView(
    val playerId: Int,
    val pocketCards: List<Card>,
    val communityCards: List<Card>,
    val stage: PokerRoundStage,
    /** Every chip in the middle, including this street's bets. */
    val potTotal: Int,
    /** The street's current bet (what the bot must match in total). */
    val streetBet: Int,
    val options: BettingOptions,
    val bigBlind: Int,
    val chips: Int,
    val position: Position,
    val opponents: List<OpponentView>,
    val actions: List<HandAction>,
) {
    val isPreflop get() = stage == PokerRoundStage.BET_BLINDS
    val toCall get() = options.amountToCall
    val stackInBigBlinds get() = (chips + streetContribution()).toDouble() / bigBlind

    /** Raises on the current street so far. */
    val raisesThisStreet get() = actions.count { it.stage == stage && it.raised }

    /** Pre-flop and nobody has raised: only blinds (and limps) in. */
    val unopened get() = isPreflop && raisesThisStreet == 0

    /** This bot made the last pre-flop raise. */
    val isPreflopAggressor get() =
        actions.lastOrNull { it.stage == PokerRoundStage.BET_BLINDS && it.raised }?.playerId == playerId

    /** An opponent is all-in for more than the bot has already put in. */
    val facingAllIn get() = toCall > 0 && actions.any { it.stage == stage && it.type == HandActionType.ALL_IN && it.playerId != playerId }

    private fun streetContribution() = actions.filter { it.stage == stage && it.playerId == playerId }.sumOf { it.amount }

    companion object {
        fun from(round: PokerRoundState, me: Player): BotView {
            val betting = checkNotNull(round.bettingRoundState) { "No betting round in progress" }
            val streetPot = betting.pot
            val streetPotIsSeparate = round.pots.none { it === streetPot }
            val potTotal = round.pots.sumOf { it.totalBets() } + if (streetPotIsSeparate) streetPot.totalBets() else 0

            return BotView(
                playerId = me.id,
                pocketCards = me.pocketCards.toList(),
                communityCards = round.communityCards.toList(),
                stage = round.pokerRoundStage,
                potTotal = potTotal,
                streetBet = streetPot.currentBet(),
                options = betting.optionsFor(me, round.players, round.blinds),
                bigBlind = round.blinds.big,
                chips = me.chips,
                position = positionOf(round, me),
                opponents = round.players
                    .filter { it !== me && it.isActive() }
                    .map { OpponentView(it.id, it.chips) },
                actions = round.actions.toList(),
            )
        }

        /**
         * Seat relative to the button. Heads-up, the button (who is also the small blind) is LATE and the
         * other seat is BLINDS. Otherwise the two seats after the button are BLINDS, the button and the
         * cutoff are LATE, and the seats from under-the-gun to the cutoff split into EARLY then MIDDLE.
         */
        internal fun positionOf(round: PokerRoundState, me: Player): Position {
            val seats = round.players
            val n = seats.size
            val dealerIdx = seats.indexOfFirst { it === round.playerOrdering.dealer() }
            val offset = (seats.indexOfFirst { it === me } - dealerIdx + n) % n
            if (n == 2) return if (offset == 0) Position.LATE else Position.BLINDS
            return when {
                offset == 0 || offset == n - 1 -> Position.LATE
                offset <= 2 -> Position.BLINDS
                // Seats from under-the-gun (offset 3) up to, excluding, the cutoff.
                (offset - 3) < (n - 4) / 2.0 -> Position.EARLY
                else -> Position.MIDDLE
            }
        }
    }
}
