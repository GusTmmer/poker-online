package com.gustmmer.poker.server.session

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

data class PlayerSession(val tableId: Int, val playerId: Int)

class JwtService(secret: String, private val secureCookies: Boolean = false) {
    private val algorithm = Algorithm.HMAC256(secret)
    // The verifier rejects an expired token automatically once the `exp` claim is present.
    private val verifier = JWT.require(algorithm).build()

    /**
     * Signs a session token for `(tableId, playerId)` that expires after [TOKEN_TTL_HOURS]. A bounded
     * lifetime means a leaked cookie can't be replayed forever — and the window comfortably covers a
     * table's own 12h Firestore TTL, so a live table never outlives its players' sessions.
     */
    fun createToken(tableId: Int, playerId: Int): String {
        val now = Instant.now()
        return JWT.create()
            .withClaim("tableId", tableId)
            .withClaim("playerId", playerId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS)))
            .sign(algorithm)
    }

    fun verify(token: String): PlayerSession? {
        return try {
            val decoded = verifier.verify(token)
            PlayerSession(
                tableId = decoded.getClaim("tableId").asInt(),
                playerId = decoded.getClaim("playerId").asInt(),
            )
        } catch (_: JWTVerificationException) {
            null
        }
    }

    fun cookieName(tableId: Int) = "$COOKIE_PREFIX$tableId"

    /**
     * The session cookie carrying [token] for [tableId]. `httpOnly` (JS can't read it), `SameSite=Lax`
     * (sent on top-level navigation, blocks cross-site CSRF), and `Secure` in production. All cookie
     * attributes live here so every set/clear site stays consistent.
     */
    fun sessionCookie(tableId: Int, token: String): Cookie =
        Cookie(
            name = cookieName(tableId),
            value = token,
            path = "/",
            httpOnly = true,
            secure = secureCookies,
            extensions = mapOf("SameSite" to "Lax"),
        )

    /** A same-attribute empty cookie that expires [tableId]'s session immediately (leave / stale prune). */
    fun expiredSessionCookie(tableId: Int): Cookie =
        Cookie(
            name = cookieName(tableId),
            value = "",
            path = "/",
            httpOnly = true,
            secure = secureCookies,
            maxAge = 0,
            extensions = mapOf("SameSite" to "Lax"),
        )

    companion object {
        /** Prefix for every per-table session cookie. Enumerated by the "my tables" discovery endpoint. */
        const val COOKIE_PREFIX = "poker_table_"

        /** Session-token lifetime. Kept above a table's 12h Firestore TTL so a live table's sessions stay valid. */
        const val TOKEN_TTL_HOURS = 24L
    }
}

fun RoutingCall.extractSession(jwtService: JwtService, tableId: Int): PlayerSession? {
    val cookieName = jwtService.cookieName(tableId)
    val token = request.cookies[cookieName] ?: return null
    val session = jwtService.verify(token) ?: return null
    if (session.tableId != tableId) return null
    return session
}

suspend fun RoutingCall.respondUnauthorized() {
    respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing session"))
}
