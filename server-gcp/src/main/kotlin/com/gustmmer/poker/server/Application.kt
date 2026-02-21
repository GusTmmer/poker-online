package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.FirestorePokerTablePersistence
import com.gustmmer.poker.server.routes.configureTableRoutes
import com.gustmmer.poker.server.routes.configureWebSocketRoutes
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
    configurePlugins()

    val jwtService = JwtService(config.jwtSecret)
    val connectionManager = TableConnectionManager()
    val voteManager = VoteManager(connectionManager)
    val timerManager = TurnTimerManager(persistence, connectionManager)

    configureTableRoutes(persistence, jwtService, connectionManager, voteManager, timerManager)
    configureWebSocketRoutes(jwtService, connectionManager, persistence)
}

fun Application.configurePlugins() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(CORS) {
        // TODO: Evaluate 'anyHost'
        //  Since this will have a hosted FE, this may need to be changed.
        anyHost()
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowCredentials = true
        allowMethod(io.ktor.http.HttpMethod.Options)
        allowMethod(io.ktor.http.HttpMethod.Patch)
    }
}
