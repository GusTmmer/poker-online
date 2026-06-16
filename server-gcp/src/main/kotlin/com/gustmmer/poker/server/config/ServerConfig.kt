package com.gustmmer.poker.server.config

data class ServerConfig(
    val port: Int,
    val jwtSecret: String,
    val firestoreProjectId: String,
    val allowedOrigin: String = "*",
    val voteTimeoutSeconds: Long = 60,
) {
    companion object {
        fun fromEnvironment() = ServerConfig(
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
            jwtSecret = System.getenv("JWT_SECRET") ?: "dev-secret-change-in-production",
            firestoreProjectId = System.getenv("GCP_PROJECT_ID") ?: "poker-online-dev",
            allowedOrigin = System.getenv("ALLOWED_ORIGIN") ?: "*",
        )
    }
}
