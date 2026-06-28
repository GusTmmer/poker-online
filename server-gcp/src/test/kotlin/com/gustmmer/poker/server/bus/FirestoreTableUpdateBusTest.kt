package com.gustmmer.poker.server.bus

import com.google.cloud.NoCredentials
import com.google.cloud.firestore.FirestoreOptions
import com.gustmmer.poker.Player
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.TableConfig
import com.gustmmer.poker.server.persistence.FirestorePokerTablePersistence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.containers.FirestoreEmulatorContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import kotlin.random.Random

/**
 * Exercises the real Firestore snapshot listener — that a committed write actually pushes the new
 * document to a subscriber, the mechanism behind all cross-instance fan-out (state and votes). The
 * emulator runs in a Testcontainers-managed container, so this runs unattended in CI. Where Docker
 * isn't available (e.g. a local box without it) `disabledWithoutDocker` skips the class, keeping the
 * build green; the in-memory [TableUpdateBusTest] covers the same publish→deliver contract.
 */
@Testcontainers(disabledWithoutDocker = true)
class FirestoreTableUpdateBusTest {

    companion object {
        @Container
        @JvmStatic
        val emulator = FirestoreEmulatorContainer(
            DockerImageName.parse("gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators"),
        )
    }

    private fun firestore() = FirestoreOptions.getDefaultInstance().toBuilder()
        .setHost(emulator.emulatorEndpoint)
        .setCredentials(NoCredentials.getInstance())
        .setProjectId("test-project")
        .build()
        .service

    @Test
    fun `snapshot listener delivers committed changes`() = runBlocking {
        val firestore = firestore()
        val persistence = FirestorePokerTablePersistence(firestore)
        val bus = FirestoreTableUpdateBus(firestore)

        val id = Random.nextInt(1, Int.MAX_VALUE)
        val config = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 6)
        PokerTable.new(id = id, firstPlayer = Player(0, "Alice"), config = config, persistence = persistence)

        // Only complete once the listener reports the post-join (2-player) state, ignoring the initial
        // snapshot the listener fires on registration.
        val twoPlayers = CompletableDeferred<PokerTableState>()
        val sub = bus.subscribe(id) { state -> if (state.players.size >= 2) twoPlayers.complete(state) }

        val t2 = PokerTable.restore(id, persistence)!!
        t2.playerJoin(Player(1, "Bob"))
        t2.commit()

        val state = withTimeout(15_000) { twoPlayers.await() }
        assertEquals(2, state.players.size)
        sub.cancel()
        firestore.close()
    }
}
