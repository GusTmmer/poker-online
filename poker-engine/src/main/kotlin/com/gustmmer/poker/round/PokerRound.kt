package com.gustmmer.poker.round

import com.gustmmer.poker.Player
import com.gustmmer.poker.active
import com.gustmmer.poker.canMoreThanOneBet
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import com.gustmmer.poker.hand.rankings.PokerHand
import com.gustmmer.poker.onlyOneIsActive
import org.slf4j.LoggerFactory
import kotlin.math.min


class PokerRound(state: PokerRoundState) {

    private var state = state.copy()

    private companion object {
        private val log = LoggerFactory.getLogger(PokerRound::class.java)
    }

    private val players
        get() = state.players

    private val deck
        get() = state.deck

    private val pots
        get() = state.pots

    private val pot
        get() = pots.last()

    private val communityCards
        get() = state.communityCards

    /**
     * Posts blinds, deals, and opens pre-flop betting. Short stacks can leave nobody with a decision
     * (e.g. heads-up, the small blind is all-in for less than the big blind): the hand then runs out
     * straight to showdown here instead of parking the turn on a player with no chips.
     */
    fun start(): PokerRoundState {
        assert(state.pokerRoundStage == PokerRoundStage.INIT)

        takeBlinds()
        dealCards()

        state = state.toFirstBettingStage()
        applyBettingResult(coordinator().settle())
        finishIfShowdown()

        return state
    }

    fun processCommand(playerCommand: PlayerCommand): PokerRoundState {
        if (!state.pokerRoundStage.isBettingRound()) {
            throw IllegalStateException("Round is not in betting stage")
        }

        // Classified before it's applied; recorded only once the coordinator has accepted it.
        val action = describe(playerCommand)
        applyBettingResult(coordinator().processPlayerCommand(playerCommand))
        action?.let(::record)
        finishIfShowdown()

        return state
    }

    /**
     * Folds [playerId] whether or not it is their turn — used when a player leaves or is kicked
     * mid-hand. A no-op outside betting or for a player who is not in the hand.
     */
    fun forceFold(playerId: Int): PokerRoundState {
        if (!state.pokerRoundStage.isBettingRound()) return state
        val player = players.find { it.id == playerId } ?: return state
        if (!player.isActive()) return state

        if (state.playerOrdering.bettingPlayer() === player) {
            return processCommand(Fold(playerId))
        }

        val stage = state.pokerRoundStage
        applyBettingResult(coordinator().processOutOfTurnFold(player))
        record(HandAction(playerId, stage, HandActionType.FOLD))
        finishIfShowdown()
        return state
    }

    private fun coordinator() =
        BettingRoundCoordinator(state.bettingRoundState!!, players, state.playerOrdering, state.blinds)

    private fun finishIfShowdown() {
        if (state.pokerRoundStage == PokerRoundStage.SHOWDOWN) {
            showdown()
            log.debug("Final balance: {}", players)
        }
    }

    private fun takeBlinds() {
        with(state) {
            postBlind(playerOrdering.smallBlindPlayer(), blinds.small, HandActionType.SMALL_BLIND)
            postBlind(playerOrdering.bigBlindPlayer(), blinds.big, HandActionType.BIG_BLIND)
        }
    }

    private fun postBlind(player: Player, blind: Int, type: HandActionType) {
        val chips = min(player.chips, blind)
        pot.addPlayerChips(player, chips)
        record(HandAction(player.id, PokerRoundStage.BET_BLINDS, type, chips))
    }

    /**
     * Classifies [command] against the street as it stands *before* the command is applied. Null for a
     * player who isn't in the hand — the coordinator rejects that command as out of turn.
     */
    private fun describe(command: PlayerCommand): HandAction? {
        val stage = state.pokerRoundStage
        val player = players.firstOrNull { it.id == command.playerId } ?: return null
        val streetPot = state.bettingRoundState!!.pot
        val toCall = streetPot.chipsToMatchCurrentBet(player)
        val streetHasBet = streetPot.currentBet() > 0
        return when (command) {
            is Fold -> HandAction(player.id, stage, HandActionType.FOLD)
            is Call -> when (toCall) {
                0 -> HandAction(player.id, stage, HandActionType.CHECK)
                else -> HandAction(player.id, stage, HandActionType.CALL, min(toCall, player.chips))
            }
            is Raise -> HandAction(
                player.id, stage,
                if (streetHasBet) HandActionType.RAISE else HandActionType.BET,
                toCall + command.value, raised = true,
            )
            is AllIn -> HandAction(player.id, stage, HandActionType.ALL_IN, player.chips, raised = player.chips > toCall)
        }
    }

    private fun record(action: HandAction) {
        state = state.copy(actions = state.actions + action)
    }

    private fun dealCards() {
        players.forEach { p -> p.setPocketCards(deck.draw(2)) }
    }

    private fun revealCommunityCards(cardCount: Int) {
        if (cardCount == 0) {
            return
        }
        log.debug("Revealed {} more card(s)", cardCount)
        communityCards.addAll(deck.draw(cardCount))
    }

    /**
     * Folds a street's result into the round. A finished street advances the stage; each fresh street is
     * settled in turn so its first bettor is a player who can actually act.
     */
    private fun applyBettingResult(bettingResult: BettingRoundState) {
        var result = bettingResult
        while (true) {
            if (result.isComplete) {
                processBettingRoundResultingPot(result)
            }
            updateRoundStateWithBettingResult(result)

            if (!result.isComplete || !state.pokerRoundStage.isBettingRound()) return
            result = coordinator().settle()
        }
    }

    private fun processBettingRoundResultingPot(newBettingState: BettingRoundState) {
        pot.mergeBetsFromPot(newBettingState.pot)

        // Everyone else folded: the last player standing takes every pot, including ones they never
        // put chips into (possible when the others were folded out of turn).
        if (players.onlyOneIsActive()) {
            val winner = players.first(Player::isActive)
            log.debug("Resolving pots preemptively for {}: {}", winner, pots)
            pots.forEach { it.distributeToWinners(setOf(winner)) }
            pots.clear()
            return
        }

        while (true) {
            pot.sidePotOrNull()?.let(pots::add) ?: break
        }

        pots.filter { pot -> pot.activePlayerCount() == 1 }.takeIf { it.isNotEmpty() }?.let { autoResolvedPots ->
            log.debug("Resolving pot preemptively: {}", autoResolvedPots)
            autoResolvedPots.forEach(Pot::resolveWinnerWithSingleActivePlayer)
            pots.removeAll(autoResolvedPots)
        }
    }

    private fun updateRoundStateWithBettingResult(bettingRoundResult: BettingRoundState) {
        if (!bettingRoundResult.isComplete) {
            state = state.copy(bettingRoundState = bettingRoundResult)
            return
        }

        if (players.onlyOneIsActive()) {
            state = state.toShowdown()
            return
        }

        if (!players.canMoreThanOneBet()) {
            revealCommunityCards(5 - communityCards.size)
            state = state.toShowdown()
            return
        }

        if (state.pokerRoundStage.isLastBettingRound()) {
            state = state.toShowdown()
        } else {
            state = state.toNextBettingStage()
            revealCommunityCards(cardCountToRevealForRoundStage(state.pokerRoundStage))
        }
    }

    private fun cardCountToRevealForRoundStage(pokerRoundStage: PokerRoundStage): Int {
        return when (pokerRoundStage) {
            PokerRoundStage.BET_FLOP -> 3
            PokerRoundStage.BET_TURN -> 1
            PokerRoundStage.BET_RIVER -> 1
            else -> 0
        }
    }

    private fun showdown() {
        if (pots.sumOf(Pot::totalBets) == 0) {
            return
        }

        log.debug("Showdown")
        log.debug("Community: {}", communityCards)

        val highestPokerHands = players
            .active()
            .map { it to TexasHoldEmHandEvaluator.getMatchingPokerHand(communityCards, it.pocketCards) }
            .sortedByDescending { (_, hand) -> hand }

        log.debug("Poker Hands: {}", highestPokerHands)

        pots.forEach { resolvePot(it, highestPokerHands) }
    }

    private fun resolvePot(pot: Pot, highestPokerHands: List<Pair<Player, PokerHand>>) {
        val pokerHandsInPot = highestPokerHands.filter { (player, _) -> pot.hasPlayerBet(player) }
        val (_, winningHand) = pokerHandsInPot.first()

        val winningPlayers = pokerHandsInPot
            .takeWhile { (_, hand) -> hand.compareTo(winningHand) == 0 }
            .map { (player, _) -> player }
            .toSet()

        pot.distributeToWinners(winningPlayers)

        log.debug("{} won pot with a {}", winningPlayers, winningHand.ranking)
    }
}
