package com.gustmmer.poker.server

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.server.config.ServerConfig
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Acceptance tests simulating 3 poker clients (Alice, Bob, Charlie) through full game scenarios.
 *
 * Player assignment is deterministic based on join order:
 *   Alice = player 0 (table creator, dealer in round 1, acts first pre-flop)
 *   Bob   = player 1 (small blind in round 1)
 *   Charlie = player 2 (big blind in round 1)
 *
 * Each scenario is self-contained and can be run independently.
 */
class AcceptanceTest {

    private val testConfig = ServerConfig(
        port = 8080,
        jwtSecret = "acceptance-test-secret",
        firestoreProjectId = "test",
        voteTimeoutSeconds = 60,
    )

    // ── Scenario 1 ────────────────────────────────────────────────────────────
    // Three players join, play a hand via fold cascade, then all ready-up to
    // automatically start the next round without a manual /start-round call.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 1 - full hand via fold cascade, ready-up auto-starts next round`() = testApplication {
        val (alice, bob, charlie) = threeClients()

        val tableId = createAndJoin(alice, bob, charlie)

        // Alice starts round 1 explicitly (first round always requires /start-round)
        assertTrue(alice.startRound(tableId).isOk())
        assertEquals("RUNNING", alice.getTable(tableId).gameStatus)

        // Fold cascade: whoever's turn it is folds until the round ends
        foldCascade(listOf(alice, bob, charlie), tableId)

        // Round ended — table should be waiting for ready-ups
        assertEquals("WAITING", alice.getTable(tableId).gameStatus)

        // Alice and Bob mark ready; round should NOT auto-start yet
        assertEquals("ready", alice.ready(tableId).statusField)
        assertEquals("ready", bob.ready(tableId).statusField)

        // Charlie's ready is the last one — should auto-start round 2
        val charlieReady = charlie.ready(tableId)
        assertTrue(charlieReady.isOk())
        assertEquals("round_started", charlieReady.statusField)

        // Round 2 is now live
        assertEquals("RUNNING", alice.getTable(tableId).gameStatus)

        // In round 2 the dealer advances: Bob (id=1) is now dealer, so Bob acts first pre-flop.
        // Verify Alice is rejected when she tries to act (it is Bob's turn).
        val aliceBadAction = alice.fold(tableId)
        assertEquals(HttpStatusCode.BadRequest, aliceBadAction.status)
        assertTrue(aliceBadAction.error!!.contains("Not your turn"))
    }

    // ── Scenario 2 ────────────────────────────────────────────────────────────
    // Pause and unpause via the direct /voting-sessions API.
    // With 3 participating players, requiredVotes = ceil(3/2) = 2.
    // The initiator auto-casts yes, so one additional yes vote is needed.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 2 - pause and unpause via voting API (2-of-3 majority)`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Alice initiates a PAUSE_GAME vote (auto-casts her own yes = 1/2 needed → PENDING)
        val pauseCreate = alice.createVote(tableId, "PAUSE_GAME")
        assertEquals("PENDING", pauseCreate.outcome)
        assertEquals(1, pauseCreate.yesCount)
        assertEquals(2, pauseCreate.requiredVotes)

        // Bob votes yes — vote reaches 2/2 → PASSED, game transitions to PAUSED
        val bobVote = bob.castVote(tableId, pauseCreate.sessionId, "yes")
        assertEquals("PASSED", bobVote!!.outcome)

        assertEquals("PAUSED", alice.getTable(tableId).gameStatus)

        // Charlie initiates UNPAUSE_GAME vote (auto-casts her yes = 1/2 → PENDING)
        val unpauseCreate = charlie.createVote(tableId, "UNPAUSE_GAME")
        assertEquals("PENDING", unpauseCreate.outcome)

        // Alice votes yes → PASSED, game transitions back to RUNNING
        val aliceVote = alice.castVote(tableId, unpauseCreate.sessionId, "yes")
        assertEquals("PASSED", aliceVote!!.outcome)

        assertEquals("RUNNING", alice.getTable(tableId).gameStatus)
    }

    // ── Scenario 3 ────────────────────────────────────────────────────────────
    // Turn enforcement and betting actions.
    //
    // Round 1 player order (determined by PlayerOrdering.forNewTable with dealer=0):
    //   dealer=Alice(0), SB=Bob(1), BB=Charlie(2), UTG=Alice(0) first to act pre-flop.
    //
    // Actions:
    //   1. Bob (not his turn) tries to act  → 400 "Not your turn"
    //   2. Alice raises 50                  → 200
    //   3. Bob calls                        → 200
    //   4. Charlie folds                    → 200 (only Alice+Bob remain)
    //   Flop (SB=Bob goes first post-flop):
    //   5. Bob folds                        → 200 (only Alice remains → round ends)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 3 - turn enforcement, wrong player rejected, raise and call progress the hand`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Starting chips: each player has 500; blinds reduce Bob and Charlie's stacks
        val startingChips = alice.getTable(tableId).players.associate { it.name to it.chips }

        // Bob tries to act while it's Alice's turn — must be rejected
        val bobOutOfTurn = bob.fold(tableId)
        assertEquals(HttpStatusCode.BadRequest, bobOutOfTurn.status)
        assertEquals("Not your turn", bobOutOfTurn.error)

        // Alice raises 50 (Alice is UTG — first to act pre-flop)
        assertTrue(alice.raise(tableId, 50).isOk())

        // Bob calls Alice's raise
        assertTrue(bob.call(tableId).isOk())

        // Charlie folds (as big blind, last to act pre-flop)
        assertTrue(charlie.fold(tableId).isOk())

        // Pre-flop betting round is over; flop betting begins.
        // Post-flop order starts from SB (Bob = id 1). Bob folds → only Alice remains → round ends.
        assertTrue(bob.fold(tableId).isOk())

        // Round is over; Alice won the pot.
        assertEquals("WAITING", alice.getTable(tableId).gameStatus)

        val endPlayers = alice.getTable(tableId).players

        // Verify total chips are conserved (sum stays at 1500 = 3 × 500)
        assertEquals(1500, endPlayers.sumOf { it.chips })

        // Verify Alice gained chips (she won the pot)
        val endAliceChips = endPlayers.first { it.name == "Alice" }.chips
        assertTrue(endAliceChips > startingChips["Alice"]!!, "Alice should have gained chips after winning the pot")
    }

    // ── Scenario 4 ────────────────────────────────────────────────────────────
    // A voting session fails immediately when a majority casts "no" votes —
    // the session does not wait for the timeout.
    //
    // With 3 players, requiredVotes = ceil(3/2) = 2.
    // Alice creates PAUSE session (auto-yes = 1). Bob votes no (1–1, pending).
    // Charlie votes no (1–2, ≥ requiredVotes) → FAILED immediately.
    // Game must still be RUNNING because the pause never passed.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 4 - majority no votes fail the session immediately`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Alice creates PAUSE vote (auto-yes, 1 of 2 required → PENDING)
        val created = alice.createVote(tableId, "PAUSE_GAME")
        assertEquals("PENDING", created.outcome)

        // Bob votes no — 1 yes vs 1 no, not yet a majority of either → PENDING
        val bobNo = bob.castVote(tableId, created.sessionId, "no")
        assertEquals("PENDING", bobNo!!.outcome)

        // Charlie votes no — 1 yes vs 2 no, noVotes (2) ≥ requiredVotes (2) → FAILED
        val charlieNo = charlie.castVote(tableId, created.sessionId, "no")
        assertEquals("FAILED", charlieNo!!.outcome)

        // Game must still be RUNNING — the failed vote did not pause the game
        assertEquals("RUNNING", alice.getTable(tableId).gameStatus)

        // Session was cleaned up; any subsequent vote on the same sessionId returns null (404)
        assertNull(alice.castVote(tableId, created.sessionId, "yes"))
    }

    // ── Scenario 5 ────────────────────────────────────────────────────────────
    // A re-raise forces the original raiser back into the betting round.
    //
    // Pre-flop order (round 1): Alice(UTG), Bob(SB), Charlie(BB).
    //   Alice raises 20 → Bob re-raises 30 → Charlie folds.
    // After Charlie folds the active bet belongs to Bob (lastRaiser). Alice has
    // not yet matched Bob's total, so it is Alice's turn — not Bob's.
    // Bob attempting to fold must be rejected ("Not your turn").
    // Alice calling completes the pre-flop and advances to the flop.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 5 - re-raise forces original raiser to respond before round advances`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Alice (UTG) opens with a raise of 20 (total bet = BB 10 + raise 20 = 30)
        assertTrue(alice.raise(tableId, 20).isOk())

        // Bob (SB) re-raises 30 (minimum re-raise ≥ currentBet=30; total Bob bet = 60)
        assertTrue(bob.raise(tableId, 30).isOk())

        // Charlie (BB) folds — only Alice and Bob remain, but Alice must still respond
        assertTrue(charlie.fold(tableId).isOk())

        // Bob is the lastRaiser but Alice has not yet matched his bet — it is Alice's turn
        val bobOutOfTurn = bob.fold(tableId)
        assertEquals(HttpStatusCode.BadRequest, bobOutOfTurn.status)
        assertTrue(bobOutOfTurn.error!!.contains("Not your turn"))

        // Alice calls Bob's raise — pre-flop ends, flop begins
        assertTrue(alice.call(tableId).isOk())

        // Game is still RUNNING (round advanced to BET_FLOP, not ended)
        assertEquals("RUNNING", alice.getTable(tableId).gameStatus)
    }

    // ── Scenario 6 ────────────────────────────────────────────────────────────
    // Player actions are rejected while the game is paused, and allowed again
    // after a successful unpause vote.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 6 - actions are rejected while paused and allowed after unpause`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Alice starts a PAUSE vote and Bob confirms it → game is now PAUSED
        val pause = alice.createVote(tableId, "PAUSE_GAME")
        bob.castVote(tableId, pause.sessionId, "yes")
        assertEquals("PAUSED", alice.getTable(tableId).gameStatus)

        // Alice (current player, UTG) tries to act while paused → 400
        val rejectedFold = alice.fold(tableId)
        assertEquals(HttpStatusCode.BadRequest, rejectedFold.status)
        assertEquals("Game is paused", rejectedFold.error)

        // Charlie starts an UNPAUSE vote; Alice confirms → game is RUNNING again
        val unpause = charlie.createVote(tableId, "UNPAUSE_GAME")
        alice.castVote(tableId, unpause.sessionId, "yes")
        assertEquals("RUNNING", alice.getTable(tableId).gameStatus)

        // Alice can now act (fold succeeds)
        assertTrue(alice.fold(tableId).isOk())
    }

    // ── Scenario 7 ────────────────────────────────────────────────────────────
    // A RAISE below the big-blind minimum is rejected with 400 (not 500).
    //
    // With startingChips=500 the big blind is 10. RAISE 9 is below the minimum.
    // RAISE 10 is exactly the minimum and must succeed.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 7 - raise below big blind minimum is rejected with 400`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Alice (UTG) tries to raise below the big blind of 10 → 400
        val tooLow = alice.raise(tableId, 9)
        assertEquals(HttpStatusCode.BadRequest, tooLow.status)
        assertTrue(tooLow.error!!.contains("invalid", ignoreCase = true))

        // Alice's turn is NOT consumed by the failed raise; she can raise 10 (minimum)
        assertTrue(alice.raise(tableId, 10).isOk())
    }

    // ── Scenario 8 ────────────────────────────────────────────────────────────
    // All three players go ALL_IN immediately. Because no player can bet any
    // further, all five community cards are revealed and the showdown resolves
    // automatically — no fold or check needed to advance.
    //
    // The invariant tested here is card-outcome-agnostic: chips are conserved,
    // any player with 0 chips is ELIMINATED, and any player with chips is ONLINE
    // (i.e. a winner is never incorrectly marked ELIMINATED mid-distribution).
    //
    // The game-over state machine (ready blocked, restart allowed) is exercised
    // when a clear winner emerges; in a board-tie outcome all players get their
    // chips back, no one is eliminated, and we verify the opposite path.
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 8 - all-in cascade resolves showdown automatically with correct chip and status invariants`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        val tableId = createAndJoin(alice, bob, charlie)
        alice.startRound(tableId)

        // Verify restart is rejected while the game is still in progress
        assertEquals(HttpStatusCode.BadRequest, alice.restartGame(tableId).status)

        // All three go ALL_IN pre-flop in UTG order (Alice → Bob → Charlie)
        assertTrue(alice.allIn(tableId).isOk())
        assertTrue(bob.allIn(tableId).isOk())
        // Charlie's ALL_IN is the last; it triggers showdown + auto-clearRoundState
        assertTrue(charlie.allIn(tableId).isOk())

        // Round resolved automatically — table is WAITING with no lingering round
        assertEquals("WAITING", alice.getTable(tableId).gameStatus)

        val players = alice.getTable(tableId).players

        // Chips are conserved: 3 × 500 = 1500 regardless of who won or tied
        assertEquals(1500, players.sumOf { it.chips })

        // Core invariant: any player with 0 chips must be ELIMINATED (not ONLINE with 0)
        players.filter { it.chips == 0 }.forEach { assertEquals("ELIMINATED", it.status) }

        // And any player with chips remaining must NOT be ELIMINATED (winner bug regression guard)
        players.filter { it.chips > 0 }.forEach { assertEquals("ONLINE", it.status) }

        val eliminatedCount = players.count { it.status == "ELIMINATED" }
        if (eliminatedCount >= 2) {
            // Clear winner: isGameOver() is true — /ready must be blocked and /restart-game must work
            val readyResp = alice.ready(tableId)
            assertEquals(HttpStatusCode.BadRequest, readyResp.status)
            assertTrue(readyResp.error!!.contains("over", ignoreCase = true))

            assertTrue(alice.restartGame(tableId).isOk())

            // After restart every player is back to 500 chips and ONLINE
            val afterRestart = alice.getTable(tableId).players
            afterRestart.forEach {
                assertEquals("ONLINE", it.status)
                assertEquals(500, it.chips)
            }
        } else {
            // Board tie (all players share best hand): nobody eliminated, game is still live.
            // /restart-game must be rejected; ready-up must work normally.
            assertEquals(HttpStatusCode.BadRequest, alice.restartGame(tableId).status)
            assertTrue(alice.ready(tableId).isOk())
        }
    }

    // ── Scenario 9 ────────────────────────────────────────────────────────────
    // Blind escalation doubles the big blind after enough completed orbits.
    //
    // Table config: startingChips=100, blindEscalationOrbits=1.
    //   initial big = 100/50 = 2
    //   handsPerEscalation = initialPlayerCount(3) × orbits(1) = 3
    //   Escalation triggers on round 3 start (roundsSinceLastEscalation reaches 3).
    //   New big = 4.  Minimum raise = 4.
    //
    // With the standard fold cascade (UTG folds first each hand), dealer cycles:
    //   Round 1: dealer=Alice(0), UTG=Alice(0)
    //   Round 2: dealer=Bob(1),   UTG=Bob(1)
    //   Round 3: dealer=Charlie(2), UTG=Charlie(2)  ← escalated blinds apply here
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `scenario 9 - blind escalation doubles minimum raise after enough orbits`() = testApplication {
        val (alice, bob, charlie) = threeClients()
        // Custom table: small starting chips so big blind = 2, with 1-orbit escalation
        val tableId = alice.createTable(
            playerName = "Alice",
            startingChips = 100,
            turnTimerSeconds = 300,
            blindEscalationOrbits = 1,
        ).tableId
        assertEquals(HttpStatusCode.Created, bob.joinTable(tableId, "Bob"))
        assertEquals(HttpStatusCode.Created, charlie.joinTable(tableId, "Charlie"))

        // Start round 1 (initial big blind = 2)
        alice.startRound(tableId)

        // Complete hands 1 and 2; each time the last /ready auto-starts the next round
        val clients = listOf(alice, bob, charlie)
        playCompleteHandAndReadyUp(clients, tableId)   // hand 1 → round 2 starts
        playCompleteHandAndReadyUp(clients, tableId)   // hand 2 → round 3 starts (escalated!)

        // Round 3 UTG is Charlie (dealer cycles 0→1→2). Big blind is now 4.
        // RAISE 2 (< new big=4) must be rejected
        assertEquals(HttpStatusCode.BadRequest, charlie.raise(tableId, 2).status)

        // RAISE 4 (exactly the new minimum) must succeed
        assertTrue(charlie.raise(tableId, 4).isOk())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun ApplicationTestBuilder.threeClients(): Triple<PokerClient, PokerClient, PokerClient> {
        val persistence = MemoryBasedPokerTablePersistence.json()
        application { configureServer(persistence, testConfig) }
        fun client() = PokerClient(createClient {
            install(ContentNegotiation) { json() }
            install(HttpCookies)
            install(WebSockets)
            install(Resources)
        })
        return Triple(client(), client(), client())
    }

    private suspend fun createAndJoin(alice: PokerClient, bob: PokerClient, charlie: PokerClient): Int {
        val tableId = alice.createTable(playerName = "Alice", startingChips = 500, turnTimerSeconds = 300).tableId
        assertEquals(HttpStatusCode.Created, bob.joinTable(tableId, "Bob"))
        assertEquals(HttpStatusCode.Created, charlie.joinTable(tableId, "Charlie"))
        return tableId
    }

    /** Fold-cascades the current hand to completion, then has every client mark ready.
     *  The last /ready call auto-starts the next round via the ready-up mechanic. */
    private suspend fun playCompleteHandAndReadyUp(clients: List<PokerClient>, tableId: Int) {
        foldCascade(clients, tableId)
        for (client in clients) {
            client.ready(tableId)
        }
    }

    /** Tries each client in round-robin until one succeeds with FOLD; repeats until no client can act. */
    private suspend fun foldCascade(clients: List<PokerClient>, tableId: Int) {
        var safety = 30
        while (safety-- > 0) {
            var acted = false
            for (c in clients) {
                if (c.fold(tableId).isOk()) {
                    acted = true
                    break
                }
            }
            if (!acted) break
        }
    }
}
