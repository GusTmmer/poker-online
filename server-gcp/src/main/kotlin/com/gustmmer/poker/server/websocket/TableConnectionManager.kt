package com.gustmmer.poker.server.websocket

import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.server.bus.Subscription
import com.gustmmer.poker.server.bus.TableUpdateBus
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

class TableConnectionManager(private val bus: TableUpdateBus) {
    private val connections = ConcurrentHashMap<Int, ConcurrentHashMap<Int, WebSocketSession>>()

    // One bus subscription per table this instance holds sockets for. The handler fans a committed
    // change out to our local sockets — so a change committed on ANY instance reaches every player,
    // wherever their socket landed. Opened on the first local socket, cancelled on the last.
    private val subscriptions = ConcurrentHashMap<Int, Subscription>()

    /**
     * Registers [session] for the player, returning any previous session it displaced so the caller
     * can close it. Returns null when there was no prior session (or it was the same instance).
     */
    fun addConnection(tableId: Int, playerId: Int, session: WebSocketSession): WebSocketSession? {
        val tableConnections = connections.getOrPut(tableId) { ConcurrentHashMap() }
        subscriptions.computeIfAbsent(tableId) { id ->
            bus.subscribe(id) { state -> broadcastGameState(state) }
        }
        return tableConnections.put(playerId, session).takeIf { it !== session }
    }

    fun removeConnection(tableId: Int, playerId: Int, session: WebSocketSession) {
        val tableConnections = connections[tableId] ?: return
        tableConnections.remove(playerId, session)
        if (tableConnections.isEmpty()) {
            connections.remove(tableId, tableConnections)
            subscriptions.remove(tableId)?.cancel()
        }
    }

    fun isPlayerConnected(tableId: Int, playerId: Int): Boolean {
        return connections[tableId]?.containsKey(playerId) == true
    }

    suspend fun broadcastGameState(state: PokerTableState) {
        val tableConnections = connections[state.id] ?: return
        val projection = GameStateProjection.of(state)
        for ((playerId, session) in tableConnections) {
            val json = Json.encodeToString(projection.forPlayer(playerId))
            val sent = runCatching { session.send(Frame.Text(json)) }.isSuccess
            if (!sent) {
                // Session is dead but its finally-block hasn't cleaned up yet — remove it now so
                // future broadcasts don't silently drop for this player.
                tableConnections.remove(playerId, session)
            }
        }
    }

    suspend fun broadcastMessage(tableId: Int, type: String, message: String) {
        val tableConnections = connections[tableId] ?: return

        @Serializable
        data class SimpleMessage(val type: String, val message: String)

        val json = Json.encodeToString(SimpleMessage(type, message))
        for ((_, session) in tableConnections) {
            runCatching { session.send(Frame.Text(json)) }
        }
    }
}
