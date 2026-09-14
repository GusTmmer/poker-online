package com.gustmmer.poker.bot

import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.Player
import com.gustmmer.poker.TableConfig
import com.gustmmer.poker.persistence.MemoryBasedPokerTablePersistence
import com.gustmmer.poker.round.CommandType
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.PokerRoundStage
import kotlin.random.Random

/**
 * Plays computer players against each other headlessly and reports the standard poker-tracker stats per
 * personality, for tuning [BotPersonality] profiles:
 *  - VPIP: share of hands where the player voluntarily put chips in pre-flop (blinds don't count)
 *  - PFR: share of hands where the player raised pre-flop
 *  - AF: post-flop aggression factor, (bets + raises) / calls
 *  - steal: share of unopened, un-limped pots in late position that the player raised
 *
 * Decisions are applied straight through the engine's validation, without [PokerTable.playBotTurn]'s
 * check-or-fold safety net, so a strategy that produced an illegal move throws here. Stacks are reset
 * after each hand so the stats reflect deep-stacked play at a constant depth.
 */
class BotSimulationHarness(
    private val seats: List<BotPersonality>,
    private val startingChips: Int = 10_000,
    private val bigBlind: Int = 100,
    seed: Long = 1,
) {
    private val rng = Random(seed)

    class Stats {
        var hands = 0
        var vpip = 0
        var pfr = 0
        var postflopAggressive = 0
        var postflopCalls = 0
        var stealOpportunities = 0
        var steals = 0

        val vpipRate get() = vpip.toDouble() / hands
        val pfrRate get() = pfr.toDouble() / hands
        val aggressionFactor get() = postflopAggressive.toDouble() / postflopCalls.coerceAtLeast(1)
        val stealRate get() = steals.toDouble() / stealOpportunities.coerceAtLeast(1)

        override fun toString() =
            "VPIP %4.1f%%  PFR %4.1f%%  AF %.2f  steal %4.1f%% (%d hands)"
                .format(vpipRate * 100, pfrRate * 100, aggressionFactor, stealRate * 100, hands)
    }

    fun run(hands: Int): Map<BotPersonality, Stats> {
        val stats = BotPersonality.entries.associateWith { Stats() }
        val players = seats.mapIndexed { i, p -> Player(i, "${p.name.lowercase()}-$i", p) }
        val config = TableConfig(
            startingChips = startingChips, turnTimerSeconds = 30, maxPlayers = seats.size,
            blindEscalationOrbits = 0, startingBigBlind = bigBlind,
        )
        val table = PokerTable.new(
            id = 1, firstPlayer = players.first(), bots = players.drop(1), config = config,
            persistence = MemoryBasedPokerTablePersistence.json(),
        )
        val totalChips = startingChips * seats.size

        repeat(hands) {
            table.newPokerRound()
            while (true) {
                val round = table.currentState.roundState ?: break
                if (!round.pokerRoundStage.isBettingRound()) break
                val bot = round.playerOrdering.bettingPlayer()
                val view = BotView.from(round, bot)
                val command = table.botDecisionForCurrentPlayer(rng)!!.command
                val stealSpot = view.isPreflop && view.position == Position.LATE && view.unopened &&
                    view.actions.none { it.stage == PokerRoundStage.BET_BLINDS && it.type == HandActionType.CALL }
                if (stealSpot) {
                    val s = stats.getValue(bot.botPersonality!!)
                    s.stealOpportunities++
                    if (command.type == CommandType.RAISE || command.type == CommandType.ALL_IN) s.steals++
                }
                table.processPlayerCommand(command)
            }

            val round = table.currentState.roundState!!
            round.players.forEach { player ->
                val s = stats.getValue(player.botPersonality!!)
                val mine = round.actions.filter { it.playerId == player.id }
                val preflop = mine.filter { it.stage == PokerRoundStage.BET_BLINDS }
                val postflop = mine.filter { it.stage != PokerRoundStage.BET_BLINDS }
                s.hands++
                if (preflop.any { it.type == HandActionType.CALL || it.raised }) s.vpip++
                if (preflop.any { it.raised }) s.pfr++
                s.postflopAggressive += postflop.count { it.raised }
                s.postflopCalls += postflop.count { it.type == HandActionType.CALL }
            }

            check(table.currentState.players.sumOf { it.chips } == totalChips) { "Chips were created or lost" }
            // Re-stack before clearing, so nobody is eliminated.
            table.currentState.players.forEach {
                it.removeChips(it.chips)
                it.addChips(startingChips)
            }
            table.clearRoundState()
        }
        return stats
    }
}
