package com.gustmmer.poker.server

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus
import com.gustmmer.poker.server.config.ServerConfig
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import com.gustmmer.poker.server.routes.CreateTableRequest
import com.gustmmer.poker.server.timer.InMemoryTaskScheduler
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.resources.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Two-player regressions from a production game: a short-stacked blind froze the hand, a kick vote
 * 500'd, and a player leaving mid-hand left a table that could no longer be loaded. Plus the votes
 * that were silently rejected in a heads-up game (restart, increase blinds).
 *
 * Alice = player 0 (dealer and small blind in hand 1), Bob = player 1 (big blind).
 */
class HeadsUpRegressionTest {

    private val config = ServerConfig(port = 8080, jwtSecret = "heads-up-test-secret", firestoreProjectId = "test")

    private lateinit var store: MemoryBasedPokerTablePersistence

    private fun ApplicationTestBuilder.twoClients(): Pair<PokerClient, PokerClient> {
        val bus = InMemoryTableUpdateBus()
        store = MemoryBasedPokerTablePersistence.json()
        application { configureServer(NotifyingPersistence(store, bus), config, bus, InMemoryTaskScheduler()) }
        fun client() = PokerClient(createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(Resources)
        })
        return client() to client()
    }

    private suspend fun seat(alice: PokerClient, bob: PokerClient, startingChips: Int = 1000): Int {
        val tableId = alice.createTable(playerName = "Alice", startingChips = startingChips).tableId
        assertEquals(HttpStatusCode.Created, bob.joinTable(tableId, "Bob"))
        return tableId
    }

    private fun state(tableId: Int): PokerTableState = store.loadState(tableId)!!

    /** Stands up state the API can't reach directly (a specific stack, an offline player). */
    private fun edit(tableId: Int, block: PokerTableState.() -> Unit) {
        store.seed(state(tableId).apply(block))
    }

    @Test
    fun `a small blind all-in on the blind plays out instead of freezing the table`() = testApplication {
        val (alice, bob) = twoClients()
        val tableId = seat(alice, bob)
        edit(tableId) { players.single { it.id == 0 }.let { it.removeChips(it.chips - 10) } } // exactly the small blind

        assertTrue(alice.startRound(tableId).isOk())

        with(state(tableId)) {
            assertNull(roundState, "the hand should have run out and been cleared")
            assertEquals(1010, players.sumOf { it.chips })
        }
        assertEquals("WAITING", alice.getTable(tableId).gameStatus)
    }

    @Test
    fun `leaving mid-hand ends the hand and the table still loads`() = testApplication {
        val (alice, bob) = twoClients()
        val tableId = seat(alice, bob)
        assertTrue(alice.startRound(tableId).isOk())

        assertEquals("left", alice.leaveTable(tableId).statusField)

        val table = bob.getTable(tableId)
        assertEquals(listOf(1), table.players.map { it.id })
        assertEquals("WAITING", table.gameStatus)
        assertEquals(1010, table.players.single().chips) // wins the blind Alice left behind
    }

    @Test
    fun `a passed kick vote folds the target out of turn`() = testApplication {
        val (alice, bob) = twoClients()
        val tableId = seat(alice, bob)
        assertTrue(alice.startRound(tableId).isOk())
        // Alice (small blind) is first to act; kick Bob, who is waiting on her.
        edit(tableId) { players.single { it.id == 1 }.setAsOffline() }

        val vote = alice.createVote(tableId, "KICK_PLAYER", targetPlayerId = 1)

        assertEquals("PASSED", vote.outcome)
        val table = alice.getTable(tableId)
        assertEquals(listOf(0), table.players.map { it.id })
        assertEquals("WAITING", table.gameStatus)
    }

    @Test
    fun `a restart vote passes mid-hand in a two-player game`() = testApplication {
        val (alice, bob) = twoClients()
        val tableId = seat(alice, bob)
        assertTrue(alice.startRound(tableId).isOk())
        assertTrue(alice.call(tableId).isOk())

        val vote = alice.createVote(tableId, "RESTART_GAME")
        assertEquals("PENDING", vote.outcome)
        assertEquals("PASSED", bob.castVote(tableId, vote.sessionId, "yes")!!.outcome)

        with(state(tableId)) {
            assertNull(roundState)
            assertTrue(activeVotes.isEmpty())
            players.forEach { assertEquals(1000, it.chips) }
            players.forEach { assertEquals(PlayerStatus.ONLINE, it.status) }
        }
        assertTrue(alice.startRound(tableId).isOk())
    }

    @Test
    fun `an increase-blinds vote raises the blinds for the next hand`() = testApplication {
        val (alice, bob) = twoClients()
        val tableId = seat(alice, bob)
        assertEquals(Blinds(big = 20, small = 10), state(tableId).blinds)

        val vote = alice.createVote(tableId, "INCREASE_BLINDS")
        assertEquals("PASSED", bob.castVote(tableId, vote.sessionId, "yes")!!.outcome)

        assertEquals(Blinds(big = 40, small = 20), state(tableId).blinds)
        assertTrue(alice.startRound(tableId).isOk())
        assertEquals(Blinds(big = 40, small = 20), state(tableId).roundState!!.blinds)
    }

    @Test
    fun `table creation honours the starting big blind and rejects invalid settings`() = testApplication {
        val (alice, _) = twoClients()
        val tableId = alice.createTable(playerName = "Alice", startingChips = 1000, bigBlind = 50).tableId
        assertEquals(Blinds(big = 50, small = 25), state(tableId).blinds)

        val tooBig = alice.createTableRaw(CreateTableRequest(playerName = "Alice", startingChips = 1000, bigBlind = 600))
        assertEquals(HttpStatusCode.BadRequest, tooBig.status)
        assertTrue(tooBig.error!!.contains("Big blind"))
    }
}
