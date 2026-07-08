package com.gustmmer.poker.server.session

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

data class PlayerSession(val tableId: Int, val playerId: Int)

class JwtService(secret: String, private val secureCookies: Boolean = false) {
    private val algorithm = Algorithm.HMAC256(secret)
    private val verifier = JWT.require(algorithm).build()

    fun createToken(tableId: Int, playerId: Int): String {
        return JWT.create()
            .withClaim("tableId", tableId)
            .withClaim("playerId", playerId)
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
