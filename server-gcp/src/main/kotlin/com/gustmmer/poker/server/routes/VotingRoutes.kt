package com.gustmmer.poker.server.routes

import com.gustmmer.poker.ActiveVote
import com.gustmmer.poker.server.service.VotingService
import com.gustmmer.poker.server.service.respond
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.session.extractSession
import com.gustmmer.poker.server.session.respondUnauthorized
import com.gustmmer.poker.server.voting.VoteOutcome
import com.gustmmer.poker.server.voting.VoteResolution
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

fun ActiveVote.toResponse(outcome: VoteOutcome) = VoteSessionResponse(
    sessionId = id,
    resolutionType = resolutionType,
    targetPlayerId = targetPlayerId,
    yesCount = yesVoters.size,
    noCount = noVoters.size,
    requiredVotes = requiredVotes,
    outcome = outcome.name,
)

fun Application.configureVotingRoutes(
    jwtService: JwtService,
    votingService: VotingService,
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
                else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown resolution type"))
            }

            call.respond(votingService.createSession(tableId, resolution, session.playerId))
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

            val result = votingService.castVote(tableId, sessionId, playerSession.playerId, yes)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("error" to "Voting session not found or player not eligible"))

            call.respond(result)
        }

        get<TableVotingSessionsResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@get call.respondUnauthorized()

            call.respond(HttpStatusCode.OK, votingService.openSessions(tableId))
        }
    }
}
