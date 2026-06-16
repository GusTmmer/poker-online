package com.gustmmer.poker.server.service

import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.isGameOver
import com.gustmmer.poker.participating
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.routes.VoteSessionResponse
import com.gustmmer.poker.server.routes.toResponse
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.voting.*
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.http.HttpStatusCode

/**
 * Single place where a passed vote is turned into a game-state change. Used by both the generic
 * voting-session endpoints and the pause/unpause/kick convenience routes, which previously each
 * re-implemented "create or cast a vote, then if it passed apply the consequence and broadcast".
 */
class VotingService(
    private val persistence: PokerTablePersistence,
    private val voteManager: VoteManager,
    private val timerManager: TurnTimerManager,
    private val connectionManager: TableConnectionManager,
) {

    suspend fun createSession(
        tableId: Int,
        resolution: VoteResolution,
        initiatorId: Int,
    ): ServiceResult<VoteSessionResponse> = withTable(tableId, persistence) { table ->
        if (resolution is VoteResolution.RestartGame && !table.currentState.isGameOver()) {
            return@withTable ServiceResult.Failed(
                HttpStatusCode.BadRequest,
                "Cannot restart while game is still active"
            )
        }

        val eligibleVoters = table.currentState.players.participating().map { it.id }.toSet()
        val result = voteManager.createSession(tableId, resolution, eligibleVoters, initiatorId)
        applyIfPassed(result, table)
        ServiceResult.Ok(result.toResponse())
    }

    suspend fun castVote(tableId: Int, sessionId: String, playerId: Int, yes: Boolean): ServiceResult<VoteSessionResponse>? {
        val result = voteManager.castVote(sessionId, playerId, yes) ?: return null
        return withTable(tableId, persistence) { table ->
            applyIfPassed(result, table)
            ServiceResult.Ok(result.toResponse())
        }
    }

    suspend fun requestKick(tableId: Int, initiatorId: Int, targetPlayerId: Int): ServiceResult<VoteSessionResponse> =
        withTable(tableId, persistence) { table ->
            val target = table.currentState.players.find { it.id == targetPlayerId }
                ?: return@withTable ServiceResult.Failed(HttpStatusCode.NotFound, "Target player not found")

            if (target.status != PlayerStatus.OFFLINE && target.status != PlayerStatus.IDLE) {
                return@withTable ServiceResult.Failed(
                    HttpStatusCode.BadRequest,
                    "Can only kick offline or idle players"
                )
            }

            val eligibleVoters = table.currentState.players
                .filter { it.id != targetPlayerId }
                .participating().map { it.id }.toSet()

            val result = voteManager.createSession(tableId, VoteResolution.KickPlayer(targetPlayerId), eligibleVoters, initiatorId)
            applyIfPassed(result, table)
            ServiceResult.Ok(result.toResponse())
        }

    suspend fun requestPause(tableId: Int, initiatorId: Int): ServiceResult<VoteSessionResponse> =
        withTable(tableId, persistence) { table ->
            if (table.currentState.gameStatus == GameStatus.PAUSED) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Already paused")
            }
            val eligibleVoters = table.currentState.players.participating().map { it.id }.toSet()
            val result = voteManager.createSession(tableId, VoteResolution.PauseGame, eligibleVoters, initiatorId)
            applyIfPassed(result, table)
            ServiceResult.Ok(result.toResponse())
        }

    suspend fun requestUnpause(tableId: Int, initiatorId: Int): ServiceResult<VoteSessionResponse> =
        withTable(tableId, persistence) { table ->
            if (table.currentState.gameStatus != GameStatus.PAUSED) {
                return@withTable ServiceResult.Failed(HttpStatusCode.BadRequest, "Not paused")
            }
            val eligibleVoters = table.currentState.players.participating().map { it.id }.toSet()
            val result = voteManager.createSession(tableId, VoteResolution.UnpauseGame, eligibleVoters, initiatorId)
            applyIfPassed(result, table)
            ServiceResult.Ok(result.toResponse())
        }

    fun openSessions(tableId: Int): List<VoteSummary> =
        voteManager.getOpenSessions(tableId).map { it.toSummary() }

    private suspend fun applyIfPassed(result: VoteResult, table: PokerTable) {
        val activeVotes = voteManager.getOpenSessions(table.id).map { it.toSummary() }

        if (result.outcome == VoteOutcome.PASSED) {
            executeResolution(result.session.resolution, table)
        }
        connectionManager.broadcastGameState(table.currentState, activeVotes)
    }

    private suspend fun executeResolution(resolution: VoteResolution, table: PokerTable) {
        val tableId = table.id
        when (resolution) {
            is VoteResolution.PauseGame -> {
                val remainingMs = timerManager.pauseTimer(tableId)
                table.pause(remainingMs)
                connectionManager.broadcastMessage(tableId, "paused", "Game paused")
            }
            is VoteResolution.UnpauseGame -> {
                val remainingMs = table.unpause()
                connectionManager.broadcastMessage(tableId, "unpaused", "Game resumed")
                if (remainingMs != null && remainingMs > 0) {
                    timerManager.startTimer(table, remainingMs)
                }
            }
            is VoteResolution.KickPlayer -> {
                val targetName = table.currentState.players.find { it.id == resolution.targetPlayerId }?.name ?: "Player"
                table.kickPlayer(resolution.targetPlayerId)
                connectionManager.broadcastMessage(tableId, "player_kicked", "$targetName was kicked")
            }
            is VoteResolution.RestartGame -> table.restartGame()
            is VoteResolution.IncreaseBlinds -> {
                // Blind escalation is built into newPokerRound — this resolution is a no-op for now
            }
        }
    }
}
