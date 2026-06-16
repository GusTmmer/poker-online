package com.gustmmer.poker.server.service

import com.gustmmer.poker.*
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import com.gustmmer.poker.server.routes.ActionRequest
import com.gustmmer.poker.server.routes.CreateTableRequest
import com.gustmmer.poker.server.routes.JoinResponse
import com.gustmmer.poker.server.routes.PlayerInfo
import com.gustmmer.poker.server.routes.TableInfoResponse
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.http.HttpStatusCode

data class CreatedTable(val tableId: Int, val playerId: Int)

/**
 * Orchestrates table lifecycle and in-round actions: restoring the table, applying the mutation,
 * persisting (with optimistic-concurrency retry via [withTable]), broadcasting the new state, and
 * deciding on the resulting turn-timer transition. Routes only decode requests and map results to
 * HTTP responses — every domain decision here was previously inline in the route handlers.
 */
class GameService(
    private val persistence: PokerTablePersistence,
    private val connectionManager: TableConnectionManager,
    private val timerManager: TurnTimerManager,
) {

    fun createTable(request: CreateTableRequest): CreatedTable {
        val config = TableConfig(
            startingChips = request.startingChips,
            turnTimerSeconds = request.turnTimerSeconds,
            maxPlayers = request.maxPlayers,
            blindEscalationOrbits = request.blindEscalationOrbits,
            blindEscalationMultiplier = request.blindEscalationMultiplier,
        )

        val playerId = 0
        val player = Player(playerId, request.playerName)
        val table = PokerTable.new(firstPlayer = player, config = config, persistence = persistence)

        return CreatedTable(tableId = table.id, playerId = playerId)
    }

    fun getTableInfo(tableId: Int, sessionPlayerId: Int?): ServiceResult<TableInfoResponse> {
        val state = persistence.loadState(tableId)
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Table not found")

        return ServiceResult.Ok(
            TableInfoResponse(
                tableId = state.id,
                players = state.players.map { PlayerInfo(it.id, it.name, it.status.name, it.chips) },
                isOpen = state.config.isOpen,
                maxPlayers = state.config.maxPlayers,
                sessionPlayerId = sessionPlayerId,
                hasSession = sessionPlayerId != null,
                gameStatus = state.gameStatus.name,
            )
        )
    }

    suspend fun joinTable(tableId: Int, playerName: String): ServiceResult<JoinResponse> =
        withTable(tableId, persistence) { table ->
            val playerId = (table.currentState.players.maxOfOrNull { it.id } ?: -1) + 1
            val player = Player(playerId, playerName)

            if (!table.playerJoin(player)) {
                return@withTable ServiceResult.Failed(HttpStatusCode.Forbidden, "Table is full or closed")
            }

            connectionManager.broadcastGameState(table.currentState)
            ServiceResult.Ok(JoinResponse(playerId = playerId, playerName = playerName), HttpStatusCode.Created)
        }

    suspend fun applyAction(tableId: Int, playerId: Int, request: ActionRequest): ServiceResult<Map<String, String>> {
        val command: PlayerCommand = when (request.type.uppercase()) {
            "FOLD" -> Fold(playerId)
            "CALL" -> Call(playerId)
            "RAISE" -> Raise(playerId, request.value ?: 0)
            "ALL_IN" -> AllIn(playerId)
            else -> return ServiceResult.Failed(HttpStatusCode.BadRequest, "Invalid action")
        }

        return withTable(tableId, persistence) { table ->
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

            val player = table.currentState.players.find { it.id == playerId }
            if (player?.status == PlayerStatus.IDLE) {
                player.setAsOnline()
            }

            try {
                timerManager.cancelTimer(tableId)
                table.processPlayerCommand(command)
                connectionManager.broadcastGameState(table.currentState)

                val newRound = table.currentState.roundState
                if (newRound != null && newRound.pokerRoundStage.isBettingRound()) {
                    timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)
                } else if (newRound != null) {
                    // Round ended (SHOWDOWN or preemptive) — transition to WAITING for ready-up
                    table.clearRoundState()
                    connectionManager.broadcastGameState(table.currentState)
                }

                ServiceResult.Ok(mapOf("status" to "ok"))
            } catch (e: IllegalArgumentException) {
                ServiceResult.Failed(HttpStatusCode.BadRequest, e.message ?: "Invalid action")
            }
        }
    }

    suspend fun startRound(tableId: Int): ServiceResult<Map<String, String>> =
        withTable(tableId, persistence) { table ->
            val existingRound = table.currentState.roundState
            if (existingRound != null && existingRound.pokerRoundStage.isBettingRound()) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Round already in progress")
            }
            if (existingRound != null) {
                table.clearRoundState()
            }

            val participating = table.currentState.players.participating()
            if (participating.size < 2) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Need at least 2 players")
            }

            table.newPokerRound()
            connectionManager.broadcastGameState(table.currentState)
            timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)

            ServiceResult.Ok(mapOf("status" to "round_started"))
        }

    suspend fun restartGame(tableId: Int): ServiceResult<Map<String, String>> =
        withTable(tableId, persistence) { table ->
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
            if (existingRound != null) {
                table.clearRoundState()
            }

            table.restartGame()
            connectionManager.broadcastGameState(table.currentState)

            ServiceResult.Ok(mapOf("status" to "game_restarted"))
        }

    suspend fun readyUp(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> =
        withTable(tableId, persistence) { table ->
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
            connectionManager.broadcastGameState(table.currentState)

            if (allReady) {
                if (table.currentState.roundState != null) {
                    table.clearRoundState()
                }
                table.newPokerRound()
                connectionManager.broadcastGameState(table.currentState)
                timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)
            }

            ServiceResult.Ok(mapOf("status" to if (allReady) "round_started" else "ready"))
        }

    // TODO: Rename route to set-player-online
    suspend fun setPlayerOnline(tableId: Int, playerId: Int): ServiceResult<Map<String, String>> {
        val table = PokerTable.restore(tableId, persistence)
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Table not found")

        val player = table.currentState.players.find { it.id == playerId }
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Player not found")

        if (player.status != PlayerStatus.IDLE) {
            return ServiceResult.Failed(HttpStatusCode.BadRequest, "Player is not idle")
        }

        player.setAsOnline()
        persistence.saveState(table.currentState)
        connectionManager.broadcastGameState(table.currentState)

        val roundState = table.currentState.roundState
        if (roundState != null &&
            table.currentState.gameStatus == GameStatus.RUNNING &&
            roundState.pokerRoundStage.isBettingRound() &&
            roundState.playerOrdering.bettingPlayer().id == playerId
        ) {
            timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)
        }

        return ServiceResult.Ok(mapOf("status" to "activated"))
    }

    suspend fun updateSettings(tableId: Int, isOpen: Boolean): ServiceResult<Map<String, String>> {
        val table = PokerTable.restore(tableId, persistence)
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Table not found")

        table.updateConfig(isOpen = isOpen)
        connectionManager.broadcastGameState(table.currentState)

        return ServiceResult.Ok(mapOf("status" to "settings_updated"))
    }
}
