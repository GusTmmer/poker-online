package com.gustmmer.poker.server.session

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

data class PlayerSession(val tableId: Int, val playerId: Int)

class JwtService(secret: String) {
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

    fun cookieName(tableId: Int) = "poker_table_$tableId"
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
