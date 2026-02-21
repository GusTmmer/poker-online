package com.gustmmer.poker.server.routes

import com.gustmmer.poker.*
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.session.extractSession
import com.gustmmer.poker.server.session.respondUnauthorized
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.voting.VoteManager
import com.gustmmer.poker.server.voting.VoteType
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class CreateTableRequest(
    val playerName: String,
    val startingChips: Int = 1000,
    val turnTimerSeconds: Int = 30,
    val maxPlayers: Int = 6,
    val blindEscalationOrbits: Int = 2,
    val blindEscalationMultiplier: Double = 2.0,
)

@Serializable
data class CreateTableResponse(val tableId: Int, val joinLink: String, val playerId: Int)

@Serializable
data class JoinRequest(val playerName: String)

@Serializable
data class JoinResponse(val playerId: Int, val playerName: String)

@Serializable
data class ActionRequest(val type: String, val value: Int? = null)

@Serializable
data class KickRequest(val targetPlayerId: Int)

@Serializable
data class SettingsRequest(val isOpen: Boolean)

@Serializable
data class TableInfoResponse(
    val tableId: Int,
    val players: List<PlayerInfo>,
    val isOpen: Boolean,
    val maxPlayers: Int,
    val sessionPlayerId: Int? = null,
)

@Serializable
data class PlayerInfo(val id: Int, val name: String, val status: String)

fun Application.configureTableRoutes(
    persistence: PokerTablePersistence,
    jwtService: JwtService,
    connectionManager: TableConnectionManager,
    voteManager: VoteManager,
    timerManager: TurnTimerManager,
) {
    routing {
        route("/api/tables") {
            post {
                val request = call.receive<CreateTableRequest>()
                val config = TableConfig(
                    startingChips = request.startingChips,
                    turnTimerSeconds = request.turnTimerSeconds,
                    maxPlayers = request.maxPlayers,
                    blindEscalationOrbits = request.blindEscalationOrbits,
                    blindEscalationMultiplier = request.blindEscalationMultiplier,
                )

                val playerId = 0
                val player = Player(playerId, request.playerName)
                val table = PokerTable.new(
                    firstPlayer = player,
                    config = config,
                    persistence = persistence,
                )

                val token = jwtService.createToken(table.id, playerId)
                call.response.cookies.append(
                    Cookie(
                        name = jwtService.cookieName(table.id),
                        value = token,
                        path = "/",
                        httpOnly = true,
                    )
                )

                call.respond(
                    HttpStatusCode.Created,
                    CreateTableResponse(
                        tableId = table.id,
                        joinLink = "/table/${table.id}",
                        playerId = playerId,
                    )
                )
            }

            route("/{tableId}") {
                get {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    val state = persistence.loadState(tableId)
                        ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val session = call.extractSession(jwtService, tableId)

                    call.respond(
                        TableInfoResponse(
                            tableId = state.id,
                            players = state.players.map { PlayerInfo(it.id, it.name, it.status.name) },
                            isOpen = state.config.isOpen,
                            maxPlayers = state.config.maxPlayers,
                            sessionPlayerId = session?.playerId,
                        )
                    )
                }

                post("/players") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    if (call.extractSession(jwtService, tableId) != null) {
                        return@post call.respond(
                            HttpStatusCode.Conflict,
                            mapOf("error" to "Already have a session for this table")
                        )
                    }

                    val request = call.receive<JoinRequest>()
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val playerId = (table.currentState.players.maxOfOrNull { it.id } ?: -1) + 1
                    val player = Player(playerId, request.playerName)

                    // TODO: Possible concurrency problem here.
                    // TODO: If two players try to join at the same time, they can end up getting the same JWT as well as player i + 1 being created twice.
                    if (!table.playerJoin(player)) {
                        return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Table is full or closed"))
                    }

                    val token = jwtService.createToken(tableId, playerId)
                    call.response.cookies.append(
                        Cookie(
                            name = jwtService.cookieName(tableId),
                            value = token,
                            path = "/",
                            httpOnly = true,
                        )
                    )

                    connectionManager.broadcastGameState(table.currentState)

                    call.respond(
                        HttpStatusCode.Created,
                        JoinResponse(playerId = playerId, playerName = request.playerName)
                    )
                }

                post("/action") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    val session = call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val request = call.receive<ActionRequest>()
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val roundState = table.currentState.roundState
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No round in progress"))

                    if (table.currentState.isPaused) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Game is paused"))
                    }

                    if (!roundState.pokerRoundStage.isBettingRound()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not a betting round"))
                    }

                    if (roundState.playerOrdering.bettingPlayer().id != session.playerId) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not your turn"))
                    }

                    val command: PlayerCommand = when (request.type.uppercase()) {
                        "FOLD" -> Fold(session.playerId)
                        "CALL" -> Call(session.playerId)
                        "RAISE" -> Raise(session.playerId, request.value ?: 0)
                        "ALL_IN" -> AllIn(session.playerId)
                        else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid action"))
                    }

                    val player = table.currentState.players.find { it.id == session.playerId }
                    // TODO: Evaluate if this is truly necessary.
                    if (player?.status == PlayerStatus.IDLE) {
                        player.setAsOnline()
                    }

                    timerManager.cancelTimer(tableId)
                    table.processPlayerCommand(command)
                    connectionManager.broadcastGameState(table.currentState)

                    val newRound = table.currentState.roundState
                    if (newRound != null && newRound.pokerRoundStage.isBettingRound()) {
                        timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)
                    }

                    call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                }

                post("/start-round") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val existingRound = table.currentState.roundState
                    if (existingRound != null && existingRound.pokerRoundStage.isBettingRound()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Round already in progress")
                        )
                    }

                    if (existingRound != null) {
                        table.clearRoundState()
                    }

                    val participating = table.currentState.players.participating()
                    if (participating.size < 2) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Need at least 2 players"))
                    }

                    table.advancePlayerOrdering()
                    table.newPokerRound()
                    connectionManager.broadcastGameState(table.currentState)

                    timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "round_started"))
                }

                post("/restart-game") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val existingRound = table.currentState.roundState
                    if (existingRound != null && existingRound.pokerRoundStage.isBettingRound()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cannot restart during a round")
                        )
                    }

                    // TODO: Require the game to have ended to restart the game.

                    if (existingRound != null) {
                        table.clearRoundState()
                    }

                    table.restartGame()
                    connectionManager.broadcastGameState(table.currentState)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "game_restarted"))
                }

                // TODO: Rename route to set-player-online
                post("/activate") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    val session = call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val player = table.currentState.players.find { it.id == session.playerId }
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Player not found"))

                    if (player.status != PlayerStatus.IDLE) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Player is not idle"))
                    }

                    player.setAsOnline()
                    persistence.saveState(table.currentState)
                    connectionManager.broadcastGameState(table.currentState)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "activated"))
                }

                post("/pause") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    val session = call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    if (table.currentState.isPaused) {
                        // TODO: Does not classify as BadRequest. Possibly change to 200 OK.
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Already paused"))
                    }

                    val onlineCount = connectionManager.getOnlinePlayerCount(tableId)
                    // TODO: Modify how voting works.
                    // TODO: Currently, we're tracking only for positive votes.
                    //  If players don't want to pause the game for instance, they cannot declare so and must wait until the timeout for the voting to actually end.
                    //  This can cause a problem when a new voting session for the same type is started shortly after voting "no".
                    //  In the current implementation, the voting session will still exist and the voting timeout will overlap.
                    val result = voteManager.vote(tableId, session.playerId, VoteType.PAUSE, onlineCount)

                    if (result.passed) {
                        val remainingMs = timerManager.pauseTimer(tableId)
                        table.pause(remainingMs)
                        connectionManager.broadcastMessage(tableId, "paused", "Game paused")
                        connectionManager.broadcastGameState(table.currentState)
                    } else {
                        connectionManager.broadcastMessage(
                            tableId, "vote_update",
                            "Pause vote: ${result.currentVotes}/${result.requiredVotes}"
                        )
                    }

                    call.respond(HttpStatusCode.OK, mapOf("status" to result.message))
                }

                post("/unpause") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    val session = call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    if (!table.currentState.isPaused) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not paused"))
                    }

                    val onlineCount = connectionManager.getOnlinePlayerCount(tableId)
                    val result = voteManager.vote(tableId, session.playerId, VoteType.UNPAUSE, onlineCount)

                    if (result.passed) {
                        val remainingMs = table.unpause()
                        connectionManager.broadcastMessage(tableId, "unpaused", "Game resumed")
                        connectionManager.broadcastGameState(table.currentState)

                        if (remainingMs != null && remainingMs > 0) {
                            timerManager.startTimer(table, remainingMs)
                        }
                    } else {
                        connectionManager.broadcastMessage(
                            tableId, "vote_update",
                            "Unpause vote: ${result.currentVotes}/${result.requiredVotes}"
                        )
                    }

                    call.respond(HttpStatusCode.OK, mapOf("status" to result.message))
                }

                post("/kick") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    val session = call.extractSession(jwtService, tableId)
                        ?: return@post call.respondUnauthorized()

                    val request = call.receive<KickRequest>()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val target = table.currentState.players.find { it.id == request.targetPlayerId }
                        ?: return@post call.respond(
                            HttpStatusCode.NotFound,
                            mapOf("error" to "Target player not found")
                        )

                    if (target.status != PlayerStatus.OFFLINE && target.status != PlayerStatus.IDLE) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Can only kick offline or idle players")
                        )
                    }

                    val onlineCount = connectionManager.getOnlinePlayerCount(tableId)
                    val result =
                        voteManager.vote(tableId, session.playerId, VoteType.KICK, onlineCount, request.targetPlayerId)

                    if (result.passed) {
                        table.kickPlayer(request.targetPlayerId)
                        connectionManager.broadcastMessage(tableId, "player_kicked", "${target.name} was kicked")
                        connectionManager.broadcastGameState(table.currentState)
                    } else {
                        connectionManager.broadcastMessage(
                            tableId, "vote_update",
                            "Kick ${target.name}: ${result.currentVotes}/${result.requiredVotes}"
                        )
                    }

                    call.respond(HttpStatusCode.OK, mapOf("status" to result.message))
                }

                patch("/settings") {
                    val tableId = call.parameters["tableId"]?.toIntOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, "Invalid table ID")

                    call.extractSession(jwtService, tableId)
                        ?: return@patch call.respondUnauthorized()

                    val request = call.receive<SettingsRequest>()

                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    table.updateConfig(isOpen = request.isOpen)
                    connectionManager.broadcastGameState(table.currentState)

                    call.respond(HttpStatusCode.OK, mapOf("status" to "settings_updated"))
                }
            }
        }
    }
}
