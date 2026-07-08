package com.gustmmer.poker.server.config

data class ServerConfig(
    val port: Int,
    val jwtSecret: String,
    val firestoreProjectId: String,
    val allowedOrigin: String = "*",
    val voteTimeoutSeconds: Long = 60,
    /**
     * Per-IP cap on table creation + join over [rateLimitRefillSeconds]. Guards the cheapest abuse
     * vector (scripted table creation burning Firestore write quota). Soft, in-memory, per-instance —
     * the hard cost ceiling is Cloud Run `max-instances` + the budget kill-switch.
     */
    val rateLimitMutations: Int = 30,
    val rateLimitRefillSeconds: Long = 60,
    /**
     * Per-IP cap on read endpoints that do per-request Firestore lookups (currently `GET /api/my-tables`,
     * which fans out one read per table cookie the browser holds). Kept separate from
     * [rateLimitMutations] so listing tables never consumes a player's create/join budget.
     */
    val rateLimitReads: Int = 60,
    val rateLimitReadRefillSeconds: Long = 60,
    /**
     * Directory of built frontend assets to serve at `/` (same-origin with the API, so no CORS and no
     * `SameSite=None` cookie). Null/absent → API only (dev uses the Vite proxy instead).
     */
    val staticDir: String? = null,
    /**
     * Shared secret guarding the internal endpoints that Cloud Tasks calls back (turn-timer expiry).
     * Cloud Tasks sets it as a header on the task; the route rejects anything else. Must be a strong
     * secret in production (Secret Manager).
     */
    val internalToken: String = DEFAULT_INTERNAL_TOKEN,
) {
    /**
     * Refuse to run with the insecure dev defaults. Called from the production entry point only —
     * otherwise a forgotten secret would leave the JWT signing key and the internal-endpoint guard set
     * to publicly-known values (anyone could forge sessions or trigger timer/vote expiry).
     */
    fun assertSecretsAreSet() {
        check(jwtSecret != DEFAULT_JWT_SECRET) { "JWT_SECRET is the insecure default — set it (Secret Manager)." }
        check(internalToken != DEFAULT_INTERNAL_TOKEN) { "INTERNAL_TOKEN is the insecure default — set it (Secret Manager)." }
    }

    companion object {
        const val DEFAULT_JWT_SECRET = "dev-secret-change-in-production"
        const val DEFAULT_INTERNAL_TOKEN = "dev-internal-token"

        fun fromEnvironment() = ServerConfig(
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            jwtSecret = System.getenv("JWT_SECRET") ?: DEFAULT_JWT_SECRET,
            firestoreProjectId = System.getenv("GCP_PROJECT_ID") ?: "poker-online-dev",
            allowedOrigin = System.getenv("ALLOWED_ORIGIN") ?: "*",
            voteTimeoutSeconds = System.getenv("VOTE_TIMEOUT_SECONDS")?.toLongOrNull() ?: 60,
            rateLimitMutations = System.getenv("RATE_LIMIT_MUTATIONS")?.toIntOrNull() ?: 30,
            rateLimitRefillSeconds = System.getenv("RATE_LIMIT_REFILL_SECONDS")?.toLongOrNull() ?: 60,
            rateLimitReads = System.getenv("RATE_LIMIT_READS")?.toIntOrNull() ?: 60,
            rateLimitReadRefillSeconds = System.getenv("RATE_LIMIT_READ_REFILL_SECONDS")?.toLongOrNull() ?: 60,
            staticDir = System.getenv("STATIC_DIR")?.takeIf { it.isNotBlank() },
            internalToken = System.getenv("INTERNAL_TOKEN") ?: DEFAULT_INTERNAL_TOKEN,
        )
    }
}
