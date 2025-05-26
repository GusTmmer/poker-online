package com.gustmmer.poker

import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.round.PlayerCommand
import com.gustmmer.poker.round.PlayerOrdering
import com.gustmmer.poker.round.PokerRound
import com.gustmmer.poker.round.PokerRoundState

data class Blinds(val big: Int, val small: Int)

class PokerTable(
    private val players: MutableList<Player>,
    private var blinds: Blinds,
    private val ruleSet: RuleSet,
) {
    val dealer: Player
        get() = playerOrdering.dealer()

    private var playerOrdering = PlayerOrdering.forNewTable(players)

    private lateinit var roundState: PokerRoundState

    companion object {
        fun new(players: List<Player>, blinds: Blinds, ruleSet: RuleSet): PokerTable {
            return PokerTable(players.toMutableList(), blinds, ruleSet)
        }
    }

    fun newPokerRound() {
        val roundPlayers = players.toList()

        playerOrdering = playerOrdering.forNextHand(roundPlayers)

        val roundState = PokerRoundState.forNewRound(
            Deck.shuffled(),
            blinds,
            roundPlayers,
            playerOrdering
        )

        this.roundState = PokerRound(roundState).start()
        // TODO: Save 'roundState'.
    }

    fun processPlayerCommand(command: PlayerCommand) {
        // TODO: Load 'roundState'

        val pokerRound = PokerRound(roundState)
        roundState = pokerRound.processCommand(command)

        // TODO: Save 'roundState'.
    }

    fun playerJoin(player: Player) {
        players.add(player)
    }

    fun playerLeave(player: Player) {
        // TODO: Update round state if it's depending on player
        players.remove(player)
        player.setAsOffline()

        // TODO: Maybe remove this. Currently only important for test.
        playerOrdering = playerOrdering.forSameHand(players.toList())
    }
}