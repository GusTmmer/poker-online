package com.gustmmer.poker.server.routes

import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.isGameOver
import com.gustmmer.poker.participating
import com.gustmmer.poker.persistence.PokerTablePersistence
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
data class CreateVoteSessionRequest(
    val resolution: String,
    val targetPlayerId: Int? = null,
)

@Serializable
data class CastVoteRequest(val vote: String)

@Serializable
data class VoteSessionResponse(
    val sessionId: String,
    val resolutionType: String,
    val targetPlayerId: Int? = null,
    val yesCount: Int,
    val noCount: Int,
    val requiredVotes: Int,
    val outcome: String,
)

fun VoteResult.toResponse() = VoteSessionResponse(
    sessionId = session.id,
    resolutionType = session.resolution.typeName,
    targetPlayerId = session.resolution.kickTargetId,
    yesCount = session.yesVoters.size,
    noCount = session.noVoters.size,
    requiredVotes = session.requiredVotes,
    outcome = outcome.name,
)

fun Application.configureVotingRoutes(
    persistence: PokerTablePersistence,
    jwtService: JwtService,
    connectionManager: TableConnectionManager,
    voteManager: VoteManager,
    timerManager: TurnTimerManager,
) {
    routing {
        post<TableVotingSessionsResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            val request = call.receive<CreateVoteSessionRequest>()

            val resolution: VoteResolution = when (request.resolution) {
                "PAUSE_GAME" -> VoteResolution.PauseGame
                "UNPAUSE_GAME" -> VoteResolution.UnpauseGame
                "KICK_PLAYER" -> {
                    val target = request.targetPlayerId
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "targetPlayerId required for KICK_PLAYER"))
                    VoteResolution.KickPlayer(target)
                }
                "RESTART_GAME" -> VoteResolution.RestartGame
                "INCREASE_BLINDS" -> VoteResolution.IncreaseBlinds
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown resolution type"))
            }

            val table = PokerTable.restore(tableId, persistence)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

            if (resolution is VoteResolution.RestartGame && !table.currentState.isGameOver()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Cannot restart while game is still active")
                )
            }

            val eligibleVoters = table.currentState.players.participating().map { it.id }.toSet()
            val result = voteManager.createSession(tableId, resolution, eligibleVoters, session.playerId)
            val activeVotes = voteManager.getOpenSessions(tableId).map { it.toSummary() }

            executeResolution(result, table, timerManager, connectionManager, activeVotes)

            call.respond(HttpStatusCode.OK, result.toResponse())
        }

        put<TableVoteResource> { resource ->
            val tableId = resource.tableId
            val sessionId = resource.sessionId

            val playerSession = call.extractSession(jwtService, tableId)
                ?: return@put call.respondUnauthorized()

            val request = call.receive<CastVoteRequest>()
            val yes = when (request.vote.lowercase()) {
                "yes" -> true
                "no" -> false
                else -> return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "vote must be 'yes' or 'no'"))
            }

            val result = voteManager.castVote(sessionId, playerSession.playerId, yes)
                ?: return@put call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Voting session not found or player not eligible")
                )

            val table = PokerTable.restore(tableId, persistence)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Table not found"))

            val activeVotes = voteManager.getOpenSessions(tableId).map { it.toSummary() }
            executeResolution(result, table, timerManager, connectionManager, activeVotes)

            call.respond(HttpStatusCode.OK, result.toResponse())
        }

        get<TableVotingSessionsResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@get call.respondUnauthorized()

            val sessions = voteManager.getOpenSessions(tableId).map { it.toSummary() }
            call.respond(HttpStatusCode.OK, sessions)
        }
    }
}

private suspend fun executeResolution(
    result: VoteResult,
    table: PokerTable,
    timerManager: TurnTimerManager,
    connectionManager: TableConnectionManager,
    activeVotes: List<VoteSummary>,
) {
    if (result.outcome != VoteOutcome.PASSED) {
        connectionManager.broadcastGameState(table.currentState, activeVotes)
        return
    }

    val tableId = table.id
    when (val resolution = result.session.resolution) {
        is VoteResolution.PauseGame -> {
            val remainingMs = timerManager.pauseTimer(tableId)
            table.pause(remainingMs)
        }
        is VoteResolution.UnpauseGame -> {
            val remainingMs = table.unpause()
            if (remainingMs != null && remainingMs > 0) {
                timerManager.startTimer(table, remainingMs)
            }
        }
        is VoteResolution.KickPlayer -> table.kickPlayer(resolution.targetPlayerId)
        is VoteResolution.RestartGame -> table.restartGame()
        is VoteResolution.IncreaseBlinds -> {
            // Blind escalation is built into newPokerRound — this resolution is a no-op for now
        }
    }

    connectionManager.broadcastGameState(table.currentState, activeVotes)
}
