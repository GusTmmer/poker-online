package com.gustmmer.poker.round

import com.gustmmer.poker.*
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import com.gustmmer.poker.hand.rankings.PokerHand
import kotlin.math.min

open class PokerRound(
    private val deck: Deck,
    private val roundSpec: PokerRoundSpec,
    private val playerIO: PlayerIO,
) {
    protected constructor(pokerRound: PokerRound) : this(
        pokerRound.deck,
        pokerRound.roundSpec,
        pokerRound.playerIO
    )

    private val pots = mutableListOf(Pot.mainPot(roundSpec.blinds))
    private val pot
        get() = pots.last()

    private val players
        get() = roundSpec.players

    private val communityCards = mutableListOf<Card>()

    open suspend fun execute() {
        takeBlinds()

        dealCards()

        bettingRounds()

        showdown()

        println("Final balance: $players")
    }

    private fun takeBlinds() {
        with(roundSpec) {
            playerOrdering.smallBlindPlayer().let { pot.addPlayerChips(it, min(it.chips, blinds.small)) }
            playerOrdering.bigBlindPlayer().let { pot.addPlayerChips(it, min(it.chips, blinds.big)) }
        }
    }

    private fun dealCards() {
        players.forEach { p -> p.setPocketCards(deck.draw(2)) }
    }

    private fun communityCardReveal(cardCount: Int) {
        println("Revealed $cardCount more card(s)")
        communityCards.addAll(deck.draw(cardCount))
    }

    private suspend fun bettingRounds() {
        // Blinds
        betting(pot, roundSpec)
        if (players.hasSingleActive()) {
            return
        }

        // Flop
        communityCardReveal(3)
        if (players.canMoreThanOneBet()) {
            betting(Pot.bettingPot(), roundSpec.withNewPlayerOrdering { forNextBettingRound() })
            if (players.hasSingleActive()) {
                return
            }
        }

        // Turn
        communityCardReveal(1)
        if (players.canMoreThanOneBet()) {
            betting(Pot.bettingPot(), roundSpec.withNewPlayerOrdering { forNextBettingRound() })
            if (players.hasSingleActive()) {
                return
            }
        }

        // River
        communityCardReveal(1)
        if (players.canMoreThanOneBet()) {
            betting(Pot.bettingPot(), roundSpec.withNewPlayerOrdering { forNextBettingRound() })
        }
    }

    protected open suspend fun betting(bettingPot: Pot, bettingRoundSpec: PokerRoundSpec) {
        println("New betting round")

        BettingRoundCoordinator(bettingRoundSpec, bettingPot, playerIO).handleBetting()

        pot.mergeBetsFromPot(bettingPot)

        while (true) {
            pot.sidePotOrNull()?.let(pots::add) ?: break
        }

        pots.filter { pot -> pot.activePlayerCount() == 1 }.takeIf { it.isNotEmpty() }?.let { autoResolvedPots ->
            println("Resolving pot preemptively: $autoResolvedPots")
            autoResolvedPots.forEach(Pot::resolveWinnerWithSingleActivePlayer)
            pots.removeAll(autoResolvedPots)
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
