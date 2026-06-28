package com.gustmmer.poker

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests the single write path: mutators stage in memory, and [PokerTable.commit] persists everything
 * staged since the last commit as one versioned write. The key property is atomicity — several
 * mutations in one commit (e.g. bring a player online *and* unpause) advance the version exactly once,
 * so they can never be split across a concurrency retry.
 */
class PokerTableCommitTest {

    private val persistence = MemoryBasedPokerTablePersistence.json()

    private val config = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 4)

    private fun newTable(playerCount: Int): PokerTable {
        val table = PokerTable.new(id = 1, firstPlayer = Player(0, "P0"), config = config, persistence = persistence)
        (1 until playerCount).forEach { table.playerJoin(Player(it, "P$it")) }
        table.commit()
        return table
    }

    @Test
    fun `a mutator stages without persisting until commit`() {
        val table = newTable(2)
        table.currentState.players.first { it.id == 1 }.setAsIdle()

        // setPlayerOnline stages the change; nothing is written yet.
        assertTrue(table.setPlayerOnline(1))
        assertTrue(table.isDirty)
        assertEquals(PlayerStatus.ONLINE, PokerTable.restore(1, persistence)!!.currentState.players.first { it.id == 1 }.status,
            "stored state should be untouched before commit")

        table.commit()
        assertFalse(table.isDirty)
        assertEquals(PlayerStatus.ONLINE, PokerTable.restore(1, persistence)!!.currentState.players.first { it.id == 1 }.status)
    }

    @Test
    fun `online and unpause compose into a single versioned write`() {
        val table = newTable(2)
        table.currentState.players.first { it.id == 1 }.setAsIdle()
        table.pause(remainingTimerMs = null)
        table.commit()
        val versionBefore = table.currentState.version

        // The composition the service performs: activate the player and resume, one commit.
        table.setPlayerOnline(1)
        table.unpause()
        table.commit()

        assertEquals(versionBefore + 1, table.currentState.version, "two mutations, one version bump")
        val stored = PokerTable.restore(1, persistence)!!.currentState
        assertEquals(GameStatus.RUNNING, stored.gameStatus)
        assertEquals(PlayerStatus.ONLINE, stored.players.first { it.id == 1 }.status)
    }

    @Test
    fun `commit is a no-op when nothing was staged`() {
        val table = newTable(2)
        val versionBefore = table.currentState.version

        assertFalse(table.isDirty)
        table.commit()

        assertEquals(versionBefore, table.currentState.version, "no staged change => no write, no version bump")
    }

    @Test
    fun `setPlayerOnline does not stage for an already-online or eliminated player`() {
        val table = newTable(2)
        // Already online.
        assertFalse(table.setPlayerOnline(0))
        // Eliminated stays eliminated.
        table.currentState.players.first { it.id == 1 }.setAsEliminated()
        assertFalse(table.setPlayerOnline(1))
        assertFalse(table.isDirty)
        assertEquals(PlayerStatus.ELIMINATED, table.currentState.players.first { it.id == 1 }.status)
    }

    @Test
    fun `a stale table loses the commit race and surfaces a conflict`() {
        val table = newTable(2)

        // A concurrent writer advances the stored version behind this table's back.
        PokerTable.restore(1, persistence)!!.apply { setPlayerOffline(1); commit() }

        // This table still thinks it is at the old version; its commit must be rejected.
        table.playerJoin(Player(9, "Late"))
        assertThrows(ConcurrentModificationException::class.java) { table.commit() }
    }
}
