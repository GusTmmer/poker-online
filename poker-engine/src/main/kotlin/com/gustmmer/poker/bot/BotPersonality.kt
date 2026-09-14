package com.gustmmer.poker.bot

import kotlinx.serialization.Serializable

/**
 * The three computer-player styles, after the archetypes poker literature describes most often.
 * Hand-range numbers are [PreflopChart] percentiles ("top X% of starting hands"); equities are win
 * probabilities from [EquityCalculator]. Tuned with `BotSimulationHarness` towards the VPIP/PFR targets
 * noted on each.
 */
@Serializable
enum class BotPersonality(val profile: BotProfile) {

    /** Loose-aggressive (LAG). Target VPIP ~30–35%, PFR ~25%: steals blinds, c-bets, semi-bluffs. */
    AGGRESSIVE(
        BotProfile(
            openCutoff = PositionCutoffs(early = 0.16, middle = 0.24, late = 0.42, blinds = 0.30),
            openRaiseFraction = 1.0,
            stealCutoff = 0.55,
            callRaiseCutoff = 0.26,
            reRaiseCutoff = 0.09,
            callMargin = 0.0,
            valueBetEquity = 0.55,
            raiseEquity = 0.70,
            cbetEquity = 0.25,
            valueSizing = 0.75,
            bluffSizing = 0.75,
            shortStackBigBlinds = 15.0,
            shoveCutoffAtThreshold = 0.30,
            deviationRate = 0.10,
            deviationKind = DeviationKind.BLUFF,
        )
    ),

    /** Tight-aggressive (TAG), the textbook style. Target VPIP ~18–22%, PFR ~15%. */
    BALANCED(
        BotProfile(
            openCutoff = PositionCutoffs(early = 0.10, middle = 0.15, late = 0.28, blinds = 0.20),
            openRaiseFraction = 1.0,
            stealCutoff = 0.35,
            callRaiseCutoff = 0.15,
            reRaiseCutoff = 0.05,
            callMargin = 0.03,
            valueBetEquity = 0.60,
            raiseEquity = 0.75,
            cbetEquity = 0.40,
            // Same size for value and bluffs: the size gives nothing away.
            valueSizing = 0.66,
            bluffSizing = 0.66,
            shortStackBigBlinds = 10.0,
            shoveCutoffAtThreshold = 0.22,
            deviationRate = 0.06,
            deviationKind = DeviationKind.BLUFF,
        )
    ),

    /** Rock / tight-passive. Target VPIP ~12%, PFR ~5%: premiums only, calls more than raises. */
    DEFENSIVE(
        BotProfile(
            openCutoff = PositionCutoffs(early = 0.08, middle = 0.10, late = 0.15, blinds = 0.12),
            openRaiseFraction = 0.45,
            stealCutoff = null,
            callRaiseCutoff = 0.10,
            reRaiseCutoff = 0.025,
            callMargin = 0.08,
            valueBetEquity = 0.72,
            raiseEquity = 0.88,
            cbetEquity = null,
            // Bets big with strong hands and small otherwise — a readable tell.
            valueSizing = 0.80,
            bluffSizing = 0.40,
            shortStackBigBlinds = 6.0,
            shoveCutoffAtThreshold = 0.12,
            deviationRate = 0.03,
            deviationKind = DeviationKind.SLOWPLAY,
        )
    ),
}

enum class DeviationKind {
    /** Sometimes bets or raises a spot it would check or fold — only where a bluff is credible. */
    BLUFF,

    /** Sometimes just calls or checks a monster it would raise. */
    SLOWPLAY,
}

data class PositionCutoffs(val early: Double, val middle: Double, val late: Double, val blinds: Double) {
    fun at(position: Position) = when (position) {
        Position.EARLY -> early
        Position.MIDDLE -> middle
        Position.LATE -> late
        Position.BLINDS -> blinds
    }
}

data class BotProfile(
    /** Starting hands to play when nobody has raised yet, by position. */
    val openCutoff: PositionCutoffs,
    /** Share of the opening range (its strongest part) that raises rather than limps. */
    val openRaiseFraction: Double,
    /** From late position into an unopened pot, raise this much wider to steal the blinds. Null: never steals. */
    val stealCutoff: Double?,
    /** Hands that call a single raise. */
    val callRaiseCutoff: Double,
    /** Hands that re-raise a raise (and the base for calling or four-betting a re-raise). */
    val reRaiseCutoff: Double,
    /** Post-flop: equity needed on top of the pot odds before calling. */
    val callMargin: Double,
    /** Post-flop: bet for value at or above this equity. */
    val valueBetEquity: Double,
    /** Post-flop: raise a bet at or above this equity. */
    val raiseEquity: Double,
    /** Post-flop: as the pre-flop raiser, heads-up on the flop, continuation-bet from this equity. Null: never. */
    val cbetEquity: Double?,
    /** Bet sizes as a fraction of the pot. */
    val valueSizing: Double,
    val bluffSizing: Double,
    /** At or below this many big blinds the bot plays all-in-or-fold. */
    val shortStackBigBlinds: Double,
    /** All-in-or-fold opening range at [shortStackBigBlinds]; widens as the stack shrinks. */
    val shoveCutoffAtThreshold: Double,
    /** Chance, in an eligible spot only, of the [deviationKind] play. Capped at 10% of decisions. */
    val deviationRate: Double,
    val deviationKind: DeviationKind,
) {
    init {
        require(deviationRate in 0.0..0.10) { "deviationRate must be at most 10%, was $deviationRate" }
    }
}
