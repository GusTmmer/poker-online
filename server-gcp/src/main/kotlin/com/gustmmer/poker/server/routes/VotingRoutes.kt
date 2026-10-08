package com.gustmmer.poker.server.routes

import com.gustmmer.poker.ActiveVote
import com.gustmmer.poker.server.service.VotingService
import com.gustmmer.poker.server.service.respond
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.session.requireSession
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

            val session = call.requireSession(jwtService, tableId) ?: return@post

            val request = call.receive<CreateVoteSessionRequest>()

            // VoteResolution.of is the one mapping from the wire name; it rejects unknown types and a kick without a target.
            val resolution = runCatching { VoteResolution.of(request.resolution, request.targetPlayerId) }
                .getOrElse { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to (it.message ?: "Invalid resolution"))) }

            call.respond(votingService.createSession(tableId, resolution, session.playerId))
        }

        put<TableVoteResource> { resource ->
            val tableId = resource.tableId
            val sessionId = resource.sessionId

            val playerSession = call.requireSession(jwtService, tableId) ?: return@put

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

            call.requireSession(jwtService, tableId) ?: return@get

            call.respond(HttpStatusCode.OK, votingService.openSessions(tableId))
        }
    }
}
