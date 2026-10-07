package com.gustmmer.poker.server.routes

import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.service.ServiceResult
import com.gustmmer.poker.server.service.withTable
import com.gustmmer.poker.server.session.JwtService
import io.ktor.http.HttpStatusCode
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*

fun Application.configureWebSocketRoutes(
    jwtService: JwtService,
    connectionManager: TableConnectionManager,
    persistence: PokerTablePersistence,
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

            // Register the socket BEFORE bringing the player online. A previous socket of theirs may be
            // closing right now (a reconnect, or React StrictMode's double mount in dev); its disconnect
            // handling marks the player offline only if no socket is registered, so registering first means
            // it either sees this one or commits before our online commit below, which then wins.
            // A reconnect on a new session displaces the old one; close it so its coroutine ends now instead
            // of lingering until the ping timeout.
            connectionManager.addConnection(tableId, session.playerId, this)?.let { displaced ->
                runCatching { displaced.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Replaced by a new connection")) }
            }

            // Bring the (re)connecting player ONLINE through a versioned save so a concurrent
            // round mutation can't be silently lost. setPlayerOnline no-ops if already ONLINE.
            val connect = withTable(tableId, persistence) { table ->
                if (table.currentState.players.none { it.id == session.playerId }) {
                    return@withTable ServiceResult.Failed(HttpStatusCode.NotFound, "Player not found in table")
                }
                table.setPlayerOnline(session.playerId)
                ServiceResult.Ok(table)
            }
            val table = when (connect) {
                is ServiceResult.Ok -> connect.value
                is ServiceResult.Failed -> {
                    connectionManager.removeConnection(tableId, session.playerId, this)
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, connect.error))
                    return@webSocket
                }
            }
            connectionManager.broadcastGameState(table.currentState)

            // No timer recovery needed: the turn timer is a durable Cloud Task that fires regardless of
            // restarts/scale-to-zero (and the dev in-memory scheduler shares the process, where the
            // in-memory persistence loses state on restart anyway).

            try {
                for (frame in incoming) {
                    // Client messages are handled via REST endpoints.
                    // The WS channel is primarily for server-to-client pushes.
                    // Keepalive pings are handled by the WebSocket plugin.
                }
            } finally {
                connectionManager.removeConnection(tableId, session.playerId, this)

                // Only mark offline if the player has NOT reconnected on a new session — otherwise the old
                // session would overwrite the new one's ONLINE with OFFLINE, and startTimer would auto-play
                // their turn. The check runs inside the transaction: if the new session's online commit lands
                // meanwhile, the version check retries this block, which then sees the new session.
                // setPlayerOffline only transitions an ONLINE player; the change (if any) fans out via the
                // bus to any players still connected.
                withTable(tableId, persistence) { table ->
                    if (!connectionManager.isPlayerConnected(tableId, session.playerId)) {
                        table.setPlayerOffline(session.playerId)
                    }
                    ServiceResult.Ok(Unit)
                }
            }
        }
    }
}
