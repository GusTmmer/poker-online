package com.gustmmer.poker.server.routes

import com.gustmmer.poker.*
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.session.extractSession
import com.gustmmer.poker.server.session.respondUnauthorized
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.voting.*
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.routing
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
    val hasSession: Boolean = false,
    val gameStatus: String = "WAITING",
)

@Serializable
data class PlayerInfo(val id: Int, val name: String, val status: String, val chips: Int = 0)

fun Application.configureTableRoutes(
    persistence: PokerTablePersistence,
    jwtService: JwtService,
    connectionManager: TableConnectionManager,
    voteManager: VoteManager,
    timerManager: TurnTimerManager,
) {
    routing {
        post<TablesResource> {
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

        get<TableResource> { resource ->
            val tableId = resource.tableId

            val state = persistence.loadState(tableId)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

            val session = call.extractSession(jwtService, tableId)

            call.respond(
                TableInfoResponse(
                    tableId = state.id,
                    players = state.players.map { PlayerInfo(it.id, it.name, it.status.name, it.chips) },
                    isOpen = state.config.isOpen,
                    maxPlayers = state.config.maxPlayers,
                    sessionPlayerId = session?.playerId,
                    hasSession = session != null,
                    gameStatus = state.gameStatus.name,
                )
            )
        }

        post<TablePlayersResource> { resource ->
            val tableId = resource.tableId

            if (call.extractSession(jwtService, tableId) != null) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    mapOf("error" to "Already have a session for this table")
                )
            }

            val request = call.receive<JoinRequest>()
            var attempts = 0
            while (true) {
                try {
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val playerId = (table.currentState.players.maxOfOrNull { it.id } ?: -1) + 1
                    val player = Player(playerId, request.playerName)

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
                    return@post call.respond(
                        HttpStatusCode.Created,
                        JoinResponse(playerId = playerId, playerName = request.playerName)
                    )
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        post<TableActionResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            val request = call.receive<ActionRequest>()

            val command: PlayerCommand = when (request.type.uppercase()) {
                "FOLD" -> Fold(session.playerId)
                "CALL" -> Call(session.playerId)
                "RAISE" -> Raise(session.playerId, request.value ?: 0)
                "ALL_IN" -> AllIn(session.playerId)
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid action"))
            }

            var attempts = 0
            while (true) {
                try {
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val roundState = table.currentState.roundState
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No round in progress"))

                    if (table.currentState.gameStatus == GameStatus.PAUSED) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Game is paused"))
                    }

                    if (!roundState.pokerRoundStage.isBettingRound()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not a betting round"))
                    }

                    if (roundState.playerOrdering.bettingPlayer().id != session.playerId) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not your turn"))
                    }

                    val player = table.currentState.players.find { it.id == session.playerId }
                    if (player?.status == PlayerStatus.IDLE) {
                        player.setAsOnline()
                    }

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

                    return@post call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to (e.message ?: "Invalid action"))
                    )
                }
            }
        }

        post<TableStartRoundResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            var attempts = 0
            while (true) {
                try {
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

                    table.newPokerRound()
                    connectionManager.broadcastGameState(table.currentState)
                    timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)

                    return@post call.respond(HttpStatusCode.OK, mapOf("status" to "round_started"))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        post<TableRestartGameResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            var attempts = 0
            while (true) {
                try {
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    val existingRound = table.currentState.roundState
                    if (existingRound != null && existingRound.pokerRoundStage.isBettingRound()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cannot restart during a round")
                        )
                    }

                    if (!table.currentState.isGameOver()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Cannot restart while the game is still active")
                        )
                    }

                    if (existingRound != null) {
                        table.clearRoundState()
                    }

                    table.restartGame()
                    connectionManager.broadcastGameState(table.currentState)

                    return@post call.respond(HttpStatusCode.OK, mapOf("status" to "game_restarted"))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        post<TableReadyResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            var attempts = 0
            while (true) {
                try {
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    if (table.currentState.gameStatus != GameStatus.WAITING) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Game is not in waiting state")
                        )
                    }

                    if (table.currentState.isGameOver()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Game is over, use /restart-game to play again")
                        )
                    }

                    val allReady = table.playerReady(session.playerId)
                    connectionManager.broadcastGameState(table.currentState)

                    if (allReady) {
                        val completedRound = table.currentState.roundState
                        if (completedRound != null) {
                            table.clearRoundState()
                        }
                        table.newPokerRound()
                        connectionManager.broadcastGameState(table.currentState)
                        timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)
                    }

                    return@post call.respond(HttpStatusCode.OK, mapOf("status" to if (allReady) "round_started" else "ready"))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        // TODO: Rename route to set-player-online
        post<TableActivateResource> { resource ->
            val tableId = resource.tableId

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

            val roundState = table.currentState.roundState
            if (roundState != null &&
                table.currentState.gameStatus == GameStatus.RUNNING &&
                roundState.pokerRoundStage.isBettingRound() &&
                roundState.playerOrdering.bettingPlayer().id == session.playerId
            ) {
                timerManager.startTimer(table, table.currentState.config.turnTimerSeconds * 1000L)
            }

            call.respond(HttpStatusCode.OK, mapOf("status" to "activated"))
        }

        post<TablePauseResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            var attempts = 0
            while (true) {
                try {
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    if (table.currentState.gameStatus == GameStatus.PAUSED) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Already paused"))
                    }

                    val eligibleVoters = table.currentState.players.participating().map { it.id }.toSet()
                    val result = voteManager.createSession(tableId, VoteResolution.PauseGame, eligibleVoters, session.playerId)
                    val activeVotes = voteManager.getOpenSessions(tableId).map { it.toSummary() }

                    if (result.outcome == VoteOutcome.PASSED) {
                        val remainingMs = timerManager.pauseTimer(tableId)
                        table.pause(remainingMs)
                        connectionManager.broadcastMessage(tableId, "paused", "Game paused")
                        connectionManager.broadcastGameState(table.currentState, activeVotes)
                    } else {
                        connectionManager.broadcastGameState(table.currentState, activeVotes)
                    }

                    val statusMsg = if (result.outcome == VoteOutcome.PASSED) "Vote passed"
                        else "Vote recorded (${result.session.yesVoters.size}/${result.session.requiredVotes})"
                    return@post call.respond(HttpStatusCode.OK, mapOf(
                        "status" to statusMsg,
                        "sessionId" to result.session.id,
                    ))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        post<TableUnpauseResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            var attempts = 0
            while (true) {
                try {
                    val table = PokerTable.restore(tableId, persistence)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

                    if (table.currentState.gameStatus != GameStatus.PAUSED) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Not paused"))
                    }

                    val eligibleVoters = table.currentState.players.participating().map { it.id }.toSet()
                    val result = voteManager.createSession(tableId, VoteResolution.UnpauseGame, eligibleVoters, session.playerId)
                    val activeVotes = voteManager.getOpenSessions(tableId).map { it.toSummary() }

                    if (result.outcome == VoteOutcome.PASSED) {
                        val remainingMs = table.unpause()
                        connectionManager.broadcastMessage(tableId, "unpaused", "Game resumed")
                        connectionManager.broadcastGameState(table.currentState, activeVotes)

                        if (remainingMs != null && remainingMs > 0) {
                            timerManager.startTimer(table, remainingMs)
                        }
                    } else {
                        connectionManager.broadcastGameState(table.currentState, activeVotes)
                    }

                    val statusMsg = if (result.outcome == VoteOutcome.PASSED) "Vote passed"
                        else "Vote recorded (${result.session.yesVoters.size}/${result.session.requiredVotes})"
                    return@post call.respond(HttpStatusCode.OK, mapOf(
                        "status" to statusMsg,
                        "sessionId" to result.session.id,
                    ))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        post<TableKickResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            val request = call.receive<KickRequest>()

            var attempts = 0
            while (true) {
                try {
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

                    val eligibleVoters = table.currentState.players
                        .filter { it.id != request.targetPlayerId }
                        .participating().map { it.id }.toSet()
                    val result = voteManager.createSession(
                        tableId, VoteResolution.KickPlayer(request.targetPlayerId), eligibleVoters, session.playerId
                    )
                    val activeVotes = voteManager.getOpenSessions(tableId).map { it.toSummary() }

                    if (result.outcome == VoteOutcome.PASSED) {
                        table.kickPlayer(request.targetPlayerId)
                        connectionManager.broadcastMessage(tableId, "player_kicked", "${target.name} was kicked")
                        connectionManager.broadcastGameState(table.currentState, activeVotes)
                    } else {
                        connectionManager.broadcastGameState(table.currentState, activeVotes)
                    }

                    val statusMsg = if (result.outcome == VoteOutcome.PASSED) "Vote passed"
                        else "Vote recorded (${result.session.yesVoters.size}/${result.session.requiredVotes})"
                    return@post call.respond(HttpStatusCode.OK, mapOf(
                        "status" to statusMsg,
                        "sessionId" to result.session.id,
                    ))
                } catch (_: ConcurrentModificationException) {
                    if (++attempts >= 3) return@post call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Concurrent update, please retry")
                    )
                }
            }
        }

        patch<TableSettingsResource> { resource ->
            val tableId = resource.tableId

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
