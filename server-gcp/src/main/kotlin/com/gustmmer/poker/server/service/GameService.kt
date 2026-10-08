package com.gustmmer.poker.server.service

import com.gustmmer.poker.*
import com.gustmmer.poker.bot.BotPersonality
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import com.gustmmer.poker.server.routes.ActionRequest
import com.gustmmer.poker.server.routes.CreateTableRequest
import com.gustmmer.poker.server.routes.JoinResponse
import com.gustmmer.poker.server.routes.PlayerInfo
import com.gustmmer.poker.server.routes.TableInfoResponse
import com.gustmmer.poker.server.routes.TableSummary
import com.gustmmer.poker.server.timer.TurnTimerManager
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

data class CreatedTable(val tableId: Int, val playerId: Int)

private val BOT_NAMES = listOf("Ada", "Bishop", "Cleo", "Dex", "Echo", "Finn", "Gizmo", "Hal", "Iris")

/**
 * Orchestrates table lifecycle and in-round actions. Each mutating operation follows one shape:
 * validate and stage mutations inside a single [withTable] block (which commits once, with
 * optimistic-concurrency retry), then run the post-commit turn-timer transition. The WebSocket
 * broadcast is NOT triggered here — the commit itself drives it: every committed change is delivered
 * to clients by the [com.gustmmer.poker.server.bus.TableUpdateBus] subscription in
 * [TableConnectionManager]. Mutators never persist on their own, so a logical operation is exactly one
 * versioned write, and one write == one fan-out.
 */
class GameService(
    private val persistence: PokerTablePersistence,
    private val timerManager: TurnTimerManager,
) {
    private val log = LoggerFactory.getLogger(GameService::class.java)

    private fun turnTimerMs(state: PokerTableState) = state.config.turnTimerSeconds * 1000L

    /**
     * Creates the table under a fresh random id. An id that's already taken fails the commit's version check
     * (the existing table is untouched), so a collision just draws another id. The write is a blocking
     * Firestore transaction, so it runs on [Dispatchers.IO] like every other commit.
     */
    suspend fun createTable(request: CreateTableRequest, newId: () -> Int = PokerTable::newTableId): CreatedTable {
        val config = TableConfig(
            startingChips = request.startingChips,
            turnTimerSeconds = request.turnTimerSeconds,
            maxPlayers = request.maxPlayers,
            name = normalizeTableName(request.name),
            blindEscalationOrbits = request.blindEscalationOrbits,
            blindEscalationMultiplier = request.blindEscalationMultiplier,
            startingBigBlind = request.bigBlind,
            computerPlayers = request.computerPlayers,
        )

        val playerId = 0
        repeat(CREATE_ATTEMPTS - 1) {
            try {
                return newTable(newId(), config, playerId, request)
            } catch (_: ConcurrentModificationException) {
                log.warn("Table id collision on create; drawing another id")
            }
        }
        return newTable(newId(), config, playerId, request)
    }

    private suspend fun newTable(id: Int, config: TableConfig, playerId: Int, request: CreateTableRequest): CreatedTable {
        // Built fresh per attempt: PokerTable.new deals the starting stacks onto these players.
        val player = Player(playerId, normalizePlayerName(request.playerName))
        val table = withContext(Dispatchers.IO) {
            PokerTable.new(id, player, config, persistence, bots = computerPlayers(request.computerPlayers))
        }
        return CreatedTable(tableId = table.id, playerId = playerId)
    }

    /** Seats 1..[count], personalities rotating aggressive → balanced → defensive so any mix has variety. */
    private fun computerPlayers(count: Int): List<Player> = List(count) { i ->
        val personality = BotPersonality.entries[i % BotPersonality.entries.size]
        Player(id = i + 1, name = "CPU ${BOT_NAMES[i % BOT_NAMES.size]}", botPersonality = personality)
    }

    suspend fun getTableInfo(tableId: Int, sessionPlayerId: Int?): ServiceResult<TableInfoResponse> {
        val state = loadTable(tableId, persistence)
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Table not found")

        val nextPlayerIdToAct = state.roundState?.let { round ->
            if (round.pokerRoundStage.isBettingRound()) round.playerOrdering.bettingPlayer().id else null
        }

        return ServiceResult.Ok(
            TableInfoResponse(
                tableId = state.id,
                name = state.config.name,
                players = state.players.map { PlayerInfo(it.id, it.name, it.status.name, it.chips, it.isBot) },
                isOpen = state.config.isOpen,
                maxPlayers = state.config.maxPlayers,
                sessionPlayerId = sessionPlayerId,
                hasSession = sessionPlayerId != null,
                gameStatus = state.gameStatus.name,
                nextPlayerIdToAct = nextPlayerIdToAct,
            )
        )
    }

    /**
     * Lightweight summary for the "my tables" discovery endpoint. Returns null when the table no longer
     * exists (expired via TTL) or [playerId] is no longer a member (kicked/left) — the caller treats a
     * null as a stale session cookie to prune. Read-only: uses [loadTable], never commits.
     */
    suspend fun getTableSummary(tableId: Int, playerId: Int): TableSummary? {
        val state = loadTable(tableId, persistence) ?: return null
        val player = state.players.firstOrNull { it.id == playerId } ?: return null
        if (playerId in state.pendingRemovals) return null
        return TableSummary(
            tableId = state.id,
            name = state.config.name,
            playerName = player.name,
            gameStatus = state.gameStatus.name,
            playerCount = state.players.size,
            maxPlayers = state.config.maxPlayers,
        )
    }

    /**
     * Permanently removes [playerId] from the table, freeing their seat for a new player — the hard
     * counterpart to going OFFLINE (which keeps the seat). Safe to call mid-hand: [PokerTable.kickPlayer]
     * folds them immediately and keeps them seated until the hand is cleared. Idempotent — a no-op if
     * they're already gone or already leaving.
     */
    suspend fun leaveTable(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
            if (table.currentState.players.none { it.id == playerId } || playerId in table.currentState.pendingRemovals) {
                return@withTable ServiceResult.Ok(mapOf("status" to "not_seated"))
            }
            table.setPlayerOffline(playerId)
            table.kickPlayer(playerId)
            // Between hands, the players still ready may now be a majority of those left.
            table.dealIfMajorityReady()
            ServiceResult.Ok(mapOf("status" to "left"))
        }
        // The fold may have advanced the turn or ended the hand — or a hand was just dealt: arm its clock.
        if (result is ServiceResult.Ok) timerManager.settleAfterCommit(tableId)
        return result
    }

    suspend fun joinTable(tableId: Int, playerName: String): ServiceResult<JoinResponse> {
        val name = normalizePlayerName(playerName)
        if (name.isBlank()) return ServiceResult.Failed(HttpStatusCode.BadRequest, "Player name is required")
        val result = withTable(tableId, persistence) { table ->
            // Never a former player's id: their session cookie would then speak for the newcomer.
            val playerId = table.currentState.nextPlayerId
            if (!table.playerJoin(Player(playerId, name))) {
                return@withTable ServiceResult.Failed(HttpStatusCode.Forbidden, "Table is full or closed")
            }
            ServiceResult.Ok(JoinResponse(playerId = playerId, playerName = name), HttpStatusCode.Created)
        }
        // Broadcast is driven by the commit (the bus subscription), so there's nothing to push here.
        return result
    }

    suspend fun applyAction(tableId: Int, playerId: Int, request: ActionRequest): ServiceResult<Map<String, String>> {
        val command: PlayerCommand = when (request.type.uppercase()) {
            "FOLD" -> Fold(playerId)
            "CALL" -> Call(playerId)
            "RAISE" -> {
                val raiseBy = request.value ?: 0
                // Cap before it reaches the engine's chip arithmetic: any real raise is bounded by the
                // total chips in play (< MAX_RAISE), and an unbounded value could overflow Int when added
                // to an existing bet. The engine still enforces the true min/max for realistic amounts.
                if (raiseBy > MAX_RAISE) {
                    return ServiceResult.Failed(HttpStatusCode.BadRequest, "Raise amount is too large")
                }
                Raise(playerId, raiseBy)
            }
            "ALL_IN" -> AllIn(playerId)
            else -> return ServiceResult.Failed(HttpStatusCode.BadRequest, "Invalid action")
        }

        val result = withTable(tableId, persistence) { table ->
            val roundState = table.currentState.roundState
                ?: return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "No round in progress")
            if (table.currentState.gameStatus == GameStatus.PAUSED) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Game is paused")
            }
            if (!roundState.pokerRoundStage.isBettingRound()) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Not a betting round")
            }
            if (roundState.playerOrdering.bettingPlayer().id != playerId) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Not your turn")
            }

            // Acting un-idles the player; this and the command commit together. An invalid command throws
            // (an engine rule violation), which withTable turns into an error and discards the staged changes.
            table.setPlayerOnline(playerId)
            table.processPlayerCommand(command)

            // NOTE: if this action ended the hand, the SHOWDOWN state is committed and fanned out here
            // on its own. Clearing to WAITING is a *separate* commit (see TurnTimerManager.settleAfterCommit) so the
            // showdown reveal reaches clients as a distinct frame — folding it into this block would
            // overwrite the reveal in memory before it is ever broadcast (the whole block is one commit).
            ServiceResult.Ok(mapOf("status" to "ok"))
        }

        // If this action ended the hand, the SHOWDOWN state was committed (and fans out) on its own;
        // clearing to WAITING is a separate commit so the reveal reaches clients as a distinct frame.
        if (result is ServiceResult.Ok) timerManager.settleAfterCommit(tableId)
        return result
    }

    /** The owner deals the next hand without waiting for the others to be ready. */
    suspend fun startRound(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
            if (!table.isOwner(playerId)) return@withTable notOwner("start a hand")
            if (table.currentState.gameStatus == GameStatus.PAUSED) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Game is paused")
            }
            val existingRound = table.currentState.roundState
            if (existingRound != null && existingRound.pokerRoundStage.isBettingRound()) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Round already in progress")
            }
            if (table.currentState.players.participating().size < 2) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Need at least 2 players")
            }
            if (existingRound != null) table.clearRoundState()
            table.newPokerRound()
            ServiceResult.Ok(mapOf("status" to "round_started"))
        }
        // Short stacks can deal a hand straight to showdown; settle clears it and arms the timer otherwise.
        if (result is ServiceResult.Ok) timerManager.settleAfterCommit(tableId)
        return result
    }

    suspend fun restartGame(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
            if (!table.isOwner(playerId)) return@withTable notOwner("start a new game")
            val existingRound = table.currentState.roundState
            if (existingRound != null && existingRound.pokerRoundStage.isBettingRound()) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Cannot restart during a round")
            }
            if (!table.currentState.isGameOver()) {
                return@withTable ServiceResult.Failed(
                    HttpStatusCode.BadRequest,
                    "Cannot restart while the game is still active"
                )
            }
            if (existingRound != null) table.clearRoundState()
            table.restartGame()
            ServiceResult.Ok(mapOf("status" to "game_restarted"))
        }
        return result
    }

    suspend fun readyUp(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        var roundStarted = false
        val result = withTable(tableId, persistence) { table ->
            if (table.currentState.gameStatus == GameStatus.PAUSED) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Game is paused")
            }
            if (table.currentState.gameStatus != GameStatus.WAITING) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Game is not in waiting state")
            }
            if (table.currentState.isGameOver()) {
                return@withTable ServiceResult.Failed(
                    HttpStatusCode.BadRequest,
                    "Game is over, use /restart-game to play again"
                )
            }

            // Readying up is being here: it counts the player among those present.
            table.setPlayerOnline(playerId)
            table.playerReady(playerId)
            roundStarted = table.dealIfMajorityReady()
            ServiceResult.Ok(mapOf("status" to if (roundStarted) "round_started" else "ready"))
        }
        if (result is ServiceResult.Ok && roundStarted) timerManager.settleAfterCommit(tableId)
        return result
    }

    suspend fun setPlayerOnline(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        // Online flip + any auto-unpause stage together and commit once; the timer cascade runs after.
        var unpaused = false
        var myTurn = false
        var turnMs = 0L
        val result = withTable(tableId, persistence) { table ->
            val player = table.currentState.players.find { it.id == playerId }
                ?: return@withTable ServiceResult.Failed(HttpStatusCode.NotFound, "Player not found")
            if (player.status == PlayerStatus.ELIMINATED) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Player cannot be activated")
            }

            table.setPlayerOnline(playerId)
            // Auto-unpause: game was paused because the majority were idle and now they aren't.
            if (table.currentState.gameStatus == GameStatus.PAUSED && !table.currentState.majorityIdle()) {
                table.unpause()
                unpaused = true
            }
            val round = table.currentState.roundState
            myTurn = round != null && round.pokerRoundStage.isBettingRound() && round.playerOrdering.bettingPlayer().id == playerId
            turnMs = turnTimerMs(table.currentState)
            ServiceResult.Ok(mapOf("status" to "activated"))
        }
        if (result !is ServiceResult.Ok) return result

        when {
            unpaused -> timerManager.resolveExpiredTurns(tableId, turnMs)
            // Back on their own turn: give them a fresh clock (startTimer does nothing unless the game is running).
            myTurn -> timerManager.startTimer(tableId, turnMs)
        }
        return result
    }

    suspend fun updateSettings(tableId: Int, playerId: Int, isOpen: Boolean?, name: String?): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
            if (!table.isOwner(playerId)) return@withTable notOwner("change the table's settings")
            table.updateConfig(isOpen = isOpen, name = name?.let(::normalizeTableName))
            ServiceResult.Ok(mapOf("status" to "settings_updated"))
        }
        return result
    }

    private fun notOwner(what: String) =
        ServiceResult.Failed(HttpStatusCode.Forbidden, "Only the table owner can $what")

    /** Trim and length-cap a user-supplied table name so it stays a sane, storable label. */
    private fun normalizeTableName(raw: String): String = raw.trim().take(TableConfig.MAX_NAME_LENGTH)

    /**
     * Trim and length-cap a user-supplied player name. Unlike the table name this is stored per player
     * and echoed to everyone on every frame, so an uncapped value is a storage/bandwidth amplifier —
     * cap it at the source (both create and join go through here).
     */
    private fun normalizePlayerName(raw: String): String = raw.trim().take(MAX_PLAYER_NAME_LENGTH)

    companion object {
        /** Length cap for a stored player name. */
        const val MAX_PLAYER_NAME_LENGTH = 40

        /** Upper bound on a single raise increment — well above total chips in play, but overflow-safe. */
        const val MAX_RAISE = 1_000_000_000

        /** Ids drawn before giving up on creating a table; a collision among 2³¹ ids is already vanishingly rare. */
        private const val CREATE_ATTEMPTS = 3
    }
}
