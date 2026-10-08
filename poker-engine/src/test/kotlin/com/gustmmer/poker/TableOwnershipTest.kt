package com.gustmmer.poker

import com.gustmmer.poker.bot.BotPersonality
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableOwnershipTest {

    private val config = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 8)

    /** Ann (0, the creator) with a bot (1), then [humans] more humans joining in order (2, 3, …). */
    private fun table(humans: Int): PokerTable {
        val table = PokerTable.new(
            id = 1,
            firstPlayer = Player(0, "Ann"),
            bots = listOf(Player(1, "CPU", BotPersonality.BALANCED)),
            config = config,
            persistence = MemoryBasedPokerTablePersistence.json(),
        )
        repeat(humans) { table.playerJoin(Player(table.currentState.nextPlayerId, "Human $it")) }
        return table
    }

    /** Deals a hand and abandons it, so the game's first hand is behind us. */
    private fun PokerTable.afterAHand() = apply {
        newPokerRound()
        clearRoundState()
    }

    @Test
    fun `the creator owns the table`() {
        assertEquals(0, table(humans = 2).currentState.ownerId)
    }

    @Test
    fun `when the owner leaves, the longest-seated human takes over — never a bot`() {
        val table = table(humans = 2)
        table.kickPlayer(0)
        assertEquals(2, table.currentState.ownerId)
    }

    @Test
    fun `an owner who leaves mid-hand hands over at once, though they stay seated to the end of the hand`() {
        val table = table(humans = 2)
        table.newPokerRound()
        table.kickPlayer(0)

        assertTrue(table.currentState.players.any { it.id == 0 })
        assertEquals(2, table.currentState.ownerId)

        table.clearRoundState()
        assertEquals(2, table.currentState.ownerId)
    }

    @Test
    fun `a table every human left is owned by the next human to join`() {
        val table = table(humans = 0)
        table.kickPlayer(0)
        assertNull(table.currentState.ownerId)

        table.playerJoin(Player(table.currentState.nextPlayerId, "Newcomer"))
        assertEquals(2, table.currentState.ownerId)
    }

    @Test
    fun `tables saved before owners existed are owned by their earliest-seated human`() {
        val wire = table(humans = 2).also { it.kickPlayer(0) }.currentState.toWire().copy(ownerId = null)
        assertEquals(2, PokerTableState.restore(wire).ownerId)
    }

    @Test
    fun `ready players never deal a game's first hand — the owner does`() {
        val table = table(humans = 2)
        assertFalse(table.playerReady(0))
        assertFalse(table.playerReady(2))
        assertFalse(table.playerReady(3), "everyone ready, but the first hand is the owner's call")
    }

    @Test
    fun `after the first hand, more than half of the humans being ready deals the next`() {
        val table = table(humans = 3).afterAHand() // four humans
        assertFalse(table.playerReady(2))
        assertFalse(table.playerReady(3), "two of four is only half")
        assertTrue(table.playerReady(4), "three of four is a majority")
    }

    @Test
    fun `a player leaving can leave the ready ones a majority, which deals the next hand`() {
        val table = table(humans = 3).afterAHand() // four humans
        table.playerReady(0)
        table.playerReady(2)
        assertFalse(table.dealIfMajorityReady(), "two of four is only half")

        table.kickPlayer(4)
        assertTrue(table.dealIfMajorityReady(), "two of the three left")
        assertEquals(GameStatus.RUNNING, table.currentState.gameStatus)
    }

    @Test
    fun `absent humans don't count towards the majority`() {
        val table = table(humans = 3).afterAHand() // four humans
        table.currentState.players.single { it.id == 3 }.setAsOffline()
        table.currentState.players.single { it.id == 4 }.setAsIdle()

        assertFalse(table.playerReady(0), "one of the two present is only half")
        assertTrue(table.playerReady(2), "both present humans are ready")
    }

    @Test
    fun `a restarted game's first hand is the owner's again`() {
        val table = table(humans = 1).afterAHand()
        table.restartGame()
        assertFalse(table.playerReady(0))
        assertFalse(table.playerReady(2))
    }
}
