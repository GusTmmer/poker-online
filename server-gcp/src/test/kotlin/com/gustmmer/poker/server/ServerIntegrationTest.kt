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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private fun ApplicationTestBuilder.configureTestApp(config: ServerConfig = testConfig): HttpClient {
        val bus = com.gustmmer.poker.server.bus.InMemoryTableUpdateBus()
        val persistence = com.gustmmer.poker.server.persistence.NotifyingPersistence(
            MemoryBasedPokerTablePersistence.json(), bus
        )
        val scheduler = com.gustmmer.poker.server.timer.InMemoryTaskScheduler()

        application {
            configureServer(persistence, config, bus, scheduler)
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
    fun `a committed change fans out to another player's socket via the bus`() = testApplication {
        val alice = configureTestApp()

        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","startingChips":1000,"maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

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
            // First frame: the connect snapshot — no round yet.
            val first = (withTimeout(5000) { incoming.receive() } as Frame.Text).readText()
            assertEquals("WAITING", Json.parseToJsonElement(first).jsonObject["gameStatus"]!!.jsonPrimitive.content)

            // Alice starts the round over REST. With the explicit broadcasts removed, the ONLY way this
            // reaches Bob's socket is the bus fanning out the committed change.
            assertEquals(HttpStatusCode.OK, alice.post("/api/tables/$tableId/start-round").status)

            withTimeout(5000) {
                while (true) {
                    val obj = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                    if (obj["roundStage"]?.jsonPrimitive?.contentOrNull != null) break // round is live → fan-out worked
                }
            }
        }
    }

    @Test
    fun `a manually-acted showdown fans out a SHOWDOWN frame revealing every participant's cards`() = testApplication {
        // Regression: the action that ends a hand used to clear the round to WAITING inside the SAME
        // withTable block that produced the SHOWDOWN state. Since a block commits (and fans out) only
        // once, the reveal was overwritten in memory before any client ever saw it — clients jumped
        // straight from the last betting stage to "no round", so pocket cards were never shown. The fix
        // commits the SHOWDOWN reveal on its own frame, then clears to WAITING in a separate commit.
        val alice = configureTestApp()
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","startingChips":1000,"turnTimerSeconds":300,"maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        fun player() = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(WebSockets)
        }
        val bob = player()
        val charlie = player()
        bob.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json); setBody("""{"playerName":"Bob"}""")
        }
        charlie.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json); setBody("""{"playerName":"Charlie"}""")
        }
        // Bob is player 1 — he observes the game over a socket while all three play the hand out.
        val bobId = 1

        bob.webSocket("/ws/tables/$tableId") {
            // A background reader drains every frame so the server's sends never block, and so we keep
            // the full history (the SHOWDOWN frame is transient — the very next commit clears the round).
            val frames = java.util.concurrent.CopyOnWriteArrayList<JsonObject>()
            val reader = launch {
                runCatching {
                    for (frame in incoming) if (frame is Frame.Text) {
                        frames += Json.parseToJsonElement(frame.readText()).jsonObject
                    }
                }
            }

            assertEquals(HttpStatusCode.OK, alice.post("/api/tables/$tableId/start-round").status)

            // Everyone just calls/checks: no folds, no raises, no all-ins — so the hand runs all the way
            // to a river showdown with all three still in and none busted (every player keeps chips, so
            // none is eliminated and every participant's cards are eligible to be revealed).
            callDownUntilRoundEnds(listOf(alice, bob, charlie), tableId)

            val showdown = withTimeout(5000) {
                var frame: JsonObject? = null
                while (frame == null) {
                    frame = frames.lastOrNull { it["roundStage"]?.jsonPrimitive?.contentOrNull == "SHOWDOWN" }
                    if (frame == null) delay(25)
                }
                frame
            }
            reader.cancel()

            // In the SHOWDOWN frame Bob observes, both OTHER participants' pocket cards are revealed
            // (2 cards each) and their evaluated best hand is attached.
            val others = showdown["players"]!!.jsonArray
                .map { it.jsonObject }
                .filter { it["id"]!!.jsonPrimitive.int != bobId }
            assertEquals(2, others.size)
            others.forEach { p ->
                val pocket = p["pocketCards"]?.jsonArray
                assertNotNull(pocket, "expected player ${p["id"]} pocket cards revealed at showdown")
                assertEquals(2, pocket!!.size)
                assertNotNull(p["bestHand"]?.jsonObject, "expected player ${p["id"]} best hand at showdown")
            }

            // And the round still transitions to WAITING afterwards (the clear is a separate commit).
            withTimeout(5000) {
                while (frames.none {
                        it["roundStage"]?.jsonPrimitive?.contentOrNull == null &&
                            it["gameStatus"]?.jsonPrimitive?.contentOrNull == "WAITING"
                    }) delay(25)
            }
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

    @Test
    fun `an unattended turn times out and auto-plays via the scheduler`() = testApplication {
        val alice = configureTestApp()

        // 1s turn timer so the in-memory scheduler fires quickly.
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","startingChips":1000,"turnTimerSeconds":1,"maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        val bob = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        bob.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Bob"}""")
        }

        assertEquals(HttpStatusCode.OK, alice.post("/api/tables/$tableId/start-round").status)

        // Nobody acts. The scheduler should fire, idle the timed-out player and auto-play their turn.
        withTimeout(6000) {
            while (true) {
                val players = Json.parseToJsonElement(alice.get("/api/tables/$tableId").bodyAsText())
                    .jsonObject["players"]!!.jsonArray
                if (players.any { it.jsonObject["status"]!!.jsonPrimitive.content == "IDLE" }) break
                delay(150)
            }
        }
    }

    @Test
    fun `a pending vote fans out to other players via the bus`() = testApplication {
        val alice = configureTestApp()
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

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
            // Drain the connect snapshot first — it's sent after the server registers Bob's bus
            // subscription, so receiving it guarantees the subsequent vote commit will reach him.
            withTimeout(5000) { incoming.receive() }

            // Alice opens a pause vote (2 participants → needs 2 → stays PENDING at her 1 yes). The vote
            // lives in table state now, so the commit fans out to Bob's socket via the bus.
            val resp = alice.post("/api/tables/$tableId/voting-sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolution":"PAUSE_GAME"}""")
            }
            assertEquals(HttpStatusCode.OK, resp.status)

            withTimeout(5000) {
                while (true) {
                    val obj = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                    val votes = obj["activeVotes"]?.jsonArray
                    if (votes != null && votes.isNotEmpty() &&
                        votes[0].jsonObject["resolutionType"]?.jsonPrimitive?.content == "PAUSE_GAME"
                    ) break
                }
            }
        }
    }

    @Test
    fun `vote eligibility counts only online players`() = testApplication {
        val alice = configureTestApp()
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","maxPlayers":6}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        // Bob + Carol join and stay (ONLINE); Dave joins, connects a socket, then drops (→ OFFLINE).
        for (name in listOf("Bob", "Carol")) {
            createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
                .post("/api/tables/$tableId/players") {
                    contentType(ContentType.Application.Json); setBody("""{"playerName":"$name"}""")
                }
        }
        val dave = createClient {
            install(ContentNegotiation) { json() }; install(HttpCookies); install(WebSockets)
        }
        dave.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json); setBody("""{"playerName":"Dave"}""")
        }
        dave.webSocket("/ws/tables/$tableId") { /* connect then immediately close → server marks OFFLINE */ }

        // Wait until Dave is actually OFFLINE (the disconnect commit is async).
        withTimeout(5000) {
            while (true) {
                val players = Json.parseToJsonElement(alice.get("/api/tables/$tableId").bodyAsText())
                    .jsonObject["players"]!!.jsonArray
                val dave3 = players.first { it.jsonObject["id"]!!.jsonPrimitive.int == 3 }
                if (dave3.jsonObject["status"]!!.jsonPrimitive.content == "OFFLINE") break
                delay(100)
            }
        }

        // 3 online (Alice, Bob, Carol) → requiredVotes = 2, not 3 (Dave doesn't count).
        val required = Json.parseToJsonElement(
            alice.post("/api/tables/$tableId/voting-sessions") {
                contentType(ContentType.Application.Json); setBody("""{"resolution":"PAUSE_GAME"}""")
            }.bodyAsText()
        ).jsonObject["requiredVotes"]!!.jsonPrimitive.int
        assertEquals(2, required)
    }

    @Test
    fun `the vote-expire endpoint closes a still-open vote`() = testApplication {
        val alice = configureTestApp()
        val tableId = openTableWithTwoPlayers(alice)

        val sessionId = Json.parseToJsonElement(
            alice.post("/api/tables/$tableId/voting-sessions") {
                contentType(ContentType.Application.Json)
                setBody("""{"resolution":"PAUSE_GAME"}""")
            }.bodyAsText()
        ).jsonObject["sessionId"]!!.jsonPrimitive.content

        assertEquals(1, votingSessionsCount(alice, tableId))

        val expire = alice.post("/internal/vote-expire/$tableId/$sessionId") {
            header("X-Internal-Token", "dev-internal-token")
        }
        assertEquals(HttpStatusCode.OK, expire.status)
        assertEquals(0, votingSessionsCount(alice, tableId))
    }

    @Test
    fun `a pending vote auto-closes when its timeout fires via the scheduler`() = testApplication {
        val alice = configureTestApp(testConfig.copy(voteTimeoutSeconds = 1))
        val tableId = openTableWithTwoPlayers(alice)

        alice.post("/api/tables/$tableId/voting-sessions") {
            contentType(ContentType.Application.Json)
            setBody("""{"resolution":"PAUSE_GAME"}""")
        }
        assertEquals(1, votingSessionsCount(alice, tableId))

        // The scheduler's vote timeout should fire and close it.
        withTimeout(6000) {
            while (votingSessionsCount(alice, tableId) != 0) delay(150)
        }
    }

    private suspend fun ApplicationTestBuilder.openTableWithTwoPlayers(alice: HttpClient): Int {
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int
        val bob = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        bob.post("/api/tables/$tableId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Bob"}""")
        }
        return tableId
    }

    private suspend fun votingSessionsCount(client: HttpClient, tableId: Int): Int =
        Json.parseToJsonElement(client.get("/api/tables/$tableId/voting-sessions").bodyAsText()).jsonArray.size

    @Test
    fun `internal timer-expire endpoint rejects calls without the shared secret`() = testApplication {
        val alice = configureTestApp()
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice"}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        assertEquals(HttpStatusCode.Unauthorized, alice.post("/internal/timer-expire/$tableId").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            alice.post("/internal/timer-expire/$tableId") { header("X-Internal-Token", "wrong") }.status,
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Tries each player client in turn, sending a FOLD. Repeats until the round
     * ends or no player can act. This avoids needing to know whose turn it is.
     */
    @Test
    fun `my-tables lists every table the browser holds a session for`() = testApplication {
        val client = configureTestApp()

        // Same client (one cookie jar) creates one table and joins another → two poker_table_* cookies.
        val firstId = Json.parseToJsonElement(
            client.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        // A second table created by someone else, which Alice then joins.
        val host = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        val secondId = Json.parseToJsonElement(
            host.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Bob","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int
        client.post("/api/tables/$secondId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice"}""")
        }

        val response = client.get("/api/my-tables")
        assertEquals(HttpStatusCode.OK, response.status)

        val tables = Json.parseToJsonElement(response.bodyAsText()).jsonObject["tables"]!!.jsonArray
        val byId = tables.associateBy { it.jsonObject["tableId"]!!.jsonPrimitive.int }
        assertEquals(setOf(firstId, secondId), byId.keys)
        // Alice created the first (host) and joined the second → she's "Alice" in both.
        assertEquals("Alice", byId[firstId]!!.jsonObject["playerName"]!!.jsonPrimitive.content)
        assertEquals(2, byId[secondId]!!.jsonObject["playerCount"]!!.jsonPrimitive.int)
    }

    @Test
    fun `my-tables supports multiple tables and switching between them`() = testApplication {
        // One browser (one cookie jar) — the whole point is that it can hold several sessions at once.
        val alice = configureTestApp()

        // Table A: Alice creates it, so she's player 0 there.
        val createdA = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject
        val tableAId = createdA["tableId"]!!.jsonPrimitive.int
        val aliceIdA = createdA["playerId"]!!.jsonPrimitive.int

        // Table B: Bob creates it (player 0); Alice joins as a later player.
        val bob = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        val tableBId = Json.parseToJsonElement(
            bob.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Bob","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int
        val joinB = alice.post("/api/tables/$tableBId/players") {
            contentType(ContentType.Application.Json)
            setBody("""{"playerName":"Alice"}""")
        }
        assertEquals(HttpStatusCode.Created, joinB.status)
        val aliceIdB = Json.parseToJsonElement(joinB.bodyAsText()).jsonObject["playerId"]!!.jsonPrimitive.int

        // ── List: both tables appear from the single cookie jar. ────────────
        val listed = Json.parseToJsonElement(alice.get("/api/my-tables").bodyAsText())
            .jsonObject["tables"]!!.jsonArray
            .map { it.jsonObject["tableId"]!!.jsonPrimitive.int }
            .toSet()
        assertEquals(setOf(tableAId, tableBId), listed)

        // ── Switch: the same jar authenticates against each table and is recognised with the
        //    correct per-table identity — that's what "switching between tables" means. ──────
        val infoA = Json.parseToJsonElement(alice.get("/api/tables/$tableAId").bodyAsText()).jsonObject
        assertTrue(infoA["hasSession"]!!.jsonPrimitive.boolean)
        assertEquals(aliceIdA, infoA["sessionPlayerId"]!!.jsonPrimitive.int)

        val infoB = Json.parseToJsonElement(alice.get("/api/tables/$tableBId").bodyAsText()).jsonObject
        assertTrue(infoB["hasSession"]!!.jsonPrimitive.boolean)
        assertEquals(aliceIdB, infoB["sessionPlayerId"]!!.jsonPrimitive.int)

        // Distinct identities per table prove these are independent sessions, not one shared token.
        assertNotEquals(aliceIdA, aliceIdB)
    }

    @Test
    fun `table name is set on create, editable via settings, and surfaced in my-tables`() = testApplication {
        val client = configureTestApp()

        val tableId = Json.parseToJsonElement(
            client.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","name":"Friday Night","maxPlayers":4}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        // Name flows into table info and the my-tables listing.
        assertEquals(
            "Friday Night",
            Json.parseToJsonElement(client.get("/api/tables/$tableId").bodyAsText())
                .jsonObject["name"]!!.jsonPrimitive.content
        )
        assertEquals(
            "Friday Night",
            Json.parseToJsonElement(client.get("/api/my-tables").bodyAsText())
                .jsonObject["tables"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content
        )

        // Rename via settings — name only, isOpen omitted; the value is trimmed.
        val patch = client.patch("/api/tables/$tableId/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"name":"  Saturday Showdown  "}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)
        assertEquals(
            "Saturday Showdown",
            Json.parseToJsonElement(client.get("/api/tables/$tableId").bodyAsText())
                .jsonObject["name"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `leaving a table frees the seat, clears the cookie, and drops it from my-tables`() = testApplication {
        val alice = configureTestApp()

        // A 2-seat table: Alice creates it, Bob joins → full.
        val tableId = Json.parseToJsonElement(
            alice.post("/api/tables") {
                contentType(ContentType.Application.Json)
                setBody("""{"playerName":"Alice","maxPlayers":2}""")
            }.bodyAsText()
        ).jsonObject["tableId"]!!.jsonPrimitive.int

        val bob = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        assertEquals(
            HttpStatusCode.Created,
            bob.post("/api/tables/$tableId/players") {
                contentType(ContentType.Application.Json); setBody("""{"playerName":"Bob"}""")
            }.status,
        )

        // Full: a third player is refused.
        val charlie = createClient { install(ContentNegotiation) { json() }; install(HttpCookies) }
        assertEquals(
            HttpStatusCode.Forbidden,
            charlie.post("/api/tables/$tableId/players") {
                contentType(ContentType.Application.Json); setBody("""{"playerName":"Charlie"}""")
            }.status,
        )

        // Alice permanently leaves.
        assertEquals(HttpStatusCode.OK, alice.delete("/api/tables/$tableId/players/me").status)

        // Her seat is gone (roster back to 1) and her cookie was cleared → my-tables now empty for her.
        assertEquals(
            1,
            Json.parseToJsonElement(bob.get("/api/tables/$tableId").bodyAsText())
                .jsonObject["players"]!!.jsonArray.size,
        )
        assertTrue(
            Json.parseToJsonElement(alice.get("/api/my-tables").bodyAsText())
                .jsonObject["tables"]!!.jsonArray.isEmpty(),
        )

        // The freed seat is now joinable.
        assertEquals(
            HttpStatusCode.Created,
            charlie.post("/api/tables/$tableId/players") {
                contentType(ContentType.Application.Json); setBody("""{"playerName":"Charlie"}""")
            }.status,
        )
    }

    @Test
    fun `my-tables is empty when the browser has no session cookies`() = testApplication {
        val client = configureTestApp()
        val response = client.get("/api/my-tables")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["tables"]!!.jsonArray.isEmpty()
        )
    }

    @Test
    fun `my-tables omits and clears a stale session cookie`() = testApplication {
        // No HttpCookies plugin: we control the Cookie header by hand to inject a stale-but-valid token.
        configureTestApp()
        val rawClient = createClient { install(ContentNegotiation) { json() } }

        // A validly-signed JWT for a table that does not exist → server must drop it and clear the cookie.
        val staleToken = com.gustmmer.poker.server.session.JwtService(testConfig.jwtSecret)
            .createToken(tableId = 999999, playerId = 0)

        val response = rawClient.get("/api/my-tables") {
            header(HttpHeaders.Cookie, "poker_table_999999=$staleToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["tables"]!!.jsonArray.isEmpty()
        )
        val setCookie = response.headers.getAll(HttpHeaders.SetCookie)?.joinToString("; ").orEmpty()
        assertTrue(setCookie.contains("poker_table_999999")) { "stale cookie should be cleared" }
    }

    @Test
    fun `session cookie is HttpOnly and SameSite=Lax, and Secure only when configured`() = testApplication {
        // Default config (secureCookies=false, dev over http): HttpOnly + SameSite=Lax, but not Secure.
        // Raw client (no HttpCookies plugin) so the Set-Cookie header is readable verbatim.
        configureTestApp()
        val rawClient = createClient { install(ContentNegotiation) { json() } }
        // Lowercase so casing doesn't matter; match attributes with their "; " delimiter so a base64
        // substring inside the JWT value can't accidentally satisfy the check.
        val insecure = rawClient.post("/api/tables") {
            contentType(ContentType.Application.Json); setBody("""{"playerName":"Alice"}""")
        }.headers[HttpHeaders.SetCookie].orEmpty().lowercase()
        assertTrue(insecure.contains("; httponly")) { "expected HttpOnly in: $insecure" }
        assertTrue(insecure.contains("; samesite=lax")) { "expected SameSite=Lax in: $insecure" }
        assertFalse(insecure.contains("; secure")) { "dev cookie must not be Secure: $insecure" }
    }

    @Test
    fun `session cookie is marked Secure when secureCookies is enabled`() = testApplication {
        // Production posture (Cloud Run is HTTPS): the same cookie gains the Secure attribute.
        configureTestApp(testConfig.copy(secureCookies = true))
        val rawClient = createClient { install(ContentNegotiation) { json() } }
        val secure = rawClient.post("/api/tables") {
            contentType(ContentType.Application.Json); setBody("""{"playerName":"Alice"}""")
        }.headers[HttpHeaders.SetCookie].orEmpty().lowercase()
        assertTrue(secure.contains("; secure")) { "expected Secure in: $secure" }
        assertTrue(secure.contains("; samesite=lax")) { "expected SameSite=Lax in: $secure" }
    }

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

    /**
     * Round-robins a CALL from each client until the round ends. Nobody folds, raises, or goes all-in,
     * so the hand checks/calls all the way through to a river showdown with every player still in. Like
     * [foldUntilRoundEnds] it never needs to know whose turn it is — an out-of-turn CALL just 400s.
     */
    private suspend fun callDownUntilRoundEnds(clients: List<HttpClient>, tableId: Int) {
        var safety = 40
        while (safety-- > 0) {
            var anyoneActed = false
            for (c in clients) {
                val resp = c.post("/api/tables/$tableId/action") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"CALL"}""")
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
