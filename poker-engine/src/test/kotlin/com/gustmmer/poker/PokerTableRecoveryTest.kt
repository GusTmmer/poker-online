package com.gustmmer.poker

import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.round.Call
import com.gustmmer.poker.round.PokerRoundStage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Table-level regressions from a production game that ended in an unloadable table. */
class PokerTableRecoveryTest {

    private val persistence = MemoryBasedPokerTablePersistence.json()

    private val config = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 6)

    private fun table(id: Int, players: Int, config: TableConfig = this.config): PokerTable {
        val table = PokerTable.new(id = id, firstPlayer = Player(0, "P0"), config = config, persistence = persistence)
        (1 until players).forEach { table.playerJoin(Player(it, "P$it")) }
        table.commit()
        return table
    }

    private fun reload(id: Int) = PokerTable.restore(id, persistence)!!

    @Test
    fun `leaving mid-hand keeps the table restorable and drops the player when the hand ends`() {
        val table = table(1, 2)
        table.newPokerRound()
        table.commit()

        val leaver = reload(1)
        leaver.setPlayerOffline(0)
        leaver.kickPlayer(0)
        leaver.commit()

        val after = reload(1)
        assertEquals(PokerRoundStage.SHOWDOWN, after.currentState.roundState!!.pokerRoundStage)
        assertEquals(2000, after.currentState.players.sumOf { it.chips })

        after.clearRoundState()
        after.commit()
        assertEquals(listOf(1), reload(1).currentState.players.map { it.id })
    }

    @Test
    fun `a stored hand that references an unseated player is discarded instead of failing to load`() {
        val table = table(2, 3)
        table.newPokerRound()
        val state = table.currentState
        state.players.removeIf { it.id == 2 }
        persistence.seed(state)

        val restored = persistence.loadState(2)
        assertNotNull(restored)
        assertNull(restored!!.roundState)
        assertEquals(GameStatus.WAITING, restored.gameStatus)
    }

    @Test
    fun `blinds skip players eliminated before the next hand is dealt from a reloaded table`() {
        val table = table(3, 3)
        table.newPokerRound()
        table.commit()

        // Player 1 busts; the hand is cleared in one commit and the next hand dealt from a reload (ready-up).
        val t = reload(3)
        t.currentState.players.single { it.id == 1 }.let { it.removeChips(it.chips) }
        t.clearRoundState()
        t.commit()

        val next = reload(3)
        next.newPokerRound()
        val round = next.currentState.roundState!!
        assertEquals(listOf(0, 2), round.players.map { it.id })
        assertFalse(round.playerOrdering.smallBlindPlayer().isEliminated())
        assertFalse(round.playerOrdering.bigBlindPlayer().isEliminated())
        assertNotEquals(round.playerOrdering.smallBlindPlayer(), round.playerOrdering.bigBlindPlayer())
        next.commit()

        // The persisted positions resolve to the same players.
        val stored = reload(3).currentState.roundState!!
        assertEquals(round.playerOrdering.smallBlindPlayer().id, stored.playerOrdering.smallBlindPlayer().id)
        assertEquals(round.playerOrdering.bettingPlayer().id, stored.playerOrdering.bettingPlayer().id)
    }

    @Test
    fun `restart mid-hand abandons the hand and resets stacks and blinds`() {
        val table = table(4, 2, config.copy(startingBigBlind = 30))
        assertEquals(Blinds(big = 30, small = 15), table.currentState.blinds)
        table.newPokerRound()
        table.increaseBlinds()
        assertEquals(Blinds(big = 60, small = 30), table.currentState.blinds)
        val first = table.currentState.roundState!!.playerOrdering.bettingPlayer().id
        table.processPlayerCommand(Call(first))

        table.restartGame()
        table.commit()

        with(reload(4).currentState) {
            assertNull(roundState)
            assertEquals(GameStatus.WAITING, gameStatus)
            assertEquals(Blinds(big = 30, small = 15), blinds)
            players.forEach { assertEquals(1000, it.chips) }
        }
    }
}
