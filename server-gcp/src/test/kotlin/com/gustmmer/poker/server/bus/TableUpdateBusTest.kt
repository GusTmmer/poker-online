package com.gustmmer.poker.server.bus

import com.gustmmer.poker.Player
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.TableConfig
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the in-memory fan-out path: a committed change reaches subscribers (and only while
 * subscribed). This is the same publish→subscribe→deliver contract the Firestore bus implements via
 * snapshot listeners, so passing here exercises everything in Phase 1 except the Firestore wiring
 * (which needs the emulator — see FirestoreTableUpdateBus).
 */
class TableUpdateBusTest {

    private fun config() = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 6)

    private fun newTable(persistence: NotifyingPersistence) =
        PokerTable.new(firstPlayer = Player(0, "Alice"), config = config(), persistence = persistence)

    @Test
    fun `commit publishes the new state to subscribers`() = runBlocking {
        val bus = InMemoryTableUpdateBus()
        val persistence = NotifyingPersistence(MemoryBasedPokerTablePersistence.json(), bus)
        val table = newTable(persistence)

        val received = CompletableDeferred<PokerTableState>()
        val sub = bus.subscribe(table.id) { received.complete(it) }

        // A second player joins and commits → the subscriber should observe the 2-player state.
        val t2 = PokerTable.restore(table.id, persistence)!!
        assertTrue(t2.playerJoin(Player(1, "Bob")))
        t2.commit()

        val state = withTimeout(2000) { received.await() }
        assertEquals(table.id, state.id)
        assertEquals(2, state.players.size)
        sub.cancel()
    }

    @Test
    fun `a cancelled subscription receives nothing`() = runBlocking {
        val bus = InMemoryTableUpdateBus()
        val persistence = NotifyingPersistence(MemoryBasedPokerTablePersistence.json(), bus)
        val table = newTable(persistence)

        var deliveries = 0
        bus.subscribe(table.id) { deliveries++ }.cancel()

        val t2 = PokerTable.restore(table.id, persistence)!!
        t2.playerJoin(Player(1, "Bob"))
        t2.commit()

        delay(200) // let any erroneously-scheduled delivery run before asserting none did
        assertEquals(0, deliveries)
    }
}
