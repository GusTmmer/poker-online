package com.gustmmer.poker

import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
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

    val currentState: PokerTableState
        get() = state

    companion object {
        fun new(
            id: Int = Random.nextInt(0..Int.MAX_VALUE),
            firstPlayer: Player,
            config: TableConfig,
            persistence: PokerTablePersistence,
        ): PokerTable {
            firstPlayer.addChips(config.startingChips)
            val players = mutableListOf(firstPlayer)
            val state = PokerTableState(
                id = id,
                players = players,
                playerOrdering = PlayerOrdering.forNewTable(players),
                blinds = Blinds(big = config.startingChips / 50, small = config.startingChips / 100),
                roundState = null,
                config = config,
            )
            val table = PokerTable(state, persistence)
            table.saveState()
            return table
        }

        fun restore(
            id: Int,
            persistence: PokerTablePersistence,
        ): PokerTable? {
            return persistence.loadState(id)?.let { PokerTable(it, persistence) }
        }
    }

    fun newPokerRound() {
        check(state.roundState == null) { "A round is already in progress" }

        val roundPlayers = state.players.participating()
        check(roundPlayers.size >= 2) { "Need at least 2 non-eliminated players to start a round" }

        playerOrdering = playerOrdering.forCurrentHand(roundPlayers)

        val roundState = PokerRoundState.forNewRound(
            Deck.shuffled(),
            state.blinds,
            roundPlayers,
            playerOrdering,
        )

        val initializedRoundState = PokerRound(roundState).start()

        state = state.copy(
            playerOrdering = playerOrdering,
            roundState = initializedRoundState,
            initialPlayerCount = if (state.initialPlayerCount == 0) roundPlayers.size else state.initialPlayerCount,
        )
        saveState()
    }

    fun advancePlayerOrdering() {
        val participatingPlayers = state.players.participating()
        playerOrdering = playerOrdering.forNextHand(participatingPlayers)

        val newRoundsSinceEscalation = state.roundsSinceLastEscalation + 1
        val handsPerEscalation = state.initialPlayerCount * state.config.blindEscalationOrbits

        if (handsPerEscalation > 0 && newRoundsSinceEscalation >= handsPerEscalation) {
            val multiplier = state.config.blindEscalationMultiplier
            state = state.copy(
                playerOrdering = playerOrdering,
                blinds = Blinds(
                    big = (state.blinds.big * multiplier).roundToInt(),
                    small = (state.blinds.small * multiplier).roundToInt(),
                ),
                roundsSinceLastEscalation = 0,
            )
        } else {
            state = state.copy(
                playerOrdering = playerOrdering,
                roundsSinceLastEscalation = newRoundsSinceEscalation,
            )
        }
        saveState()
    }

    fun processPlayerCommand(command: PlayerCommand) {
        if (state.roundState == null) return

        val pokerRound = PokerRound(state.roundState!!)
        val newRoundState = pokerRound.processCommand(command)

        state = state.copy(roundState = newRoundState)

        checkForEliminations()
        saveState()
    }

    /**
     * Resolves an idle or timed-out player's turn.
     * OFFLINE players always fold.
     * IDLE players check if possible, otherwise fold.
     */
    fun autoPlayForCurrentPlayer(): PlayerCommand? {
        val roundState = state.roundState ?: return null
        val bettingState = roundState.bettingRoundState ?: return null
        val currentPlayer = roundState.playerOrdering.bettingPlayer()

        val command = when (currentPlayer.status) {
            PlayerStatus.OFFLINE -> Fold(currentPlayer.id)
            PlayerStatus.IDLE -> {
                if (bettingState.pot.chipsToMatchCurrentBet(currentPlayer) == 0) {
                    Call(currentPlayer.id)
                } else {
                    Fold(currentPlayer.id)
                }
            }

            else -> return null
        }

        processPlayerCommand(command)
        return command
    }

    fun playerJoin(player: Player): Boolean {
        if (!state.config.isOpen) return false
        if (state.players.size >= state.config.maxPlayers) return false

        player.addChips(state.config.startingChips)
        state.players.add(player)
        saveState()
        return true
    }

    // TODO: Add route to cleanly leave, possibly triggered by 'on_window_close' event in FE.
    //  Evaluate if websocket getting closed is sufficient for this.
    fun playerLeave(playerId: Int) {
        val player = state.players.find { it.id == playerId } ?: return
        player.setAsOffline()
        saveState()
    }

    fun kickPlayer(playerId: Int) {
        val player = state.players.find { it.id == playerId } ?: return

        // TODO: This may be broken. Player cannot fold if not their turn.
        if (state.roundState != null && player.isActive()) {
            player.fold()
        }

        state.players.remove(player)
        saveState()
    }

    fun restartGame() {
        check(state.roundState == null) { "Cannot restart while a round is in progress" }

        state.players.forEach { player ->
            player.removeChips(player.chips)
            player.addChips(state.config.startingChips)
            player.setAsOnline()
        }

        val initialBlinds = Blinds(
            big = state.config.startingChips / 50,
            small = state.config.startingChips / 100,
        )

        state = state.copy(
            blinds = initialBlinds,
            playerOrdering = PlayerOrdering.forNewTable(state.players),
            roundsSinceLastEscalation = 0,
            initialPlayerCount = 0,
        )
        saveState()
    }

    fun pause(remainingTimerMs: Long?) {
        state = state.copy(isPaused = true, turnTimeRemainingMs = remainingTimerMs)
        saveState()
    }

    fun unpause(): Long? {
        val remaining = state.turnTimeRemainingMs
        state = state.copy(isPaused = false, turnTimeRemainingMs = null)
        saveState()
        return remaining
    }

    fun updateConfig(isOpen: Boolean) {
        state = state.copy(config = state.config.copy(isOpen = isOpen))
        saveState()
    }

    fun clearRoundState() {
        state = state.copy(roundState = null)
        saveState()
    }

    private fun checkForEliminations() {
        state.players
            .filter { it.chips == 0 && it.isParticipating() }
            .forEach { it.setAsEliminated() }
    }

    // TODO: Evaluate making 'saveState' explicit for performance and transactional reasons.
    private fun saveState() {
        state = state.copy(version = state.version + 1)
        persistence.saveState(state)
    }
}
