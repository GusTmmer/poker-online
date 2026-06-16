package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.FirestorePokerTablePersistence
import com.gustmmer.poker.server.routes.configureTableRoutes
import com.gustmmer.poker.server.routes.configureVotingRoutes
import com.gustmmer.poker.server.routes.configureWebSocketRoutes
import com.gustmmer.poker.server.service.GameService
import com.gustmmer.poker.server.service.VotingService
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.voting.VoteManager
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.resources.*
import io.ktor.server.websocket.*
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

fun main() {
    val config = ServerConfig.fromEnvironment()
    val persistence = FirestorePokerTablePersistence(config.firestoreProjectId)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        configureServer(persistence, config)
    }.start(wait = true)
}

fun Application.configureServer(
    persistence: PokerTablePersistence,
    config: ServerConfig,
) {
    configurePlugins(config)

    val jwtService = JwtService(config.jwtSecret)
    val connectionManager = TableConnectionManager()
    val voteManager = VoteManager(connectionManager, persistence)
    val timerManager = TurnTimerManager(persistence, connectionManager)

    val gameService = GameService(persistence, connectionManager, timerManager)
    val votingService = VotingService(persistence, voteManager, timerManager, connectionManager)

    configureTableRoutes(jwtService, gameService, votingService)
    configureVotingRoutes(jwtService, votingService)
    configureWebSocketRoutes(jwtService, connectionManager, persistence, voteManager, timerManager)
}

fun Application.configurePlugins(config: ServerConfig) {
    install(Resources)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(CORS) {
        if (config.allowedOrigin == "*") anyHost() else allowHost(config.allowedOrigin, schemes = listOf("https", "http"))
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowCredentials = true
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowMethod(io.ktor.http.HttpMethod.Patch)
    }
}
