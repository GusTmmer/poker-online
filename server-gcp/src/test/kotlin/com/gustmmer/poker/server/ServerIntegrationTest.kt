package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.config.ServerConfig
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

class ServerIntegrationTest {

    private val testConfig = ServerConfig(
        port = 8080,
        jwtSecret = "test-secret",
        firestoreProjectId = "test-project",
    )

    private fun ApplicationTestBuilder.configureTestApp(): HttpClient {
        val persistence = MemoryBasedPokerTablePersistence.json()

        application {
            configureServer(persistence, testConfig)
        }

        return createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(WebSockets)
        }
    }

    @Test
    fun `full server lifecycle - create, join, play, pause, restart, kick, close`() = testApplication {
        val client = configureTestApp()

        // ── Step 1: Create a table ──────────────────────────────────────────

        val createResponse = client.post("/api/tables") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice","startingChips":1000,"turnTimerSeconds":30,"maxPlayers":4}""")
        }
        assertEquals(HttpStatusCode.Created, createResponse.status)

        val createBody = Json.parseToJsonElement(createResponse.bodyAsText()).jsonObject
        val tableId = createBody["tableId"]!!.jsonPrimitive.int
        assertTrue(tableId > 0)
        assertEquals(0, createBody["playerId"]!!.jsonPrimitive.int)

        // ── Step 2: Get table info (Alice's session detected) ───────────────

        val infoResponse = client.get("/api/tables/$tableId")
        assertEquals(HttpStatusCode.OK, infoResponse.status)

        val info = Json.parseToJsonElement(infoResponse.bodyAsText()).jsonObject
        assertEquals(tableId, info["tableId"]!!.jsonPrimitive.int)
        assertTrue(info["hasSession"]!!.jsonPrimitive.boolean)
        assertEquals(0, info["sessionPlayerId"]!!.jsonPrimitive.int)
        assertEquals(1, info["players"]!!.jsonArray.size)

        // ── Step 3: Two more players join (each with own cookie jar) ────────

        val bob = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }
        val charlie = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }

        val bobJoin = bob.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Bob"}""")
        }
        assertEquals(HttpStatusCode.Created, bobJoin.status)
        assertEquals(1, Json.parseToJsonElement(bobJoin.bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.int)

        val charlieJoin = charlie.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Charlie"}""")
        }
        assertEquals(HttpStatusCode.Created, charlieJoin.status)
        assertEquals(2, Json.parseToJsonElement(charlieJoin.bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.int)

        // Verify 3 players
        val info2 = Json.parseToJsonElement(client.get("/api/tables/$tableId").bodyAsText()).jsonObject
        assertEquals(3, info2["players"]!!.jsonArray.size)

        // ── Step 4: Alice cannot rejoin (already has cookie) ────────────────

        val aliceRejoin = client.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice2"}""")
        }
        assertEquals(HttpStatusCode.Conflict, aliceRejoin.status)

        // ── Step 5: Start a round ───────────────────────────────────────────

        val startResp = client.post("/api/tables/$tableId/start-round")
        assertEquals(HttpStatusCode.OK, startResp.status)

        // ── Step 6: Play round 1 (fold everyone to end it) ──────────────────

        val clients = listOf(client, bob, charlie)
        foldUntilRoundEnds(clients, tableId)

        // ── Step 7: Start round 2, pause and unpause ─────────────────────────

        val start2 = client.post("/api/tables/$tableId/start-round")
        assertEquals(HttpStatusCode.OK, start2.status)

        // Alice votes to pause (3 participating players → requiredVotes=2, still pending after 1 vote)
        val pause1 = client.post("/api/tables/$tableId/pause")
        assertEquals(HttpStatusCode.OK, pause1.status)
        val pauseResult = Json.parseToJsonElement(pause1.bodyAsText()).jsonObject
        val pauseSessionId = pauseResult["sessionId"]!!.jsonPrimitive.content

        // Bob casts the second yes vote → vote passes, game is now paused
        val pause2 = bob.put("/api/tables/$tableId/voting-sessions/$pauseSessionId/vote") {
            contentType(ContentType.Application.Json)
            setBody("""{"vote":"yes"}""")
        }
        assertEquals(HttpStatusCode.OK, pause2.status)
        val pause2Result = Json.parseToJsonElement(pause2.bodyAsText()).jsonObject
        assertTrue(
            pause2Result["outcome"]!!.jsonPrimitive.content.equals("PASSED", ignoreCase = true),
            "Pause should pass with 2/3 votes"
        )

        // Verify action is blocked while paused
        for (c in clients) {
            val resp = c.post("/api/tables/$tableId/action") {
                contentType(ContentType.Application.Json)
                setBody("""{"type":"FOLD"}""")
            }
            if (resp.status == HttpStatusCode.BadRequest) {
                val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
                assertTrue(body["error"]!!.jsonPrimitive.content.contains("paused", ignoreCase = true))
                break
            }
        }

        // Cannot pause again while already paused
        val pauseAgain = bob.post("/api/tables/$tableId/pause")
        assertEquals(HttpStatusCode.BadRequest, pauseAgain.status)

        // Unpause: Alice and Bob both vote yes (same 2/3 majority needed)
        val unpause1 = client.post("/api/tables/$tableId/unpause")
        assertEquals(HttpStatusCode.OK, unpause1.status)
        val unpauseSessionId = Json.parseToJsonElement(unpause1.bodyAsText()).jsonObject["sessionId"]!!.jsonPrimitive.content

        val unpauseResp = bob.put("/api/tables/$tableId/voting-sessions/$unpauseSessionId/vote") {
            contentType(ContentType.Application.Json)
            setBody("""{"vote":"yes"}""")
        }
        assertEquals(HttpStatusCode.OK, unpauseResp.status)

        // Fold to end round 2
        foldUntilRoundEnds(clients, tableId)

        // ── Step 8: Restart game rejected while game is active ──────────────

        val restartResp = client.post("/api/tables/$tableId/restart-game")
        assertEquals(HttpStatusCode.BadRequest, restartResp.status)

        // ── Step 9: Kick attempt (must be offline/idle) ─────────────────────

        // Start and end a round
        client.post("/api/tables/$tableId/start-round")
        foldUntilRoundEnds(clients, tableId)

        // Try to kick an online player (should fail)
        val kickOnline = client.post("/api/tables/$tableId/kick") {
            contentType(ContentType.Application.Json)
            setBody("""{"targetPlayerId":2}""")
        }
        assertEquals(HttpStatusCode.BadRequest, kickOnline.status)

        // ── Step 10: Close table, verify join rejected ──────────────────────

        val settingsResp = client.patch("/api/tables/$tableId/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"isOpen":false}""")
        }
        assertEquals(HttpStatusCode.OK, settingsResp.status)

        val diana = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }
        val dianaJoin = diana.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Diana"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, dianaJoin.status)
    }

    @Test
    fun `websocket receives game state on connect`() = testApplication {
        val client = configureTestApp()

        val createResp = client.post("/api/tables") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice","startingChips":1000,"maxPlayers":4}""")
        }
        val tableId = Json.parseToJsonElement(createResp.bodyAsText())
            .jsonObject["tableId"]!!.jsonPrimitive.int

        val bob = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(WebSockets)
        }
        bob.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Bob"}""")
        }

        bob.webSocket("/ws/tables/$tableId") {
            val frame = withTimeout(5000) { incoming.receive() }
            assertTrue(frame is Frame.Text)
            val msg = (frame as Frame.Text).readText()
            val json = Json.parseToJsonElement(msg).jsonObject
            assertEquals("game_state", json["type"]!!.jsonPrimitive.content)
            assertEquals(tableId, json["tableId"]!!.jsonPrimitive.int)
            assertTrue(json["players"]!!.jsonArray.size >= 2)
        }
    }

    @Test
    fun `unauthenticated requests are rejected`() = testApplication {
        val client = configureTestApp()

        val createResp = client.post("/api/tables") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice"}""")
        }
        val tableId = Json.parseToJsonElement(createResp.bodyAsText())
            .jsonObject["tableId"]!!.jsonPrimitive.int

        val stranger = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
        }

        assertEquals(HttpStatusCode.Unauthorized, stranger.post("/api/tables/$tableId/start-round").status)
        assertEquals(HttpStatusCode.Unauthorized, stranger.post("/api/tables/$tableId/action") {
            contentType(ContentType.Application.Json)
            setBody("""{"type":"FOLD"}""")
        }.status)
        assertEquals(HttpStatusCode.Unauthorized, stranger.post("/api/tables/$tableId/restart-game").status)
    }

    @Test
    fun `nonexistent table returns 404`() = testApplication {
        val client = configureTestApp()
        assertEquals(HttpStatusCode.NotFound, client.get("/api/tables/99999").status)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Tries each player client in turn, sending a FOLD. Repeats until the round
     * ends or no player can act. This avoids needing to know whose turn it is.
     */
    private suspend fun foldUntilRoundEnds(clients: List<HttpClient>, tableId: Int) {
        var safety = 20
        while (safety-- > 0) {
            var anyoneActed = false
            for (c in clients) {
                val resp = c.post("/api/tables/$tableId/action") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"FOLD"}""")
                }
                if (resp.status == HttpStatusCode.OK) {
                    anyoneActed = true
                    break
                }
            }
            if (!anyoneActed) break
        }
    }
}
