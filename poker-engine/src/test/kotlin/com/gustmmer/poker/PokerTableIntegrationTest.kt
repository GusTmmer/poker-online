package com.gustmmer.poker

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.round.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Integration test that walks through the full lifecycle of a poker table:
 * creation, joining, playing rounds (with serialization round-trips),
 * idle auto-play, blind escalation, elimination, pause/unpause, kick, and restart.
 */
class PokerTableIntegrationTest {

    // Typed as the in-memory impl so tests can use its `seed` seam to stand up arbitrary state.
    private val persistence: MemoryBasedPokerTablePersistence = MemoryBasedPokerTablePersistence.json()

    private val config = TableConfig(
        startingChips = 1000,
        turnTimerSeconds = 30,
        maxPlayers = 4,
        blindEscalationOrbits = 1,
        blindEscalationMultiplier = 2.0,
    )

    @Test
    fun `full table lifecycle`() {
        val tableId = 42

        // ── Phase 1: Create table and join players ──────────────────────────

        val table = PokerTable.new(
            id = tableId,
            firstPlayer = Player(0, "Alice"),
            config = config,
            persistence = persistence,
        )

        assertEquals(1, table.currentState.players.size)
        assertEquals(1000, table.currentState.players[0].chips)
        assertEquals("Alice", table.currentState.players[0].name)

        assertTrue(table.playerJoin(Player(1, "Bob")))
        assertTrue(table.playerJoin(Player(2, "Charlie")))
        table.commit()

        assertEquals(3, table.currentState.players.size)
        table.currentState.players.forEach { assertEquals(1000, it.chips) }

        // Verify persistence round-trip before any round
        val restored = PokerTable.restore(tableId, persistence)!!
        assertEquals(3, restored.currentState.players.size)
        restored.currentState.players.forEach { assertEquals(1000, it.chips) }

        // ── Phase 2: Play round 1 (fold-to-win, with serialization between moves)

        val totalChipsBefore = table.currentState.players.sumOf { it.chips }
        table.newPokerRound()
        table.commit()
        assertNotNull(table.currentState.roundState)
        assertEquals(PokerRoundStage.BET_BLINDS, table.currentState.roundState!!.pokerRoundStage)

        // Fold all but one, restoring from persistence between each move
        foldAllButOne(tableId)

        val afterRound1 = restoreTable(tableId)
        assertEquals(totalChipsBefore, afterRound1.currentState.players.sumOf { it.chips },
            "Total chips must be conserved")

        // ── Phase 3: Start round 2 with an idle player ─────────────────────

        val betweenRounds = restoreTable(tableId)
        betweenRounds.clearRoundState()
        betweenRounds.commit()

        // Mark player 1 as idle before starting the round
        val idleTable = restoreTable(tableId)
        idleTable.currentState.players.find { it.id == 1 }!!.setAsIdle()
        persistence.seed(idleTable.currentState)

        val round2Table = restoreTable(tableId)
        assertEquals(PlayerStatus.IDLE, round2Table.currentState.players.find { it.id == 1 }!!.status)

        round2Table.newPokerRound()
        round2Table.commit()

        // Auto-play for idle/offline players
        drainAutoPlays(tableId)

        // Verify the idle player's turn was auto-resolved
        val afterAutoPlay = restoreTable(tableId)
        val rs2 = afterAutoPlay.currentState.roundState
        if (rs2 != null && rs2.pokerRoundStage.isBettingRound()) {
            assertNotEquals(1, rs2.playerOrdering.bettingPlayer().id,
                "Idle player's turn should have been auto-resolved")
        }

        // Finish round 2 and verify chip conservation
        foldAllButOne(tableId)
        assertEquals(3000, restoreTable(tableId).currentState.players.sumOf { it.chips },
            "Total chips conserved after round with idle player")

        // ── Phase 4: Blind escalation ───────────────────────────────────────

        finishCurrentRound(tableId)
        val initialBlinds = restoreTable(tableId).currentState.blinds
        playQuickRounds(tableId, count = 3)

        val escalatedBlinds = restoreTable(tableId).currentState.blinds
        assertTrue(escalatedBlinds.big > initialBlinds.big,
            "Blinds should have escalated: was ${initialBlinds.big}, now ${escalatedBlinds.big}")
        assertEquals(initialBlinds.big * 2, escalatedBlinds.big, "Blinds should double")

        // ── Phase 5: Pause / Unpause ────────────────────────────────────────

        val pauseTable = restoreTable(tableId)
        assertFalse(pauseTable.currentState.gameStatus == GameStatus.PAUSED)

        pauseTable.pause(remainingTimerMs = 15000L)
        pauseTable.commit()
        assertTrue(restoreTable(tableId).currentState.gameStatus == GameStatus.PAUSED)
        assertEquals(15000L, restoreTable(tableId).currentState.turnTimeRemainingMs)

        val unpauseTable = restoreTable(tableId)
        val remaining = unpauseTable.unpause()
        unpauseTable.commit()
        assertEquals(15000L, remaining)
        assertFalse(restoreTable(tableId).currentState.gameStatus == GameStatus.PAUSED)
        assertNull(restoreTable(tableId).currentState.turnTimeRemainingMs)

        // ── Phase 6: Kick a player ──────────────────────────────────────────

        finishCurrentRound(tableId)
        assertEquals(3, restoreTable(tableId).currentState.players.size)

        val beforeKick = restoreTable(tableId)
        beforeKick.currentState.players.find { it.id == 2 }!!.setAsOffline()
        persistence.seed(beforeKick.currentState)

        restoreTable(tableId).apply { kickPlayer(2); commit() }
        val afterKick = restoreTable(tableId)
        assertEquals(2, afterKick.currentState.players.size)
        assertNull(afterKick.currentState.players.find { it.id == 2 })

        // ── Phase 7: Join, then close the table ─────────────────────────────

        val joinTable = restoreTable(tableId)
        assertTrue(joinTable.playerJoin(Player(3, "Diana")))
        joinTable.commit()
        assertEquals(3, restoreTable(tableId).currentState.players.size)

        restoreTable(tableId).apply { updateConfig(isOpen = false); commit() }
        assertFalse(restoreTable(tableId).currentState.config.isOpen)
        assertFalse(restoreTable(tableId).playerJoin(Player(4, "Eve")),
            "Should not join a closed table")

        // ── Phase 8: Restart game ───────────────────────────────────────────

        finishCurrentRound(tableId)
        restoreTable(tableId).apply { updateConfig(isOpen = true); commit() }
        restoreTable(tableId).apply { restartGame(); commit() }

        val restarted = restoreTable(tableId)
        restarted.currentState.players.forEach { player ->
            assertEquals(1000, player.chips, "Player ${player.name} should have starting chips")
            assertEquals(PlayerStatus.ONLINE, player.status, "Player ${player.name} should be ONLINE")
        }
        assertEquals(Blinds(big = 20, small = 10), restarted.currentState.blinds,
            "Blinds should be reset to initial")

        // ── Phase 9: Verify a full round works after restart ────────────────

        val finalTable = restoreTable(tableId)
        finalTable.newPokerRound()
        finalTable.commit()
        assertNotNull(finalTable.currentState.roundState)

        foldAllButOne(tableId)
        assertEquals(3000, restoreTable(tableId).currentState.players.sumOf { it.chips },
            "Total chips conserved after restart round")
    }

    @Test
    fun `elimination when player loses all chips`() {
        val lowConfig = config.copy(startingChips = 50)
        val table = PokerTable.new(
            id = 500,
            firstPlayer = Player(0, "A"),
            config = lowConfig,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "B"))
        table.playerJoin(Player(2, "C"))

        // Play rounds where one player always folds (losing blinds each time)
        // With startingChips=50 and big blind=1, small blind=0, this takes a while.
        // Blinds = 50/50 = 1 big, 50/100 = 0 small — too low. Use direct all-in instead.
        table.newPokerRound()

        // Have all players go all-in
        val rs = table.currentState.roundState!!
        var safety = 10
        while (safety-- > 0) {
            val roundState = table.currentState.roundState ?: break
            if (!roundState.pokerRoundStage.isBettingRound()) break
            val bettor = roundState.playerOrdering.bettingPlayer()
            table.processPlayerCommand(AllIn(bettor.id))
        }

        // Total chips are conserved once the showdown resolves.
        assertEquals(150, table.currentState.players.sumOf { it.chips })

        // A busted player is NOT eliminated yet at showdown — elimination folds the player, which would
        // hide their cards from the showdown reveal. They stay ACTIVE through the SHOWDOWN state so the
        // hand can be shown; the bust is realised when the round is cleared. (Vacuous on a board-tie
        // where everyone gets their chips back — no bust to observe.)
        table.currentState.players.filter { it.chips == 0 }.forEach { player ->
            assertTrue(player.isActive(),
                "Player ${player.name} should still be active (not folded/eliminated) at showdown")
        }

        // Clearing the round (the WAITING transition) realises the busts.
        table.clearRoundState()
        table.currentState.players.filter { it.chips == 0 }.forEach { player ->
            assertEquals(PlayerStatus.ELIMINATED, player.status,
                "Player ${player.name} with 0 chips should be ELIMINATED after the round is cleared")
        }
    }

    @Test
    fun `max players enforcement`() {
        val config = config.copy(maxPlayers = 2)
        val table = PokerTable.new(
            id = 100,
            firstPlayer = Player(0, "A"),
            config = config,
            persistence = persistence,
        )
        assertTrue(table.playerJoin(Player(1, "B")))
        assertFalse(table.playerJoin(Player(2, "C")), "Should reject when table is full")
    }

    @Test
    fun `kick during active round folds the player`() {
        val table = PokerTable.new(
            id = 200,
            firstPlayer = Player(0, "A"),
            config = config,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "B"))
        table.playerJoin(Player(2, "C"))
        table.newPokerRound()

        assertTrue(table.currentState.roundState!!.pokerRoundStage.isBettingRound())

        table.kickPlayer(1)

        assertEquals(2, table.currentState.players.size)
        assertNull(table.currentState.players.find { it.id == 1 })
    }

    @Test
    fun `autoPlayForCurrentPlayer checks when no bet to match`() {
        val table = PokerTable.new(
            id = 300,
            firstPlayer = Player(0, "A"),
            config = config,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "B"))
        table.playerJoin(Player(2, "C"))

        table.newPokerRound()

        // Everyone calls through blinds round to get to flop
        playThroughBettingRound(table)

        val rs = table.currentState.roundState
        if (rs != null && rs.pokerRoundStage.isBettingRound()) {
            val current = rs.playerOrdering.bettingPlayer()
            current.setAsIdle()
            val chipsToMatch = rs.bettingRoundState!!.pot.chipsToMatchCurrentBet(current)

            val cmd = table.autoPlayForCurrentPlayer()
            assertNotNull(cmd)

            if (chipsToMatch == 0) {
                assertEquals(CommandType.CALL, cmd!!.type,
                    "Idle player should check (call 0) when no bet to match")
            } else {
                assertEquals(CommandType.FOLD, cmd!!.type,
                    "Idle player should fold when there's a bet to match")
            }
        }
    }

    @Test
    fun `offline player auto-folds`() {
        val table = PokerTable.new(
            id = 400,
            firstPlayer = Player(0, "A"),
            config = config,
            persistence = persistence,
        )
        table.playerJoin(Player(1, "B"))
        table.playerJoin(Player(2, "C"))

        table.newPokerRound()

        val rs = table.currentState.roundState!!
        val current = rs.playerOrdering.bettingPlayer()
        current.setAsOffline()

        val cmd = table.autoPlayForCurrentPlayer()
        assertNotNull(cmd)
        assertEquals(CommandType.FOLD, cmd!!.type, "Offline player should always fold")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun restoreTable(tableId: Int): PokerTable {
        return PokerTable.restore(tableId, persistence)!!
    }

    private fun foldAllButOne(tableId: Int) {
        var safety = 20
        while (safety-- > 0) {
            val t = restoreTable(tableId)
            val rs = t.currentState.roundState ?: break
            if (!rs.pokerRoundStage.isBettingRound()) break
            t.processPlayerCommand(Fold(rs.playerOrdering.bettingPlayer().id))
            t.commit()
        }
    }

    private fun playThroughBettingRound(table: PokerTable) {
        val startStage = table.currentState.roundState?.pokerRoundStage ?: return
        var safety = 20
        while (safety-- > 0) {
            val rs = table.currentState.roundState ?: break
            if (rs.pokerRoundStage != startStage || !rs.pokerRoundStage.isBettingRound()) break
            table.processPlayerCommand(Call(rs.playerOrdering.bettingPlayer().id))
        }
    }

    private fun finishCurrentRound(tableId: Int) {
        val t = restoreTable(tableId)
        if (t.currentState.roundState != null) {
            foldAllButOne(tableId)
            val t2 = restoreTable(tableId)
            if (t2.currentState.roundState != null) {
                t2.clearRoundState()
                t2.commit()
            }
        }
    }

    private fun playQuickRounds(tableId: Int, count: Int) {
        repeat(count) {
            finishCurrentRound(tableId)
            val t = restoreTable(tableId)
            if (t.currentState.players.participating().size >= 2) {
                t.newPokerRound()
                t.commit()
                foldAllButOne(tableId)
            }
        }
        finishCurrentRound(tableId)
    }

    private fun drainAutoPlays(tableId: Int) {
        var safety = 20
        while (safety-- > 0) {
            val t = restoreTable(tableId)
            val rs = t.currentState.roundState ?: break
            if (!rs.pokerRoundStage.isBettingRound()) break
            if (rs.playerOrdering.bettingPlayer().status == PlayerStatus.ONLINE) break
            t.autoPlayForCurrentPlayer() ?: break
            t.commit()
        }
    }
}
