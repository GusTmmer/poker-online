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

    fun createTable(request: CreateTableRequest): CreatedTable {
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
        val player = Player(playerId, request.playerName)
        val table = PokerTable.new(firstPlayer = player, config = config, persistence = persistence, bots = computerPlayers(request.computerPlayers))

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
            ServiceResult.Ok(mapOf("status" to "left"))
        }
        // The fold may have advanced the turn or ended the hand.
        if (result is ServiceResult.Ok) timerManager.settleAfterCommit(tableId)
        return result
    }

    suspend fun joinTable(tableId: Int, playerName: String): ServiceResult<JoinResponse> {
        val result = withTable(tableId, persistence) { table ->
            val playerId = (table.currentState.players.maxOfOrNull { it.id } ?: -1) + 1
            if (!table.playerJoin(Player(playerId, playerName))) {
                return@withTable ServiceResult.Failed(HttpStatusCode.Forbidden, "Table is full or closed")
            }
            ServiceResult.Ok(JoinResponse(playerId = playerId, playerName = playerName), HttpStatusCode.Created)
        }
        // Broadcast is driven by the commit (the bus subscription), so there's nothing to push here.
        return result
    }

    suspend fun applyAction(tableId: Int, playerId: Int, request: ActionRequest): ServiceResult<Map<String, String>> {
        val command: PlayerCommand = when (request.type.uppercase()) {
            "FOLD" -> Fold(playerId)
            "CALL" -> Call(playerId)
            "RAISE" -> Raise(playerId, request.value ?: 0)
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

    suspend fun startRound(tableId: Int): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
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

    suspend fun restartGame(tableId: Int): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
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
            if (table.currentState.gameStatus != GameStatus.WAITING) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Game is not in waiting state")
            }
            if (table.currentState.isGameOver()) {
                return@withTable ServiceResult.Failed(
                    HttpStatusCode.BadRequest,
                    "Game is over, use /restart-game to play again"
                )
            }

            val allReady = table.playerReady(playerId)
            if (allReady) {
                if (table.currentState.roundState != null) table.clearRoundState()
                table.newPokerRound()
            }
            roundStarted = allReady
            ServiceResult.Ok(mapOf("status" to if (allReady) "round_started" else "ready"))
        }
        if (result is ServiceResult.Ok && roundStarted) timerManager.settleAfterCommit(tableId)
        return result
    }

    suspend fun setPlayerOnline(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        // Online flip + any auto-unpause stage together and commit once; the timer cascade runs after.
        var unpaused = false
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
            ServiceResult.Ok(mapOf("status" to "activated"))
        }
        if (result !is ServiceResult.Ok) return result

        val state = loadTable(tableId, persistence)
        if (state != null) {
            if (unpaused) {
                timerManager.resolveExpiredTurns(tableId, turnTimerMs(state))
            } else {
                val roundState = state.roundState
                if (roundState != null &&
                    state.gameStatus == GameStatus.RUNNING &&
                    roundState.pokerRoundStage.isBettingRound() &&
                    roundState.playerOrdering.bettingPlayer().id == playerId
                ) {
                    timerManager.startTimer(tableId, turnTimerMs(state))
                }
            }
        }
        return result
    }

    suspend fun updateSettings(tableId: Int, isOpen: Boolean?, name: String?): ServiceResult<Map<String, String>> {
        val result = withTable(tableId, persistence) { table ->
            table.updateConfig(isOpen = isOpen, name = name?.let(::normalizeTableName))
            ServiceResult.Ok(mapOf("status" to "settings_updated"))
        }
        return result
    }

    /** Trim and length-cap a user-supplied table name so it stays a sane, storable label. */
    private fun normalizeTableName(raw: String): String = raw.trim().take(TableConfig.MAX_NAME_LENGTH)
}
