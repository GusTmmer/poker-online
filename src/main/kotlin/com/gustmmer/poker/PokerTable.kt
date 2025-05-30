package com.gustmmer.poker

import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.PlayerCommand
import com.gustmmer.poker.round.PlayerOrdering
import com.gustmmer.poker.round.PokerRound
import com.gustmmer.poker.round.PokerRoundState
import kotlinx.serialization.Serializable
import kotlin.random.Random
import kotlin.random.nextInt

@Serializable
data class Blinds(val big: Int, val small: Int)

class PokerTable(
    private var state: PokerTableState,
    private val persistence: PokerTablePersistence,
) {
    private var playerOrdering = state.playerOrdering

    val dealer: Player
        get() = playerOrdering.dealer()

    val id: Int
        get() = state.id

    companion object {
        fun new(
            id: Int = Random.nextInt(0..Int.MAX_VALUE),
            players: List<Player>,
            blinds: Blinds,
            ruleSet: RuleSet,
            persistence: PokerTablePersistence,
        ): PokerTable {
            val state = PokerTableState(
                id = id,
                players = players.toMutableList(),
                playerOrdering = PlayerOrdering.forNewTable(players),
                blinds = blinds,
                roundState = null
            )
            return PokerTable(state, persistence)
        }

        fun restore(
            id: Int,
            persistence: PokerTablePersistence
        ): PokerTable? {
            return persistence.loadState(id)?.let { PokerTable(it, persistence) }
        }
    }

    fun advancePlayerOrdering() {
        playerOrdering = playerOrdering.forNextHand(state.players.toList())
    }

    fun newPokerRound() {
        val roundPlayers = state.players.toList()

        playerOrdering = playerOrdering.forSameHand(roundPlayers)

        val roundState = PokerRoundState.forNewRound(
            Deck.shuffled(),
            state.blinds,
            roundPlayers,
            playerOrdering,
        )

        val initializedRoundState = PokerRound(roundState).start()

        state = state.copy(
            playerOrdering = playerOrdering,
            roundState = initializedRoundState
        )
        saveState()
    }

    fun processPlayerCommand(command: PlayerCommand) {
        if (state.roundState == null) {
            return
        }

        val pokerRound = PokerRound(state.roundState!!)
        val newRoundState = pokerRound.processCommand(command)

        state = state.copy(roundState = newRoundState)
        saveState()
    }

    private fun saveState() {
        persistence.saveState(state)
    }

    fun playerJoin(player: Player) {
        state.players.add(player)
    }

    fun playerLeave(player: Player) {
        // TODO: Update round state if it's depending on player
        state.players.remove(player)
        player.setAsOffline()

        // TODO: Maybe remove this. Currently only important for test.
        playerOrdering = playerOrdering.forSameHand(state.players.toList())
    }
}