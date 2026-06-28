package com.gustmmer.poker.server

import com.google.cloud.firestore.FirestoreOptions
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.bus.FirestoreTableUpdateBus
import com.gustmmer.poker.server.bus.TableUpdateBus
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.FirestorePokerTablePersistence
import com.gustmmer.poker.server.routes.configureInternalRoutes
import com.gustmmer.poker.server.routes.configureTableRoutes
import com.gustmmer.poker.server.routes.configureVotingRoutes
import com.gustmmer.poker.server.routes.configureWebSocketRoutes
import com.gustmmer.poker.server.service.GameService
import com.gustmmer.poker.server.service.VotingService
import com.gustmmer.poker.server.session.JwtService
import com.gustmmer.poker.server.timer.CloudTasksScheduler
import com.gustmmer.poker.server.timer.TaskScheduler
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.server.plugins.origin
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.seconds

/** Per-IP rate-limit bucket for state-creating endpoints (table create + join). */
val MutationRateLimit = RateLimitName("mutations")

fun main() {
    val config = ServerConfig.fromEnvironment()
    // One Firestore client shared by the persistence (reads/writes) and the update bus (snapshot
    // listeners) so they talk to the same database.
    val firestore = FirestoreOptions.newBuilder().setProjectId(config.firestoreProjectId).build().service
    val persistence = FirestorePokerTablePersistence(firestore)
    val bus = FirestoreTableUpdateBus(firestore)
    // Durable turn timers via Cloud Tasks: the queue calls /internal/timer-expire back on this service.
    val scheduler = CloudTasksScheduler(
        projectId = config.firestoreProjectId,
        location = System.getenv("CLOUD_TASKS_LOCATION") ?: "us-central1",
        queue = System.getenv("CLOUD_TASKS_QUEUE") ?: "poker-timers",
        serviceUrl = System.getenv("SERVICE_URL") ?: error("SERVICE_URL must be set for Cloud Tasks callbacks"),
        internalToken = config.internalToken,
    )

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        configureServer(persistence, config, bus, scheduler)
    }.start(wait = true)
}

fun Application.configureServer(
    persistence: PokerTablePersistence,
    config: ServerConfig,
    bus: TableUpdateBus,
    scheduler: TaskScheduler,
) {
    configurePlugins(config)

    val jwtService = JwtService(config.jwtSecret)
    val connectionManager = TableConnectionManager(bus)
    val timerManager = TurnTimerManager(persistence, scheduler)
    // In-process schedulers fire through this handler; Cloud Tasks ignores it and uses the route below.
    scheduler.attachExpiry { tableId, token -> timerManager.onTimerFired(tableId, token) }

    val gameService = GameService(persistence, timerManager)
    val votingService = VotingService(persistence, timerManager, connectionManager, scheduler, config.voteTimeoutSeconds)
    scheduler.attachVoteExpiry { tableId, sessionId -> votingService.onVoteExpired(tableId, sessionId) }

    configureTableRoutes(jwtService, gameService, votingService)
    configureVotingRoutes(jwtService, votingService)
    configureWebSocketRoutes(jwtService, connectionManager, persistence)
    configureInternalRoutes(timerManager, votingService, config.internalToken)
    configureStaticAndHealth(config)
}

/**
 * Liveness/readiness probe plus optional same-origin frontend hosting. When [ServerConfig.staticDir]
 * is set the built SPA is served at `/` (with client-side-routing fallback), so the browser talks to
 * one origin for assets, REST, and WebSocket — no CORS, no cross-site cookie. Unset in dev/tests,
 * where the Vite proxy provides the same-origin illusion.
 */
fun Application.configureStaticAndHealth(config: ServerConfig) {
    routing {
        get("/healthz") { call.respondText("ok") }

        val dir = config.staticDir?.let(::File)?.takeIf { it.isDirectory }
        if (dir != null) {
            singlePageApplication {
                useResources = false
                filesPath = dir.path
                defaultPage = "index.html"
            }
        }
    }
}

fun Application.configurePlugins(config: ServerConfig) {
    install(Resources)

    // On Cloud Run the real client IP arrives in X-Forwarded-For; trust it so rate-limit keys are
    // per-client, not the single load-balancer hop.
    install(XForwardedHeaders)

    install(RateLimit) {
        register(MutationRateLimit) {
            rateLimiter(limit = config.rateLimitMutations, refillPeriod = config.rateLimitRefillSeconds.seconds)
            requestKey { call -> call.request.origin.remoteHost }
        }
    }

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
        // Browsers reject `Access-Control-Allow-Origin: *` together with credentials, and the JWT
        // travels as a cookie. So credentials are only enabled when a concrete origin is configured;
        // the wildcard is dev-only (works because Vite proxies same-origin) and must not ship.
        if (config.allowedOrigin == "*") {
            this@configurePlugins.log.warn("ALLOWED_ORIGIN is '*': credentialed cross-origin requests will fail. Set a concrete origin in production.")
            anyHost()
        } else {
            allowHost(config.allowedOrigin, schemes = listOf("https", "http"))
            allowCredentials = true
        }
        allowHeader("Content-Type")
        allowHeader("Authorization")
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
        allowMethod(io.ktor.http.HttpMethod.Patch)
        allowMethod(io.ktor.http.HttpMethod.Options)
    }
}
