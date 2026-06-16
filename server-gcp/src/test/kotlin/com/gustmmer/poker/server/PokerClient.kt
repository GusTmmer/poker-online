package com.gustmmer.poker.server

import com.gustmmer.poker.server.routes.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*

/**
 * Typed HTTP client for the poker server API.
 * Every request targets a [@Resource]-annotated class from [com.gustmmer.poker.server.routes] —
 * test code never contains a raw URL string, and the server's typed route registration is the
 * single source of truth each path is checked against at compile time.
 */
class PokerClient(private val http: HttpClient) {

    /** Outcome of endpoints that return {"status": "..."} or {"error": "..."}. */
    data class ApiResult(
        val status: HttpStatusCode,
        val statusField: String? = null,
        val error: String? = null,
    ) {
        fun isOk() = status.isSuccess()
    }

    // ── Table management ──────────────────────────────────────────────────────

    suspend fun createTable(
        playerName: String,
        startingChips: Int = 500,
        turnTimerSeconds: Int = 300,
        maxPlayers: Int = 6,
        blindEscalationOrbits: Int = 2,
        blindEscalationMultiplier: Double = 2.0,
    ): CreateTableResponse =
        http.post(TablesResource()) {
            setJsonBody(CreateTableRequest(playerName, startingChips, turnTimerSeconds, maxPlayers, blindEscalationOrbits, blindEscalationMultiplier))
        }.body()

    suspend fun getTable(tableId: Int): TableInfoResponse =
        http.get(TableResource(tableId)).body()

    suspend fun joinTable(tableId: Int, playerName: String): HttpStatusCode =
        http.post(TablePlayersResource(tableId)) {
            setJsonBody(JoinRequest(playerName))
        }.status

    // ── Game flow ─────────────────────────────────────────────────────────────

    suspend fun startRound(tableId: Int): ApiResult =
        http.post(TableStartRoundResource(tableId)).toApiResult()

    suspend fun ready(tableId: Int): ApiResult =
        http.post(TableReadyResource(tableId)).toApiResult()

    suspend fun restartGame(tableId: Int): ApiResult =
        http.post(TableRestartGameResource(tableId)).toApiResult()

    // ── Poker actions ─────────────────────────────────────────────────────────

    suspend fun fold(tableId: Int): ApiResult = sendAction(tableId, ActionRequest("FOLD"))
    suspend fun call(tableId: Int): ApiResult = sendAction(tableId, ActionRequest("CALL"))
    suspend fun raise(tableId: Int, amount: Int): ApiResult = sendAction(tableId, ActionRequest("RAISE", amount))
    suspend fun allIn(tableId: Int): ApiResult = sendAction(tableId, ActionRequest("ALL_IN"))

    private suspend fun sendAction(tableId: Int, request: ActionRequest): ApiResult =
        http.post(TableActionResource(tableId)) { setJsonBody(request) }.toApiResult()

    // ── Voting ────────────────────────────────────────────────────────────────

    suspend fun createVote(tableId: Int, resolution: String, targetPlayerId: Int? = null): VoteSessionResponse =
        http.post(TableVotingSessionsResource(tableId)) {
            setJsonBody(CreateVoteSessionRequest(resolution, targetPlayerId))
        }.body()

    /** Returns null when the session no longer exists (404); throws on any other error. */
    suspend fun castVote(tableId: Int, sessionId: String, vote: String): VoteSessionResponse? {
        val resp = http.put(TableVoteResource(tableId, sessionId)) {
            setJsonBody(CastVoteRequest(vote))
        }
        return when (resp.status) {
            HttpStatusCode.OK -> resp.body()
            HttpStatusCode.NotFound -> null
            else -> error("Unexpected ${resp.status}: ${resp.bodyAsText()}")
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun HttpRequestBuilder.setJsonBody(body: Any) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.toApiResult(): ApiResult {
        val body = runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject }.getOrNull()
        return ApiResult(
            status = status,
            statusField = body?.get("status")?.jsonPrimitive?.contentOrNull,
            error = body?.get("error")?.jsonPrimitive?.contentOrNull,
        )
    }
}
