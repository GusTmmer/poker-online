package com.gustmmer.poker.server.routes

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * Typed route definitions for the REST API. Each class is the single source of truth for its
 * path — server route registration and [com.gustmmer.poker.server.PokerClient] both reference
 * these classes instead of duplicating string paths.
 */
@Serializable
@Resource("/api/tables")
class TablesResource

@Serializable
@Resource("/api/my-tables")
class MyTablesResource

@Serializable
@Resource("/api/tables/{tableId}")
data class TableResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/players")
data class TablePlayersResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/players/me")
data class TablePlayerMeResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/action")
data class TableActionResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/start-round")
data class TableStartRoundResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/restart-game")
data class TableRestartGameResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/ready")
data class TableReadyResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/activate")
data class TableActivateResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/pause")
data class TablePauseResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/unpause")
data class TableUnpauseResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/kick")
data class TableKickResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/settings")
data class TableSettingsResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/voting-sessions")
data class TableVotingSessionsResource(val tableId: Int)

@Serializable
@Resource("/api/tables/{tableId}/voting-sessions/{sessionId}/vote")
data class TableVoteResource(val tableId: Int, val sessionId: String)
