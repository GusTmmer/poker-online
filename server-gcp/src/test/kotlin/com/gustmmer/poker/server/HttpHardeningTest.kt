package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import com.gustmmer.poker.server.routes.isAllowedSocketOrigin
import com.gustmmer.poker.server.timer.InMemoryTaskScheduler
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HttpHardeningTest {

    private fun ApplicationTestBuilder.app(staticDir: String? = null): HttpClient {
        val bus = InMemoryTableUpdateBus()
        val persistence = NotifyingPersistence(MemoryBasedPokerTablePersistence.json(), bus)
        val config = ServerConfig(port = 8080, jwtSecret = "test-secret", firestoreProjectId = "test", staticDir = staticDir)
        application { configureServer(persistence, config, bus, InMemoryTaskScheduler()) }
        return createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(WebSockets)
        }
    }

    private suspend fun HttpClient.createTable(): Int {
        val response = post("/api/tables") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["tableId"]!!.jsonPrimitive.int
    }

    @Test
    fun `every response refuses framing and sniffing`() = testApplication {
        val response = app().get("/health")

        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("frame-ancestors 'none'", response.headers["Content-Security-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("same-origin", response.headers["Referrer-Policy"])
    }

    @Test
    fun `hashed assets are cached for good, the SPA page is revalidated, and API responses are never stored`(
        @TempDir dir: File,
    ) = testApplication {
        File(dir, "index.html").writeText("<!doctype html><title>Poker</title>")
        File(dir, "assets").mkdirs()
        File(dir, "assets/index-abc123.js").writeText("console.log('hi')")
        val client = app(staticDir = dir.path)

        val asset = client.get("/assets/index-abc123.js")
        assertEquals(HttpStatusCode.OK, asset.status)
        assertEquals("public, max-age=31536000, immutable", asset.headers[HttpHeaders.CacheControl])

        // A client-side route falls back to index.html, which must not be served from cache after a deploy.
        val page = client.get("/table/42")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("<title>Poker</title>"))
        assertEquals("no-cache", page.headers[HttpHeaders.CacheControl])

        assertEquals("no-store", client.get("/api/my-tables").headers[HttpHeaders.CacheControl])
    }

    @Test
    fun `a socket opened from another site is refused before it sees the table`() = testApplication {
        val client = app()
        val tableId = client.createTable()

        client.webSocket("/ws/tables/$tableId", request = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "https://evil.example")
        }) {
            val reason = withTimeout(5_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            assertEquals("Origin not allowed", reason?.message)
        }
    }

    @Test
    fun `a socket opened from the page's own origin gets the table`() = testApplication {
        val client = app()
        val tableId = client.createTable()

        // A real browser handshake always carries Host; the test engine doesn't add one, so set it like a browser would.
        client.webSocket("/ws/tables/$tableId", request = {
            header(HttpHeaders.Host, "localhost")
            header(HttpHeaders.Origin, "http://localhost")
        }) {
            val frame = withTimeout(5_000) { incoming.receive() } as Frame.Text
            assertEquals("game_state", Json.parseToJsonElement(frame.readText()).jsonObject["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `socket origins allowed are the page's own, the configured host, and non-browser clients`() {
        assertTrue(isAllowedSocketOrigin(null, "poker.run.app", "*"))
        assertTrue(isAllowedSocketOrigin("https://poker.run.app", "poker.run.app", "*"))
        assertTrue(isAllowedSocketOrigin("http://localhost:5173", "localhost:5173", "*"))
        assertTrue(isAllowedSocketOrigin("https://play.example.com", "poker.run.app", "play.example.com"))

        assertFalse(isAllowedSocketOrigin("https://evil.example", "poker.run.app", "*"))
        assertFalse(isAllowedSocketOrigin("https://evil.example", "poker.run.app", "play.example.com"))
        // Same host, different port: a different origin.
        assertFalse(isAllowedSocketOrigin("http://localhost:6666", "localhost:5173", "*"))
        assertFalse(isAllowedSocketOrigin("null", "poker.run.app", "*"))
    }

    @Test
    fun `a player who left can't come back as whoever takes their seat`() = testApplication {
        val alice = app()
        val tableId = alice.createTable()

        val bob = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        val joined = bob.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Bob"}""")
        }
        val bobsId = Json.parseToJsonElement(joined.bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.int
        val bobsOldCookie = joined.setCookie().single { it.name == "poker_table_$tableId" }.value
        assertEquals(HttpStatusCode.OK, bob.delete("/api/tables/$tableId/players/me").status)

        val carol = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        val carolJoined = carol.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Carol"}""")
        }
        val carolsId = Json.parseToJsonElement(carolJoined.bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.int
        assertNotEquals(bobsId, carolsId)

        // Bob's leftover cookie still verifies, but it names his old id — nobody is seated there now.
        val asBob = client.get("/api/tables/$tableId") { header(HttpHeaders.Cookie, "poker_table_$tableId=$bobsOldCookie") }
        val info = Json.parseToJsonElement(asBob.bodyAsText()).jsonObject
        assertEquals(bobsId, info["sessionPlayerId"]!!.jsonPrimitive.int)
        assertTrue(info["players"]!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.int == bobsId })
    }
}
