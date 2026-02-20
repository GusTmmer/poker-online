package com.gustmmer.poker.server.websocket

import com.gustmmer.poker.*
import com.gustmmer.poker.round.PokerRoundStage
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class GameStateUpdate(
    val type: String,
    val tableId: Int,
    val players: List<PlayerView>,
    val communityCards: List<String>,
    val potTotal: Int,
    val currentPlayerId: Int?,
    val roundStage: String?,
    val blinds: BlindInfo,
    val isPaused: Boolean,
    val message: String? = null,
)

@Serializable
data class PlayerView(
    val id: Int,
    val name: String,
    val chips: Int,
    val status: String,
    val isActive: Boolean,
    val currentBet: Int,
    val pocketCards: List<String>?,
)

@Serializable
data class BlindInfo(val big: Int, val small: Int)

data class PlayerConnection(val tableId: Int, val playerId: Int, val session: WebSocketSession)

class TableConnectionManager {
    private val connections = ConcurrentHashMap<Int, MutableMap<Int, WebSocketSession>>()

    fun addConnection(tableId: Int, playerId: Int, session: WebSocketSession) {
        val tableConnections = connections.getOrPut(tableId) { ConcurrentHashMap() }
        tableConnections[playerId]?.let { oldSession ->
            runCatching { /* old session will be closed by the caller */ }
        }
        tableConnections[playerId] = session
    }

    fun removeConnection(tableId: Int, playerId: Int) {
        connections[tableId]?.remove(playerId)
    }

    fun getOnlinePlayerCount(tableId: Int): Int {
        return connections[tableId]?.size ?: 0
    }

    fun isPlayerConnected(tableId: Int, playerId: Int): Boolean {
        return connections[tableId]?.containsKey(playerId) == true
    }

    suspend fun broadcastGameState(state: PokerTableState) {
        val tableConnections = connections[state.id] ?: return

        for ((playerId, session) in tableConnections) {
            val update = buildGameStateUpdate(state, playerId)
            val json = Json.encodeToString(update)
            runCatching { session.send(Frame.Text(json)) }
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

    private fun buildGameStateUpdate(state: PokerTableState, forPlayerId: Int): GameStateUpdate {
        val roundState = state.roundState
        val isShowdown = roundState?.pokerRoundStage == PokerRoundStage.SHOWDOWN

        val players = state.players.map { player ->
            val showCards = player.id == forPlayerId || (isShowdown && player.isActive())
            PlayerView(
                id = player.id,
                name = player.name,
                chips = player.chips,
                status = player.status.name,
                isActive = player.isActive(),
                currentBet = roundState?.bettingRoundState?.pot?.playerBet(player) ?: 0,
                pocketCards = if (showCards && player.pocketCards.isNotEmpty()) {
                    player.pocketCards.map { it.toString() }
                } else null,
            )
        }

        val currentPlayer = roundState?.let {
            if (it.pokerRoundStage.isBettingRound()) it.playerOrdering.bettingPlayer().id else null
        }

        return GameStateUpdate(
            type = "game_state",
            tableId = state.id,
            players = players,
            communityCards = roundState?.communityCards?.map { it.toString() } ?: emptyList(),
            potTotal = roundState?.pots?.sumOf { it.totalBets() } ?: 0,
            currentPlayerId = currentPlayer,
            roundStage = roundState?.pokerRoundStage?.name,
            blinds = BlindInfo(state.blinds.big, state.blinds.small),
            isPaused = state.isPaused,
        )
    }
}
