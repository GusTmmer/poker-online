package com.gustmmer.poker

import com.gustmmer.poker.bot.BotDecision
import com.gustmmer.poker.bot.BotStrategy
import com.gustmmer.poker.bot.BotView
import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.nextInt

@Serializable
data class Blinds(val big: Int, val small: Int) {
    companion object {
        /** Initial blinds: the configured big blind (small = half), else big = 2% of the stack, small = 1%. */
        fun initial(config: TableConfig): Blinds = config.startingBigBlind
            ?.let { Blinds(big = it, small = it / 2) }
            ?: Blinds(big = config.startingChips / 50, small = config.startingChips / 100)
    }

    fun escalated(multiplier: Double) = Blinds(
        big = (big * multiplier).roundToInt(),
        small = (small * multiplier).roundToInt(),
    )
}

private val log = LoggerFactory.getLogger(PokerTable::class.java)

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
            bots: List<Player> = emptyList(),
        ): PokerTable {
            val players = (listOf(firstPlayer) + bots).toMutableList()
            players.forEach { it.addChips(config.startingChips) }
            val state = PokerTableState(
                id = id,
                players = players,
                playerOrdering = PlayerOrdering.forNewTable(players),
                blinds = Blinds.initial(config),
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
            newBlinds = state.blinds.escalated(state.config.blindEscalationMultiplier)
            newRoundsSince = 0
        } else {
            newBlinds = state.blinds
            newRoundsSince = newRoundsSinceEscalation
        }

        // Re-derive positions over exactly this hand's players, so blinds and first-to-act never index a
        // stale list (e.g. one still holding a player eliminated since the ordering was last computed).
        playerOrdering = playerOrdering.forCurrentHand(roundPlayers)
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

        // Eliminations are deferred to clearRoundState (the WAITING transition), NOT applied here.
        // setAsEliminated() folds the player (roundStatus = FOLDED), which would make a busted all-in
        // player look folded to the showdown reveal and hide their cards — yet they were part of the
        // showdown. Keeping them ACTIVE through the SHOWDOWN commit lets their hand be revealed; the
        // bust is realised one commit later when the hand closes.
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
        // Defensive: the turn only ever rests on a player who owes an action.
        if (currentPlayer !in bettingState.toAct || !currentPlayer.canBet()) return null

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

    /**
     * What the computer player whose turn it is would do, without doing it. Null when the turn isn't a
     * bot's. The decision's randomness comes from [rng]; its `thinkMs` never depends on it.
     */
    fun botDecisionForCurrentPlayer(rng: Random = Random.Default): BotDecision? {
        val roundState = state.roundState ?: return null
        val bettingState = roundState.bettingRoundState ?: return null
        if (!roundState.pokerRoundStage.isBettingRound()) return null
        val bot = roundState.playerOrdering.bettingPlayer()
        if (!bot.isBot || bot !in bettingState.toAct || !bot.canBet()) return null
        return BotStrategy.decide(BotView.from(roundState, bot), bot.botPersonality!!.profile, rng)
    }

    /**
     * Plays the current computer player's turn (staged). Returns the command played, or null when it
     * isn't a bot's turn. A decision the engine rejects is logged and replaced by check-or-fold, so a
     * strategy bug can never stall the table.
     */
    fun playBotTurn(rng: Random = Random.Default): PlayerCommand? {
        val decision = botDecisionForCurrentPlayer(rng) ?: return null
        val bot = state.roundState!!.playerOrdering.bettingPlayer()
        return try {
            processPlayerCommand(decision.command)
            log.debug("Bot {} ({}): {} — {}", bot.name, bot.botPersonality, decision.command.type, decision.reason)
            decision.command
        } catch (e: IllegalArgumentException) {
            log.warn("Bot {} made an illegal move ({}); checking or folding instead", bot.name, e.message)
            val toCall = state.roundState!!.bettingRoundState!!.pot.chipsToMatchCurrentBet(bot)
            val fallback = if (toCall == 0) Call(bot.id) else Fold(bot.id)
            processPlayerCommand(fallback)
            fallback
        }
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

    /**
     * Removes [playerId] from the table. If a hand they were dealt into is still on the table, they are
     * folded now (in turn or not) but stay seated until [clearRoundState] — the hand's pots and ordering
     * reference them, and dropping them early leaves a state that can't be restored.
     */
    fun kickPlayer(playerId: Int) {
        val player = state.players.find { it.id == playerId } ?: return

        val roundState = state.roundState
        if (roundState != null && roundState.players.any { it.id == playerId }) {
            state = state.copy(
                roundState = PokerRound(roundState).forceFold(playerId),
                pendingRemovals = state.pendingRemovals + playerId,
            )
        } else {
            state.players.remove(player)
            state = state.copy(readyPlayers = state.readyPlayers - playerId)
        }
        dirty = true
    }

    /** Raises the blinds by the table's escalation multiplier, effective from the next hand. */
    fun increaseBlinds() {
        state = state.copy(
            blinds = state.blinds.escalated(state.config.blindEscalationMultiplier),
            roundsSinceLastEscalation = 0,
        )
        dirty = true
    }

    fun playerReady(playerId: Int): Boolean {
        check(state.gameStatus == GameStatus.WAITING) { "Game is not in waiting state" }
        val player = state.players.find { it.id == playerId } ?: return false
        if (!player.isParticipating()) return false

        state = state.copy(readyPlayers = state.readyPlayers + playerId)
        dirty = true

        // Computer players are always ready.
        return state.players.participating().filterNot { it.isBot }.all { it.id in state.readyPlayers }
    }

    /**
     * Resets every stack and the blinds for a fresh game. A hand still on the table is abandoned — its
     * bets don't matter once every stack is reset — and players who left during it are dropped.
     */
    fun restartGame() {
        state.players.removeAll { it.id in state.pendingRemovals }

        state.players.forEach { player ->
            player.removeChips(player.chips)
            player.addChips(state.config.startingChips)
            player.setAsOnline()
        }

        state = state.copy(
            blinds = Blinds.initial(state.config),
            playerOrdering = PlayerOrdering.forNewTable(state.players),
            roundsSinceLastEscalation = 0,
            initialPlayerCount = 0,
            gameStatus = GameStatus.WAITING,
            readyPlayers = emptySet(),
            turnTimerStartedAt = null,
            turnTimeRemainingMs = null,
            roundState = null,
            activeVotes = emptyList(),
            pendingRemovals = emptySet(),
        )
        playerOrdering = state.playerOrdering
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
        // The hand is over and the pot fully distributed — now realise busts. Deferred to here (rather
        // than the SHOWDOWN commit) so the showdown reveal can still show a busted all-in player's cards
        // before they are folded out by elimination. Runs before forNextHand so eliminated players are
        // dropped from the next hand's ordering. Idempotent: already-eliminated players are skipped.
        checkForEliminations()
        state.players.removeAll { it.id in state.pendingRemovals }
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
            pendingRemovals = emptySet(),
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
