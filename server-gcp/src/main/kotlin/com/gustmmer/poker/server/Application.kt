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

/** Per-IP rate-limit bucket for read endpoints that fan out Firestore reads (e.g. `GET /api/my-tables`). */
val ReadRateLimit = RateLimitName("reads")

/**
 * The client identity to key rate limits on, resistant to `X-Forwarded-For` spoofing.
 *
 * Cloud Run's front end *appends* the connection's verified IP as the **last** entry of
 * `X-Forwarded-For`; any earlier entries are attacker-supplied and must not be trusted. So we take the
 * right-most entry, not the left-most. With no XFF header (local dev / direct connection) we fall back
 * to the socket peer. This is the whole reason `XForwardedHeaders` is not installed — it would trust the
 * left-most (spoofable) entry and let a client mint a new bucket per request.
 */
fun io.ktor.server.request.ApplicationRequest.rateLimitClientKey(): String {
    val forwarded = headers["X-Forwarded-For"]
    if (!forwarded.isNullOrBlank()) {
        forwarded.split(',').map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let { return it }
    }
    return origin.remoteHost
}

fun main() {
    val config = ServerConfig.fromEnvironment()
    config.assertSecretsAreSet()
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

    val jwtService = JwtService(config.jwtSecret, config.secureCookies)
    val connectionManager = TableConnectionManager(bus)
    val timerManager = TurnTimerManager(persistence, scheduler, config.botDelayScale)
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
 *
 * The probe path is `/health`, not `/healthz`: Cloud Run's infrastructure intercepts the exact path
 * `/healthz` for its own internal use and never forwards it to the container (a documented gotcha —
 * requests to it 404 at the edge, before reaching this code at all).
 */
fun Application.configureStaticAndHealth(config: ServerConfig) {
    routing {
        get("/health") { call.respondText("ok") }

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

    // NOTE: XForwardedHeaders is deliberately NOT installed. It rewrites `origin.remoteHost` to the
    // left-most X-Forwarded-For entry, which is fully client-supplied — a client can rotate that header
    // to mint a fresh rate-limit bucket per request and defeat the per-IP cap entirely. We derive the
    // client IP ourselves from the *trusted* hop instead (see rateLimitClientKey).

    install(RateLimit) {
        register(MutationRateLimit) {
            rateLimiter(limit = config.rateLimitMutations, refillPeriod = config.rateLimitRefillSeconds.seconds)
            requestKey { call -> call.request.rateLimitClientKey() }
        }
        register(ReadRateLimit) {
            rateLimiter(limit = config.rateLimitReads, refillPeriod = config.rateLimitReadRefillSeconds.seconds)
            requestKey { call -> call.request.rateLimitClientKey() }
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
        // Bound frame size so a malicious client can't stream an unbounded frame and exhaust the instance
        // heap (512Mi, up to 250 concurrent connections). This governs both directions, so the ceiling
        // sits well above the largest legitimate server→client game_state (a 10-player showdown with pots
        // and per-street runout odds is a few tens of KB) while still being tiny next to a DoS payload.
        // Clients themselves never send game data here — all actions are REST — only control/keepalive frames.
        maxFrameSize = 256 * 1024
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
