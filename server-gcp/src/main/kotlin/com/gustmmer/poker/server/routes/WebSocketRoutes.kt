package com.gustmmer.poker.server.routes

import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.voting.VoteManager
import com.gustmmer.poker.server.voting.toSummary
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import java.time.Instant

fun Application.configureWebSocketRoutes(
    jwtService: JwtService,
    connectionManager: TableConnectionManager,
    persistence: PokerTablePersistence,
    voteManager: VoteManager,
    timerManager: TurnTimerManager,
) {
    routing {
        webSocket("/ws/tables/{tableId}") {
            val tableId = call.parameters["tableId"]?.toIntOrNull()
            if (tableId == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid table ID"))
                return@webSocket
            }

            val cookieName = jwtService.cookieName(tableId)
            val token = call.request.cookies[cookieName]
            if (token == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing auth cookie"))
                return@webSocket
            }

            val session = jwtService.verify(token)
            if (session == null || session.tableId != tableId) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth"))
                return@webSocket
            }

            val table = PokerTable.restore(tableId, persistence)
            if (table == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Table not found"))
                return@webSocket
            }

            val player = table.currentState.players.find { it.id == session.playerId }
            if (player == null) {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Player not found in table"))
                return@webSocket
            }

            if (player.status == PlayerStatus.OFFLINE || player.status == PlayerStatus.IDLE) {
                player.setAsOnline()
                persistence.saveState(table.currentState)
            }

            connectionManager.addConnection(tableId, session.playerId, this)
            val activeVotes = voteManager.getOpenSessions(tableId).map { it.toSummary() }
            connectionManager.broadcastGameState(table.currentState, activeVotes)

            // Recover timer if the server restarted while a round was in progress
            if (table.currentState.gameStatus == GameStatus.RUNNING && timerManager.getRemainingMs(tableId) == null) {
                val durationMs = table.currentState.config.turnTimerSeconds * 1000L
                val startedAt = table.currentState.turnTimerStartedAt
                if (startedAt != null) {
                    val elapsed = Instant.now().toEpochMilli() - startedAt
                    val remaining = (durationMs - elapsed).coerceAtLeast(0L)
                    if (remaining <= 0L) {
                        timerManager.resolveExpiredTurns(table, durationMs)
                    } else {
                        timerManager.startTimer(table, remaining)
                    }
                } else {
                    timerManager.startTimer(table, durationMs)
                }
            }

            try {
                for (frame in incoming) {
                    // Client messages are handled via REST endpoints.
                    // The WS channel is primarily for server-to-client pushes.
                    // Keepalive pings are handled by the WebSocket plugin.
                }
            } finally {
                connectionManager.removeConnection(tableId, session.playerId)

                val currentTable = PokerTable.restore(tableId, persistence)
                if (currentTable != null) {
                    val disconnectedPlayer = currentTable.currentState.players.find { it.id == session.playerId }
                    if (disconnectedPlayer != null && disconnectedPlayer.status == PlayerStatus.ONLINE) {
                        disconnectedPlayer.setAsOffline()
                        persistence.saveState(currentTable.currentState)
                        val votes = voteManager.getOpenSessions(tableId).map { it.toSummary() }
                        connectionManager.broadcastGameState(currentTable.currentState, votes)
                    }
                }
            }
        }
    }
}
