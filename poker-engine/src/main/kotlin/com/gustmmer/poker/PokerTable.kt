package com.gustmmer.poker

import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.nextInt

@Serializable
data class Blinds(val big: Int, val small: Int) {
    companion object {
        /** Initial blinds derived from the starting stack: big = 2%, small = 1%. */
        fun initial(startingChips: Int) = Blinds(big = startingChips / 50, small = startingChips / 100)
    }
}

class PokerTable(
    private var state: PokerTableState,
    private val persistence: PokerTablePersistence,
) {
    private var playerOrdering = state.playerOrdering

    /**
     * Set by every mutator, cleared by [commit]. Lets the transaction boundary skip a write when a
     * block restored the table but changed nothing (e.g. a validation that bailed out, or a no-op
     * status flip), avoiding pointless version bumps and the spurious conflicts they'd cause.
     */
    private var dirty = false
    val isDirty: Boolean get() = dirty

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
                blinds = Blinds.initial(config.startingChips),
                roundState = null,
                config = config,
            )
            val table = PokerTable(state, persistence)
            table.dirty = true
            table.commit()
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

        val newRoundsSinceEscalation = state.roundsSinceLastEscalation + 1
        val handsPerEscalation = state.initialPlayerCount * state.config.blindEscalationOrbits
        val newBlinds: Blinds
        val newRoundsSince: Int
        if (handsPerEscalation > 0 && newRoundsSinceEscalation >= handsPerEscalation) {
            val multiplier = state.config.blindEscalationMultiplier
            newBlinds = Blinds(
                big = (state.blinds.big * multiplier).roundToInt(),
                small = (state.blinds.small * multiplier).roundToInt(),
            )
            newRoundsSince = 0
        } else {
            newBlinds = state.blinds
            newRoundsSince = newRoundsSinceEscalation
        }

        val roundState = PokerRoundState.forNewRound(Deck.shuffled(), newBlinds, roundPlayers, playerOrdering)
        val initializedRoundState = PokerRound(roundState).start()

        state = state.copy(
            playerOrdering = playerOrdering,
            roundState = initializedRoundState,
            blinds = newBlinds,
            roundsSinceLastEscalation = newRoundsSince,
            gameStatus = GameStatus.RUNNING,
            readyPlayers = emptySet(),
            turnTimerStartedAt = null,
            initialPlayerCount = if (state.initialPlayerCount == 0) roundPlayers.size else state.initialPlayerCount,
        )
        dirty = true
    }

    fun processPlayerCommand(command: PlayerCommand) {
        if (state.roundState == null) return

        val pokerRound = PokerRound(state.roundState!!)
        val newRoundState = pokerRound.processCommand(command)

        state = state.copy(roundState = newRoundState)

        // Only check eliminations after showdown, when the pot has been fully distributed.
        // Calling this mid-round (e.g. after an all-in) would mark players as ELIMINATED
        // before the showdown returns their winnings.
        if (newRoundState.pokerRoundStage == PokerRoundStage.SHOWDOWN) {
            checkForEliminations()
        }
        dirty = true
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
        dirty = true
        return true
    }

    /**
     * Brings an OFFLINE or IDLE [playerId] back ONLINE (staged). ONLINE and ELIMINATED players are
     * left untouched. Returns true if the status actually changed. Compose with [unpause] inside one
     * [withTable] block to activate-and-resume in a single commit.
     */
    fun setPlayerOnline(playerId: Int): Boolean {
        val player = state.players.find { it.id == playerId } ?: return false
        if (player.status != PlayerStatus.OFFLINE && player.status != PlayerStatus.IDLE) return false
        player.setAsOnline()
        dirty = true
        return true
    }

    /**
     * Marks [playerId] OFFLINE (staged). Only transitions an ONLINE player, so a reconnect that
     * already set the player ONLINE on a new session is never clobbered. Returns true if it changed.
     */
    fun setPlayerOffline(playerId: Int): Boolean {
        val player = state.players.find { it.id == playerId } ?: return false
        if (player.status != PlayerStatus.ONLINE) return false
        player.setAsOffline()
        dirty = true
        return true
    }

    fun kickPlayer(playerId: Int) {
        val player = state.players.find { it.id == playerId } ?: return

        val roundState = state.roundState
        if (roundState != null && roundState.pokerRoundStage.isBettingRound() &&
            roundState.playerOrdering.bettingPlayer().id == playerId
        ) {
            autoPlayForCurrentPlayer()
        }

        state.players.remove(player)
        dirty = true
    }

    fun playerReady(playerId: Int): Boolean {
        check(state.gameStatus == GameStatus.WAITING) { "Game is not in waiting state" }
        val player = state.players.find { it.id == playerId } ?: return false
        if (!player.isParticipating()) return false

        state = state.copy(readyPlayers = state.readyPlayers + playerId)
        dirty = true

        return state.players.participating().all { it.id in state.readyPlayers }
    }

    fun restartGame() {
        check(state.roundState == null) { "Cannot restart while a round is in progress" }

        state.players.forEach { player ->
            player.removeChips(player.chips)
            player.addChips(state.config.startingChips)
            player.setAsOnline()
        }

        state = state.copy(
            blinds = Blinds.initial(state.config.startingChips),
            playerOrdering = PlayerOrdering.forNewTable(state.players),
            roundsSinceLastEscalation = 0,
            initialPlayerCount = 0,
            gameStatus = GameStatus.WAITING,
            readyPlayers = emptySet(),
            turnTimerStartedAt = null,
        )
        dirty = true
    }

    fun timerStarted(epochMillis: Long) {
        state = state.copy(turnTimerStartedAt = epochMillis)
        dirty = true
    }

    fun pause(remainingTimerMs: Long?) {
        state = state.copy(
            gameStatus = GameStatus.PAUSED,
            turnTimeRemainingMs = remainingTimerMs,
            turnTimerStartedAt = null,
        )
        dirty = true
    }

    fun unpause(): Long? {
        val remaining = state.turnTimeRemainingMs
        state = state.copy(gameStatus = GameStatus.RUNNING, turnTimeRemainingMs = null)
        dirty = true
        return remaining
    }

    /** Update table-level settings in place; only the non-null arguments are applied. */
    fun updateConfig(isOpen: Boolean? = null, name: String? = null) {
        state = state.copy(
            config = state.config.copy(
                isOpen = isOpen ?: state.config.isOpen,
                name = name ?: state.config.name,
            )
        )
        dirty = true
    }

    fun clearRoundState() {
        val nextPlayers = state.players.participating()
        if (nextPlayers.isNotEmpty()) {
            playerOrdering = playerOrdering.forNextHand(nextPlayers)
        }
        state = state.copy(
            roundState = null,
            playerOrdering = playerOrdering,
            gameStatus = GameStatus.WAITING,
            readyPlayers = emptySet(),
            turnTimerStartedAt = null,
        )
        dirty = true
    }

    /**
     * Opens [vote], replacing any existing vote with the same resolution + target (re-opening supersedes).
     * The engine only stores the tally; eligibility and consequences are the server's concern.
     */
    fun openVote(vote: ActiveVote) {
        val others = state.activeVotes.filterNot {
            it.resolutionType == vote.resolutionType && it.targetPlayerId == vote.targetPlayerId
        }
        state = state.copy(activeVotes = others + vote)
        dirty = true
    }

    /** Records [playerId]'s yes/no on vote [sessionId] (idempotent; flips a prior opposite vote). No-op if absent. */
    fun castVote(sessionId: String, playerId: Int, yes: Boolean) {
        state = state.copy(activeVotes = state.activeVotes.map { v ->
            when {
                v.id != sessionId -> v
                yes -> v.copy(yesVoters = v.yesVoters + playerId, noVoters = v.noVoters - playerId)
                else -> v.copy(noVoters = v.noVoters + playerId, yesVoters = v.yesVoters - playerId)
            }
        })
        dirty = true
    }

    /** Removes vote [sessionId] (on resolve, timeout, or cancellation). */
    fun closeVote(sessionId: String) {
        state = state.copy(activeVotes = state.activeVotes.filterNot { it.id == sessionId })
        dirty = true
    }

    private fun checkForEliminations() {
        state.players
            .filter { it.chips == 0 && it.isParticipating() }
            .forEach { it.setAsEliminated() }
    }

    /**
     * The single write path. Persists staged mutations with an optimistic-concurrency check, then
     * clears the dirty flag. A no-op if nothing was staged. Throws [ConcurrentModificationException]
     * on a version mismatch so the surrounding [withTable] can restore and retry. Intended to be
     * called only by that transaction boundary.
     */
    fun commit() {
        if (!dirty) return
        state = state.copy(version = state.version + 1)
        if (!persistence.saveStateIfVersionMatches(state)) {
            throw ConcurrentModificationException("Concurrent modification of table ${state.id}")
        }
        dirty = false
    }
}
