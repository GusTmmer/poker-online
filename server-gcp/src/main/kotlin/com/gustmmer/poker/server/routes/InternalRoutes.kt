package com.gustmmer.poker.server.routes

import com.gustmmer.poker.server.service.VotingService
import com.gustmmer.poker.server.timer.TurnTimerManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class TimerExpireBody(val token: Long)

/** Constant-time comparison so the shared secret can't be probed by response timing. */
private fun secretMatches(provided: String?, expected: String): Boolean =
    provided != null && MessageDigest.isEqual(provided.toByteArray(), expected.toByteArray())

/**
 * Endpoints called by Cloud Tasks, not players. Guarded by a shared-secret header so only the task
 * queue can drive timer/vote expiry; everything else is rejected. (In the in-memory dev/test setup
 * these are unused — the scheduler invokes the handlers directly — but registering them is harmless.)
 *
 * A stronger production option is to drop the shared secret and verify the OIDC token Cloud Tasks can
 * attach instead; the shared secret is the simpler, equally-gated choice for this project.
 */
fun Application.configureInternalRoutes(
    timerManager: TurnTimerManager,
    votingService: VotingService,
    internalToken: String,
) {
    routing {
        post("/internal/timer-expire/{tableId}") {
            if (!secretMatches(call.request.headers["X-Internal-Token"], internalToken)) {
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            val tableId = call.parameters["tableId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<TimerExpireBody>()
            timerManager.onTimerFired(tableId, body.token)
            call.respond(HttpStatusCode.OK)
        }

        post("/internal/vote-expire/{tableId}/{sessionId}") {
            if (!secretMatches(call.request.headers["X-Internal-Token"], internalToken)) {
                return@post call.respond(HttpStatusCode.Unauthorized)
            }
            val tableId = call.parameters["tableId"]?.toIntOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val sessionId = call.parameters["sessionId"]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            votingService.onVoteExpired(tableId, sessionId)
            call.respond(HttpStatusCode.OK)
        }
    }
}
