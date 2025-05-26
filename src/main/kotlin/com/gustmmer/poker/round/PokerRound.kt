package com.gustmmer.poker.round

import com.gustmmer.poker.Player
import com.gustmmer.poker.active
import com.gustmmer.poker.canMoreThanOneBet
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import com.gustmmer.poker.hand.rankings.PokerHand
import com.gustmmer.poker.onlyOneIsActive
import kotlin.math.min


class PokerRound(state: PokerRoundState) {

    private var state = state.copy()

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

    fun start(): PokerRoundState {
        assert(state.pokerRoundStage == PokerRoundStage.INIT)

        takeBlinds()
        dealCards()

        return state.toBets()
    }

    fun processCommand(playerCommand: PlayerCommand): PokerRoundState {
        assert(state.pokerRoundStage.isBettingRound())

        processPlayerCommandForBettingRound(playerCommand)

        if (state.pokerRoundStage == PokerRoundStage.SHOWDOWN) {
            showdown()
            println("Final balance: $players")
        }

        return state
    }

    private fun takeBlinds() {
        with(state) {
            playerOrdering.smallBlindPlayer().let { pot.addPlayerChips(it, min(it.chips, blinds.small)) }
            playerOrdering.bigBlindPlayer().let { pot.addPlayerChips(it, min(it.chips, blinds.big)) }
        }
    }

    private fun dealCards() {
        players.forEach { p -> p.setPocketCards(deck.draw(2)) }
    }

    private fun revealCommunityCards(cardCount: Int) {
        if (cardCount == 0) {
            return
        }
        println("Revealed $cardCount more card(s)")
        communityCards.addAll(deck.draw(cardCount))
    }

    private fun processPlayerCommandForBettingRound(playerCommand: PlayerCommand) {
        if (!state.pokerRoundStage.isBettingRound()) {
            throw IllegalStateException("Round is not in betting stage")
        }

        val newBettingRoundState =
            BettingRoundCoordinator(state.bettingRoundState!!, players, state.playerOrdering, state.blinds)
                .processPlayerCommand(playerCommand)

        if (newBettingRoundState.isComplete) {
            processBettingRoundResultingPot(newBettingRoundState)
        }

        updateRoundStateWithBettingResult(newBettingRoundState)
    }

    private fun processBettingRoundResultingPot(newBettingState: BettingRoundState) {
        pot.mergeBetsFromPot(newBettingState.pot)

        while (true) {
            pot.sidePotOrNull()?.let(pots::add) ?: break
        }

        pots.filter { pot -> pot.activePlayerCount() == 1 }.takeIf { it.isNotEmpty() }?.let { autoResolvedPots ->
            println("Resolving pot preemptively: $autoResolvedPots")
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

        println("Showdown")
        println("Community: $communityCards")

        val highestPokerHands = players
            .active()
            .map { it to TexasHoldEmHandEvaluator.getMatchingPokerHand(communityCards, it.pocketCards) }
            .sortedByDescending { (_, hand) -> hand }

        println("Poker Hands: $highestPokerHands")

        pots.forEach { resolvePot(it, highestPokerHands) }
    }

    private fun resolvePot(pot: Pot, highestPokerHands: List<Pair<Player, PokerHand>>) {
        val pokerHandsInPot = highestPokerHands.filter { (player, _) -> pot.hasPlayerBet(player) }
        val (_, winningHand) = pokerHandsInPot.first()

        val winningPlayers = pokerHandsInPot
            .takeWhile { (_, hand) -> hand.compareTo(winningHand) == 0 }
            .map { (player, _) -> player }
            .toSet()

        val chipsWonByEachPlayer = pot.chipsWonByEachWinner(winningPlayers)

        winningPlayers.forEach { it.addChips(chipsWonByEachPlayer) }

        println("$winningPlayers won $chipsWonByEachPlayer with a ${winningHand.ranking}")
    }
}
