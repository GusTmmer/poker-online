package com.gustmmer.poker

import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.round.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED

data class Blinds(val big: Int, val small: Int)

open class PokerTable protected constructor(
    private val players: MutableList<Player>,
    private var blinds: Blinds,
    private val ruleSet: RuleSet,
) {
    private val initialBlinds = blinds.copy()

    val dealer: Player
        get() = playerOrdering.dealer()

    private var playerOrdering = PlayerOrdering.forNewTable(players)
    private var roundPlayers: List<Player> = players

    private val playerCommandChannel: Channel<PlayerCommand> = Channel(UNLIMITED)
    private val systemCommandChannel: Channel<SystemCommand> = Channel(UNLIMITED)
    private val events = Channel<Any>(UNLIMITED)

    private val playerIO = PlayerIO(playerCommandChannel, systemCommandChannel, events)

    companion object {
        fun new(players: List<Player>, blinds: Blinds, ruleSet: RuleSet): PokerTable {
            return PokerTable(players.toMutableList(), blinds, ruleSet)
        }
    }

    suspend fun executeNewPokerRound() {
        createNewPokerRound().execute()

        // TODO: Logic for when player has no more chips. (Buy-back as part of RuleSet?)

        roundPlayers = players.toList()
        playerOrdering = playerOrdering.forNextHand(roundPlayers)
    }

    fun playerJoin(player: Player) {
        players.add(player)
    }

    fun playerLeave(player: Player) {
        players.remove(player)
        player.setAsOffline()
        playerCommandChannel.trySend(Fold(player))

        roundPlayers = players.toList()
        playerOrdering = playerOrdering.forSameHand(roundPlayers)
    }

    protected open fun createNewPokerRound(): PokerRound {
        return PokerRound(Deck.shuffled(), PokerRoundSpec(roundPlayers, playerOrdering, blinds), playerIO)
    }
}