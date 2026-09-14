package com.gustmmer.poker

import com.gustmmer.poker.bot.BotPersonality
import com.gustmmer.poker.persistence.JsonSerializer
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.round.Call
import com.gustmmer.poker.round.Fold
import com.gustmmer.poker.round.HandActionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PokerTableBotTest {

    private val config = TableConfig(startingChips = 1000, turnTimerSeconds = 30, maxPlayers = 6)

    private fun tableWithBots(vararg personalities: BotPersonality) = PokerTable.new(
        id = 1,
        firstPlayer = Player(0, "Human"),
        bots = personalities.mapIndexed { i, p -> Player(i + 1, "CPU $i", p) },
        config = config,
        persistence = MemoryBasedPokerTablePersistence.json(),
    )

    @Test
    fun `bots are seated with starting chips at creation`() {
        val table = tableWithBots(BotPersonality.AGGRESSIVE, BotPersonality.DEFENSIVE)
        val players = table.currentState.players
        assertEquals(listOf(false, true, true), players.map { it.isBot })
        assertTrue(players.all { it.chips == 1000 })
        assertEquals(BotPersonality.DEFENSIVE, players[2].botPersonality)
    }

    @Test
    fun `bots never hold up ready-up`() {
        val table = tableWithBots(BotPersonality.BALANCED, BotPersonality.BALANCED)
        assertTrue(table.playerReady(0), "the only human being ready is enough")
    }

    @Test
    fun `bots don't count towards the idle majority`() {
        val table = tableWithBots(BotPersonality.BALANCED, BotPersonality.BALANCED, BotPersonality.BALANCED)
        assertFalse(table.currentState.majorityIdle())
        table.currentState.players[0].setAsIdle()
        assertTrue(table.currentState.majorityIdle(), "the one human idling is the whole human table")
    }

    @Test
    fun `plays bot turns and leaves human turns alone`() {
        val table = tableWithBots(BotPersonality.AGGRESSIVE, BotPersonality.BALANCED)
        table.newPokerRound()
        // Three-handed: seat 0 is the button and acts first pre-flop.
        assertNull(table.playBotTurn(), "it's the human's turn")
        assertNull(table.botDecisionForCurrentPlayer())

        table.processPlayerCommand(Call(0))
        val round = table.currentState.roundState!!
        val bot = round.playerOrdering.bettingPlayer()
        assertTrue(bot.isBot)
        assertNotNull(table.botDecisionForCurrentPlayer())

        val played = table.playBotTurn()
        assertNotNull(played)
        assertEquals(bot.id, played!!.playerId)
        assertEquals(bot.id, table.currentState.roundState!!.actions.last().playerId)
    }

    @Test
    fun `once the human folds, the bots play the hand to completion`() {
        val table = tableWithBots(BotPersonality.AGGRESSIVE, BotPersonality.BALANCED, BotPersonality.DEFENSIVE)
        table.newPokerRound()
        var guard = 0
        while (table.currentState.roundState!!.pokerRoundStage.isBettingRound()) {
            if (table.currentState.roundState!!.playerOrdering.bettingPlayer().isBot) {
                assertNotNull(table.playBotTurn())
            } else {
                table.processPlayerCommand(Fold(0))
            }
            check(guard++ < 100)
        }
        assertEquals(4000, table.currentState.players.sumOf { it.chips })
    }

    @Test
    fun `the action log records blinds and actions in order`() {
        val table = tableWithBots(BotPersonality.BALANCED, BotPersonality.BALANCED)
        table.newPokerRound()
        table.processPlayerCommand(Call(0))
        val actions = table.currentState.roundState!!.actions
        assertEquals(listOf(HandActionType.SMALL_BLIND, HandActionType.BIG_BLIND, HandActionType.CALL), actions.map { it.type })
        assertEquals(listOf(1, 2, 0), actions.map { it.playerId })
        assertEquals(listOf(10, 20, 20), actions.map { it.amount })
    }

    @Test
    fun `bot personalities and the action log survive serialization`() {
        val table = tableWithBots(BotPersonality.DEFENSIVE)
        table.newPokerRound()
        val restored = JsonSerializer().run { deserialize(serialize(table.currentState)) }

        assertEquals(listOf(null, BotPersonality.DEFENSIVE), restored.players.map { it.botPersonality })
        assertEquals(table.currentState.roundState!!.actions, restored.roundState!!.actions)
    }

    @Test
    fun `records written before bots existed still load`() {
        val table = tableWithBots()
        table.playerJoin(Player(1, "Another human"))
        table.newPokerRound()
        val json = JsonSerializer().serialize(table.currentState)
            .replace(Regex(""",\s*"botPersonality":\s*null"""), "")
            .replace(Regex(""",\s*"actions":\s*\[[^\]]*\]"""), "")
        assertFalse("botPersonality" in json || "\"actions\"" in json, "fixture strips the new fields")

        val restored = JsonSerializer().deserialize(json)
        assertTrue(restored.players.none { it.isBot })
        assertTrue(restored.roundState?.actions?.isEmpty() ?: true)
    }
}
