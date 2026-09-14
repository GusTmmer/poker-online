package com.gustmmer.poker.server

import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.bot.BotPersonality
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import com.gustmmer.poker.server.routes.CreateTableRequest
import com.gustmmer.poker.server.timer.InMemoryTaskScheduler
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.json.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Computer players seated at table creation, playing their turns through the turn-timer scheduler. */
class ComputerPlayersTest {

    // Bots "think" for ~1% of their real delay, so their turns resolve in milliseconds.
    private val config = ServerConfig(port = 8080, jwtSecret = "bots-test-secret", firestoreProjectId = "test", botDelayScale = 0.01)

    private lateinit var store: MemoryBasedPokerTablePersistence

    private fun ApplicationTestBuilder.client(): PokerClient {
        val bus = InMemoryTableUpdateBus()
        store = MemoryBasedPokerTablePersistence.json()
        application { configureServer(NotifyingPersistence(store, bus), config, bus, InMemoryTaskScheduler()) }
        return newClient()
    }

    private fun ApplicationTestBuilder.newClient() = PokerClient(httpClient())

    private fun ApplicationTestBuilder.httpClient() = createClient {
        install(ContentNegotiation) { json() }
        install(HttpCookies)
        install(Resources)
        install(WebSockets)
    }

    private fun state(tableId: Int): PokerTableState = store.loadState(tableId)!!

    private suspend fun createWithBots(human: PokerClient, bots: Int, maxPlayers: Int = 6): Int =
        human.createTable(playerName = "Alice", startingChips = 1000, maxPlayers = maxPlayers, computerPlayers = bots).tableId

    @Test
    fun `bots are seated at creation with rotating personalities`() = testApplication {
        val alice = client()
        val tableId = createWithBots(alice, bots = 4)

        val table = alice.getTable(tableId)
        assertEquals(5, table.players.size)
        assertEquals(listOf(false, true, true, true, true), table.players.map { it.isBot })
        assertEquals(
            listOf(null, BotPersonality.AGGRESSIVE, BotPersonality.BALANCED, BotPersonality.DEFENSIVE, BotPersonality.AGGRESSIVE),
            state(tableId).players.map { it.botPersonality },
        )
        assertEquals(4, state(tableId).config.computerPlayers)
    }

    @Test
    fun `computer players must leave a seat for the creator`() = testApplication {
        val alice = client()
        fun request(bots: Int) = CreateTableRequest(playerName = "Alice", maxPlayers = 4, computerPlayers = bots)
        assertEquals(HttpStatusCode.BadRequest, alice.createTableRaw(request(4)).status)
        assertEquals(HttpStatusCode.BadRequest, alice.createTableRaw(request(-1)).status)
        assertEquals(HttpStatusCode.Created, alice.createTableRaw(request(3)).status)
    }

    @Test
    fun `bots take seats, so a full table turns away another human`() = testApplication {
        val alice = client()
        val tableId = createWithBots(alice, bots = 2, maxPlayers = 3)
        assertEquals(HttpStatusCode.Forbidden, newClient().joinTable(tableId, "Bob"))
    }

    @Test
    fun `the human's ready-up alone deals a hand`() = testApplication {
        val alice = client()
        val tableId = createWithBots(alice, bots = 2)
        assertTrue(alice.ready(tableId).isOk())
        assertEquals(GameStatus.RUNNING, state(tableId).gameStatus)
    }

    @Test
    fun `bots play their turns until the hand is over`() = testApplication {
        val alice = client()
        val tableId = createWithBots(alice, bots = 2)
        assertTrue(alice.startRound(tableId).isOk())

        var botActions = 0
        withTimeout(15_000) {
            while (true) {
                val s = state(tableId)
                val round = s.roundState
                if (s.gameStatus == GameStatus.WAITING && round == null) break
                if (round != null && round.pokerRoundStage.isBettingRound()) {
                    botActions = maxOf(botActions, round.actions.count { it.playerId != 0 && it.type !in BLINDS })
                    if (round.playerOrdering.bettingPlayer().id == 0) alice.call(tableId)
                }
                delay(20)
            }
        }

        assertEquals(3000, state(tableId).players.sumOf { it.chips }, "no chips created or lost")
        assertTrue(botActions > 0, "the bots acted")
    }

    @Test
    fun `a bot acts on its own when a hand starts on its turn`() = testApplication {
        val alice = client()
        // Four-handed: seat 3 (a bot) is first to act pre-flop.
        val tableId = createWithBots(alice, bots = 3)
        assertTrue(alice.startRound(tableId).isOk())

        withTimeout(5_000) {
            while (state(tableId).roundState?.actions.orEmpty().none { it.type !in BLINDS }) delay(20)
        }
        assertEquals(3, state(tableId).roundState!!.actions.first { it.type !in BLINDS }.playerId)
    }

    @Test
    fun `bots don't vote, so the only human's vote passes on its own`() = testApplication {
        val alice = client()
        val tableId = createWithBots(alice, bots = 3)
        val vote = alice.createVote(tableId, "INCREASE_BLINDS")
        assertEquals(1, vote.requiredVotes)
        assertEquals("PASSED", vote.outcome)
    }

    @Test
    fun `a computer player's turn shows no turn clock, a human's does`() = testApplication {
        val bus = InMemoryTableUpdateBus()
        store = MemoryBasedPokerTablePersistence.json()
        application { configureServer(NotifyingPersistence(store, bus), config, bus, InMemoryTaskScheduler()) }
        val http = httpClient()
        val alice = PokerClient(http)
        // Three-handed, Alice (seat 0) is first to act pre-flop; both bots act after her.
        val tableId = createWithBots(alice, bots = 2)

        http.webSocket("/ws/tables/$tableId") {
            withTimeout(5_000) { incoming.receive() } // connect snapshot: subscribed from here on
            assertTrue(alice.startRound(tableId).isOk())

            var humanTurnWithClock = false
            var botTurns = 0
            withTimeout(15_000) {
                while (true) {
                    val frame = incoming.receive() as? Frame.Text ?: continue
                    val json = Json.parseToJsonElement(frame.readText()).jsonObject
                    if (json["type"]?.jsonPrimitive?.content != "game_state") continue
                    val toAct = json["nextPlayerIdToAct"]?.jsonPrimitive?.intOrNull
                    val clock = json["turnTimerEndsAt"]?.jsonPrimitive?.longOrNull
                    when (toAct) {
                        null -> if (humanTurnWithClock && botTurns > 0) break
                        0 -> if (clock != null) {
                            humanTurnWithClock = true
                            if (botTurns > 0) break
                            alice.call(tableId)
                        }
                        else -> {
                            botTurns++
                            assertNull(clock, "bot $toAct's turn broadcast a clock")
                        }
                    }
                }
            }
            assertTrue(humanTurnWithClock)
            assertTrue(botTurns > 0)
        }
    }

    private companion object {
        val BLINDS = setOf(HandActionType.SMALL_BLIND, HandActionType.BIG_BLIND)
    }
}
