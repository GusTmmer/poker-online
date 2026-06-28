package com.gustmmer.poker.server

import com.gustmmer.poker.Player
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.TableConfig
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.bus.InMemoryTableUpdateBus
import com.gustmmer.poker.server.persistence.NotifyingPersistence
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext

/**
 * Proves the Path B payoff without real GCP: two [TableConnectionManager]s — each a stand-in for a
 * separate Cloud Run instance with its own sockets — share one [InMemoryTableUpdateBus] (the in-memory
 * analogue of a shared Firestore). A change committed by *anyone* must reach the sockets on *both*
 * instances, because each instance subscribes to the table and re-broadcasts to its own sockets.
 */
class MultiInstanceTest {

    /** Minimal [WebSocketSession] that just records the text frames sent to it. */
    private class RecordingSession : WebSocketSession {
        val sent = CopyOnWriteArrayList<String>()
        override val coroutineContext: CoroutineContext = Dispatchers.Unconfined + Job()
        override val incoming: ReceiveChannel<Frame> = Channel()
        override val outgoing: SendChannel<Frame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()
        override var masking: Boolean = false
        override var maxFrameSize: Long = Long.MAX_VALUE
        override suspend fun send(frame: Frame) {
            if (frame is Frame.Text) sent.add(frame.readText())
        }
        override suspend fun flush() {}
        @Deprecated("terminate", level = DeprecationLevel.ERROR)
        override fun terminate() {}
    }

    @Test
    fun `a commit on one instance reaches a socket on another instance`() = runBlocking {
        val bus = InMemoryTableUpdateBus()
        val persistence = NotifyingPersistence(MemoryBasedPokerTablePersistence.json(), bus)

        // Two independent "instances" sharing the bus + persistence.
        val instanceA = TableConnectionManager(bus)
        val instanceB = TableConnectionManager(bus)

        val table = PokerTable.new(
            firstPlayer = Player(0, "Alice"),
            config = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 6),
            persistence = persistence,
        )
        PokerTable.restore(table.id, persistence)!!.apply { playerJoin(Player(1, "Bob")); commit() }

        // Alice's socket lives on instance A, Bob's on instance B.
        val alice = RecordingSession().also { instanceA.addConnection(table.id, 0, it) }
        val bob = RecordingSession().also { instanceB.addConnection(table.id, 1, it) }

        // A third player joins (one commit). It must fan out to BOTH instances' sockets via the bus.
        PokerTable.restore(table.id, persistence)!!.apply { playerJoin(Player(2, "Carol")); commit() }

        withTimeout(3000) {
            while (alice.sent.none { it.contains("Carol") } || bob.sent.none { it.contains("Carol") }) {
                delay(20)
            }
        }
        assertTrue(alice.sent.any { it.contains("Carol") }, "instance A's socket should see the change")
        assertTrue(bob.sent.any { it.contains("Carol") }, "instance B's socket should see the change")
    }
}
