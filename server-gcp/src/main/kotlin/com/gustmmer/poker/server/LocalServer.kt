package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.config.ServerConfig
import io.ktor.server.engine.*
import io.ktor.server.netty.*

/**
 * Local development entry point. Uses in-memory persistence — state is lost on restart.
 * Run via: ./gradlew :server-gcp:runLocal
 */
fun main() {
    val config = ServerConfig(
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        jwtSecret = "local-dev-secret",
        firestoreProjectId = "local",
    )
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        configureServer(MemoryBasedPokerTablePersistence.json(), config)
    }.start(wait = true)
}
