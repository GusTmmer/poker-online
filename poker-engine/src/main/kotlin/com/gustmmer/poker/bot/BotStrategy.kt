package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.round.AllIn
import com.gustmmer.poker.round.Call
import com.gustmmer.poker.round.Fold
import com.gustmmer.poker.round.HandActionType
import com.gustmmer.poker.round.PlayerCommand
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.Raise
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** A bot's move, how long it "thinks" before making it, and why (for logs and tests). */
data class BotDecision(val command: PlayerCommand, val thinkMs: Long, val reason: String)

/**
 * Decides a computer player's action from a [BotView] and its personality's [BotProfile].
 *
 * The decision is deterministic — the same spot always gets the same play — except for a single
 * [Random] roll that is only made in spots where the personality's [DeviationKind] is credible
 * (a well-placed bluff, or slow-playing a monster), and succeeds at most [BotProfile.deviationRate]
 * (≤ 10%) of the time.
 */
object BotStrategy {

    /** Raising this share of the remaining stack or more commits the bot: it moves all-in instead. */
    private const val COMMIT_FRACTION = 0.4

    fun decide(view: BotView, profile: BotProfile, rng: Random = Random.Default): BotDecision {
        val plan = baseline(view, profile)
        val chosen = if (deviationEligible(view, profile, plan) && rng.nextDouble() < profile.deviationRate) {
            deviate(view, profile, plan)
        } else {
            plan
        }
        val command = toCommand(view, chosen.intent)
        return BotDecision(command, thinkMs(view, plan, command), chosen.reason)
    }

    // ── Intents ─────────────────────────────────────────────────────────────────────────────────────

    private sealed interface Intent
    private data object FoldIntent : Intent
    private data object CheckOrCall : Intent

    /** Raise by [increment] chips on top of the call. */
    private data class RaiseBy(val increment: Int) : Intent
    private data object Shove : Intent

    private data class Plan(
        val intent: Intent,
        val reason: String,
        /** How far the key number was from the decision threshold; small = a hard decision. */
        val margin: Double = 1.0,
        val equity: Double? = null,
        val shortStacked: Boolean = false,
    )

    // ── Baseline (deterministic) ────────────────────────────────────────────────────────────────────

    private fun baseline(view: BotView, profile: BotProfile): Plan = when {
        view.isPreflop && view.stackInBigBlinds <= profile.shortStackBigBlinds -> shortStack(view, profile)
        view.isPreflop -> preflop(view, profile)
        else -> postflop(view, profile)
    }

    private fun shortStack(view: BotView, profile: BotProfile): Plan {
        val pct = PreflopChart.percentile(view.pocketCards)
        // The shorter the stack, the more hands are worth shoving before the blinds eat it.
        val shoveCutoff = (profile.shoveCutoffAtThreshold * profile.shortStackBigBlinds /
            view.stackInBigBlinds.coerceAtLeast(1.0)).coerceAtMost(1.0)
        val cutoff = if (view.raisesThisStreet == 0) shoveCutoff else shoveCutoff * 0.45
        return if (pct < cutoff) {
            Plan(Shove, "short stack shove", margin = cutoff - pct, shortStacked = true)
        } else {
            Plan(FoldIntent, "short stack fold", margin = pct - cutoff, shortStacked = true)
        }
    }

    private fun preflop(view: BotView, profile: BotProfile): Plan {
        val pct = PreflopChart.percentile(view.pocketCards)
        val bb = view.bigBlind

        if (view.unopened) {
            val limpers = view.actions.count { it.stage == PokerRoundStage.BET_BLINDS && it.type == HandActionType.CALL }
            val stealing = view.position == Position.LATE && limpers == 0 && profile.stealCutoff != null
            val playCutoff = profile.openCutoff.at(view.position)
                .let { if (stealing) maxOf(it, profile.stealCutoff!!) else it }
            val raiseCutoff = if (stealing) playCutoff else playCutoff * profile.openRaiseFraction
            val openTo = 3 * bb + limpers * bb
            return when {
                pct < raiseCutoff -> Plan(
                    RaiseBy(openTo - view.streetBet),
                    if (stealing && pct >= profile.openCutoff.at(view.position)) "blind steal" else "open raise",
                    margin = raiseCutoff - pct,
                )
                pct < playCutoff -> Plan(CheckOrCall, "limp", margin = playCutoff - pct)
                else -> Plan(FoldIntent, "not in opening range", margin = pct - playCutoff)
            }
        }

        val bigCommitment = view.toCall > COMMIT_FRACTION * view.chips
        if (view.raisesThisStreet == 1) {
            val defend = when (view.position) {
                Position.BLINDS -> 1.3 // already invested, better odds
                Position.LATE -> 1.15 // position after the flop
                else -> 1.0
            }
            val callCutoff = if (bigCommitment) profile.reRaiseCutoff * 1.5 else profile.callRaiseCutoff * defend
            return when {
                pct < profile.reRaiseCutoff -> Plan(RaiseBy(2 * view.streetBet), "re-raise", margin = profile.reRaiseCutoff - pct)
                pct < callCutoff -> Plan(CheckOrCall, "call a raise", margin = callCutoff - pct)
                else -> Plan(FoldIntent, "fold to a raise", margin = pct - callCutoff)
            }
        }

        // Facing a re-raise or more.
        val fourBetCutoff = profile.reRaiseCutoff * 0.4
        val callCutoff = profile.reRaiseCutoff * if (potOdds(view) < 0.2) 2.0 else 1.0
        return when {
            pct < fourBetCutoff -> Plan(RaiseBy((1.3 * view.streetBet).roundToInt()), "four-bet", margin = fourBetCutoff - pct)
            pct < callCutoff -> Plan(CheckOrCall, "call a re-raise", margin = callCutoff - pct)
            else -> Plan(FoldIntent, "fold to a re-raise", margin = pct - callCutoff)
        }
    }

    private fun postflop(view: BotView, profile: BotProfile): Plan {
        val ceilings = view.opponents.map { RangeModel.ceilingFor(it.playerId, view.actions) }
        val equity = EquityCalculator.equity(view.pocketCards, view.communityCards, ceilings)
        val pot = view.potTotal

        if (view.toCall == 0) {
            val cbet = profile.cbetEquity != null && view.isPreflopAggressor && view.opponents.size == 1 &&
                view.stage == PokerRoundStage.BET_FLOP && equity >= profile.cbetEquity
            return when {
                equity >= profile.valueBetEquity ->
                    Plan(RaiseBy(sized(pot, profile.valueSizing)), "value bet", equity - profile.valueBetEquity, equity)
                cbet -> Plan(RaiseBy(sized(pot, profile.bluffSizing)), "continuation bet", equity - profile.cbetEquity!!, equity)
                else -> Plan(CheckOrCall, "check", profile.valueBetEquity - equity, equity)
            }
        }

        val callThreshold = potOdds(view) + profile.callMargin
        return when {
            equity >= profile.raiseEquity ->
                Plan(RaiseBy(sized(pot + view.toCall, profile.valueSizing)), "raise for value", equity - profile.raiseEquity, equity)
            equity >= callThreshold -> Plan(CheckOrCall, "call with odds", equity - callThreshold, equity)
            else -> Plan(FoldIntent, "fold without odds", callThreshold - equity, equity)
        }
    }

    // ── Deviation (the only randomness) ─────────────────────────────────────────────────────────────

    private fun deviationEligible(view: BotView, profile: BotProfile, plan: Plan): Boolean {
        if (plan.shortStacked) return false
        return when (profile.deviationKind) {
            DeviationKind.BLUFF -> {
                val passive = plan.intent == FoldIntent || plan.intent == CheckOrCall && view.toCall == 0
                val headsUpish = view.opponents.size <= 2
                val credible = when {
                    view.isPreflop -> view.unopened && view.position == Position.LATE
                    // Raising a bet with nothing is burning chips; only with a draw behind it.
                    view.toCall > 0 -> Draws.hasDraw(view.pocketCards, view.communityCards)
                    else -> view.position == Position.LATE || view.isPreflopAggressor ||
                        Draws.hasDraw(view.pocketCards, view.communityCards)
                }
                passive && headsUpish && credible && view.options.canRaise
            }
            DeviationKind.SLOWPLAY -> {
                val aggressive = plan.intent is RaiseBy || plan.intent == Shove
                val monster = if (view.isPreflop) {
                    PreflopChart.percentile(view.pocketCards) < 0.02 && view.raisesThisStreet <= 1
                } else {
                    (plan.equity ?: 0.0) >= 0.80
                }
                aggressive && monster
            }
        }
    }

    private fun deviate(view: BotView, profile: BotProfile, plan: Plan): Plan = when (profile.deviationKind) {
        DeviationKind.BLUFF -> {
            val size = if (view.isPreflop) 3 * view.bigBlind - view.streetBet else sized(view.potTotal + view.toCall, profile.bluffSizing)
            plan.copy(intent = RaiseBy(size), reason = if (Draws.hasDraw(view.pocketCards, view.communityCards)) "semi-bluff" else "bluff")
        }
        DeviationKind.SLOWPLAY -> plan.copy(intent = CheckOrCall, reason = "slow-play")
    }

    // ── Legal command ───────────────────────────────────────────────────────────────────────────────

    private fun toCommand(view: BotView, intent: Intent): PlayerCommand {
        val id = view.playerId
        val options = view.options
        return when (intent) {
            FoldIntent -> if (view.toCall == 0) Call(id) else Fold(id)
            CheckOrCall -> Call(id)
            Shove -> if (options.canRaise || view.toCall >= view.chips) AllIn(id) else Call(id)
            is RaiseBy -> when {
                !options.canRaise -> Call(id)
                // Only an all-in is left as a raise.
                options.maxRaiseBy < options.minRaiseBy -> AllIn(id)
                else -> {
                    val increment = intent.increment.coerceIn(options.minRaiseBy, options.maxRaiseBy)
                    if (increment == options.maxRaiseBy || view.toCall + increment >= COMMIT_FRACTION * view.chips) {
                        AllIn(id)
                    } else {
                        Raise(id, increment)
                    }
                }
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────

    private fun potOdds(view: BotView): Double =
        if (view.toCall == 0) 0.0 else view.toCall.toDouble() / (view.potTotal + view.toCall)

    private fun sized(base: Int, fraction: Double) = (base * fraction).roundToInt().coerceAtLeast(1)

    /**
     * Deterministic "thinking" time: snap decisions are quick, close ones and all-ins take a while — like
     * a person, and it gives observant players a timing tell.
     */
    private fun thinkMs(view: BotView, plan: Plan, command: PlayerCommand): Long = when {
        view.facingAllIn || command is AllIn -> 2600L
        abs(plan.margin) < 0.05 -> 2200L
        command is Fold || (command is Call && view.toCall == 0) -> 700L
        else -> 1300L
    }
}

/** Drawing hands, from the bot's own cards and the visible board only. */
internal object Draws {
    fun hasDraw(pocket: List<Card>, board: List<Card>): Boolean {
        if (board.size !in 3..4) return false
        return flushDraw(pocket, board) || openEndedStraightDraw(pocket, board)
    }

    private fun flushDraw(pocket: List<Card>, board: List<Card>): Boolean {
        val all = pocket + board
        return pocket.map { it.suit }.distinct().any { suit -> all.count { it.suit == suit } == 4 }
    }

    private fun openEndedStraightDraw(pocket: List<Card>, board: List<Card>): Boolean {
        val ranks = (pocket + board).map { it.rank.ordinal }.toSet()
        val pocketRanks = pocket.map { it.rank.ordinal }.toSet()
        // Four in a row with a free rank on both ends (so not A-high or wheel-bound), using a pocket card.
        return (1..8).any { low ->
            val window = low..low + 3
            window.all { it in ranks } && window.any { it in pocketRanks } &&
                (low - 1) !in ranks && (low + 4) !in ranks
        }
    }
}
