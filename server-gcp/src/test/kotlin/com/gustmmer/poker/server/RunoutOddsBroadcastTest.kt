package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import com.gustmmer.poker.server.timer.InMemoryTaskScheduler
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The win-probability odds that ride along a showdown frame when an all-in board is run out. */
class RunoutOddsBroadcastTest {

    private val config = ServerConfig(port = 8080, jwtSecret = "runout-odds-test-secret", firestoreProjectId = "test")

    private class Table(val id: Int, val alice: PokerClient, val aliceHttp: HttpClient, val bob: PokerClient)

    /** Alice (button, small blind) with a WebSocket-capable client, and Bob (big blind), seated heads-up. */
    private suspend fun ApplicationTestBuilder.seatHeadsUp(): Table {
        val bus = InMemoryTableUpdateBus()
        val store = MemoryBasedPokerTablePersistence.json()
        application { configureServer(NotifyingPersistence(store, bus), config, bus, InMemoryTaskScheduler()) }
        fun http() = createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(Resources)
            install(WebSockets)
        }
        val aliceHttp = http()
        val alice = PokerClient(aliceHttp)
        val bob = PokerClient(http())
        val tableId = alice.createTable(playerName = "Alice", startingChips = 1000).tableId
        bob.joinTable(tableId, "Bob")
        return Table(tableId, alice, aliceHttp, bob)
    }

    private suspend fun ReceiveChannel<Frame>.nextState(): JsonObject {
        while (true) {
            val frame = receive() as? Frame.Text ?: continue
            val json = Json.parseToJsonElement(frame.readText()).jsonObject
            if (json["type"]?.jsonPrimitive?.content == "game_state") return json
        }
    }

    private fun JsonObject.odds(): JsonArray = this["runoutOdds"]?.jsonArray ?: JsonArray(emptyList())

    private val JsonObject.isShowdown get() = this["roundStage"]?.jsonPrimitive?.contentOrNull == "SHOWDOWN"

    /** Reads frames up to the showdown, asserting none before it carries odds. */
    private suspend fun ReceiveChannel<Frame>.showdownFrame(): JsonObject = withTimeout(5_000) {
        var state = nextState()
        while (!state.isShowdown) {
            assertTrue(state.odds().isEmpty(), "odds must never ride along a betting frame: $state")
            state = nextState()
        }
        state
    }

    @Test
    fun `a pre-flop all-in showdown carries odds for every board the runout shows`() = testApplication {
        val t = seatHeadsUp()
        t.aliceHttp.webSocket("/ws/tables/${t.id}") {
            withTimeout(5_000) { incoming.nextState() } // connect snapshot
            assertTrue(t.alice.startRound(t.id).isOk())
            assertTrue(t.alice.allIn(t.id).isOk())
            assertTrue(t.bob.call(t.id).isOk())

            val odds = incoming.showdownFrame().odds().map { it.jsonObject }
            assertEquals(listOf(0, 3, 4, 5), odds.map { it["boardCards"]!!.jsonPrimitive.int })
            odds.forEach { street ->
                val equities = street["equities"]!!.jsonArray.map { it.jsonObject }
                assertEquals(setOf(0, 1), equities.map { it["playerId"]!!.jsonPrimitive.int }.toSet())
                assertEquals(1.0, equities.sumOf { it["equity"]!!.jsonPrimitive.double }, 1e-9)
            }
        }
    }

    @Test
    fun `a showdown on the river carries no odds`() = testApplication {
        val t = seatHeadsUp()
        t.aliceHttp.webSocket("/ws/tables/${t.id}") {
            withTimeout(5_000) { incoming.nextState() }
            assertTrue(t.alice.startRound(t.id).isOk())
            // Pre-flop Alice completes and Bob checks; after the flop Bob acts first on every street.
            assertTrue(t.alice.call(t.id).isOk())
            assertTrue(t.bob.call(t.id).isOk())
            repeat(3) {
                assertTrue(t.bob.call(t.id).isOk())
                assertTrue(t.alice.call(t.id).isOk())
            }

            assertTrue(incoming.showdownFrame().odds().isEmpty())
        }
    }
}
