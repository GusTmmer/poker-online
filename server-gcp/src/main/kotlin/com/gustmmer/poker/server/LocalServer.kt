package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import com.gustmmer.poker.server.timer.InMemoryTaskScheduler
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
        // Honor STATIC_DIR locally too, so the same-origin SPA hosting can be exercised without Docker.
        staticDir = System.getenv("STATIC_DIR")?.takeIf { it.isNotBlank() },
    )
    // In-memory analogue of the Firestore setup: NotifyingPersistence publishes each commit to the
    // in-memory bus, which the connection manager subscribes to — same fan-out path as production.
    val bus = InMemoryTableUpdateBus()
    val persistence = NotifyingPersistence(MemoryBasedPokerTablePersistence.json(), bus)
    val scheduler = InMemoryTaskScheduler()

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        configureServer(persistence, config, bus, scheduler)
    }.start(wait = true)
}
