package com.gustmmer.poker.server.routes

import com.gustmmer.poker.server.MutationRateLimit
import com.gustmmer.poker.server.ReadRateLimit
import com.gustmmer.poker.server.service.GameService
import com.gustmmer.poker.server.service.ServiceResult
import com.gustmmer.poker.server.service.VotingService
import com.gustmmer.poker.server.service.respond
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.session.extractSession
import com.gustmmer.poker.server.session.respondUnauthorized
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class CreateTableRequest(
    val playerName: String,
    val name: String = "",
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
data class SettingsRequest(val isOpen: Boolean? = null, val name: String? = null)

@Serializable
data class TableInfoResponse(
    val tableId: Int,
    val name: String = "",
    val players: List<PlayerInfo>,
    val isOpen: Boolean,
    val maxPlayers: Int,
    val sessionPlayerId: Int? = null,
    val hasSession: Boolean = false,
    val gameStatus: String = "WAITING",
    val nextPlayerIdToAct: Int? = null,
)

@Serializable
data class PlayerInfo(val id: Int, val name: String, val status: String, val chips: Int = 0)

@Serializable
data class TableSummary(
    val tableId: Int,
    val name: String,
    val playerName: String,
    val gameStatus: String,
    val playerCount: Int,
    val maxPlayers: Int,
)

@Serializable
data class MyTablesResponse(val tables: List<TableSummary>)

fun Application.configureTableRoutes(
    jwtService: JwtService,
    gameService: GameService,
    votingService: VotingService,
) {
    routing {
        // Rate-limit the two endpoints that create Firestore documents — the cheapest abuse vector.
        rateLimit(MutationRateLimit) {
            post<TablesResource> {
                val request = call.receive<CreateTableRequest>()
                val created = gameService.createTable(request)

                val token = jwtService.createToken(created.tableId, created.playerId)
                call.response.cookies.append(jwtService.sessionCookie(created.tableId, token))

                call.respond(
                    HttpStatusCode.Created,
                    CreateTableResponse(
                        tableId = created.tableId,
                        joinLink = "/table/${created.tableId}",
                        playerId = created.playerId,
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
                val result = gameService.joinTable(tableId, request.playerName)

                if (result is ServiceResult.Ok) {
                    val token = jwtService.createToken(tableId, result.value.playerId)
                    call.response.cookies.append(jwtService.sessionCookie(tableId, token))
                }

                call.respond(result)
            }
        }

        // Discovery: which tables does this browser hold a session for? The per-table JWT cookies are
        // httpOnly (JS can't read them), so the server enumerates them, verifies each, and confirms the
        // player is still seated. Stale cookies (table expired or player kicked) are cleared in the response.
        rateLimit(ReadRateLimit) {
            get<MyTablesResource> {
                val sessions = call.request.cookies.rawCookies
                    .filterKeys { it.startsWith(JwtService.COOKIE_PREFIX) }
                    .values
                    .mapNotNull { jwtService.verify(it) }

                val summaries = sessions.mapNotNull { session ->
                    gameService.getTableSummary(session.tableId, session.playerId)
                        ?: run {
                            // Stale session — expire the cookie so it stops riding along on every request.
                            call.response.cookies.append(jwtService.expiredSessionCookie(session.tableId))
                            null
                        }
                }

                call.respond(MyTablesResponse(summaries))
            }
        }

        get<TableResource> { resource ->
            val tableId = resource.tableId
            val session = call.extractSession(jwtService, tableId)

            call.respond(gameService.getTableInfo(tableId, session?.playerId))
        }

        // Permanently leave a table: free the seat and drop the table from this browser's my-tables
        // list by clearing its session cookie. Distinct from the soft "go offline" a disconnect causes.
        delete<TablePlayerMeResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@delete call.respondUnauthorized()

            val result = gameService.leaveTable(tableId, session.playerId)
            call.response.cookies.append(jwtService.expiredSessionCookie(tableId))
            call.respond(result)
        }

        post<TableActionResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            val request = call.receive<ActionRequest>()
            call.respond(gameService.applyAction(tableId, session.playerId, request))
        }

        post<TableStartRoundResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            call.respond(gameService.startRound(tableId))
        }

        post<TableRestartGameResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            call.respond(gameService.restartGame(tableId))
        }

        post<TableReadyResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            call.respond(gameService.readyUp(tableId, session.playerId))
        }

        post<TableActivateResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            call.respond(gameService.setPlayerOnline(tableId, session.playerId))
        }

        post<TablePauseResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            call.respond(votingService.requestPause(tableId, session.playerId))
        }

        post<TableUnpauseResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            call.respond(votingService.requestUnpause(tableId, session.playerId))
        }

        post<TableKickResource> { resource ->
            val tableId = resource.tableId

            val session = call.extractSession(jwtService, tableId)
                ?: return@post call.respondUnauthorized()

            val request = call.receive<KickRequest>()
            call.respond(votingService.requestKick(tableId, session.playerId, request.targetPlayerId))
        }

        patch<TableSettingsResource> { resource ->
            val tableId = resource.tableId

            call.extractSession(jwtService, tableId)
                ?: return@patch call.respondUnauthorized()

            val request = call.receive<SettingsRequest>()
            call.respond(gameService.updateSettings(tableId, request.isOpen, request.name))
        }
    }
}
