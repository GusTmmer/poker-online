package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.hand.FastHandEvaluator

/**
 * Strength ranking of the 169 starting-hand classes ("AA", "AKs", "AKo", …), strongest first, by
 * heads-up all-in equity against a random hand. Regenerate with `PreflopChartGenerator` (test sources).
 *
 * [percentile] is the share of all 1326 two-card combos that rank strictly above a hand: AA is 0.0, and
 * "top 20% of hands" means `percentile < 0.20`. It is how both a bot's own starting-hand choices and its
 * model of an opponent's range are expressed.
 */
object PreflopChart {

    /** Strongest first. */
    val ORDER: List<String> = """
        AA KK QQ JJ TT 99 88 AKs AQs 77 AJs AKo ATs
        AQo KQs AJo 66 A9s ATo KJs KTs A8s KQo A7s A9o KJo
        QJs 55 A5s K9s A8o A6s KTo QTs A4s A7o A3s K8s QJo
        K9o Q9s A6o A5o JTs A2s QTo K7s 44 A4o K6s K8o Q8s
        J9s A3o K5s JTo Q9o K7o A2o K4s Q7s K3s T9s K6o J8s
        33 Q6s K5o Q8o K2s J9o K4o Q5s J7s T8s Q7o Q4s T9o
        J8o K3o Q6o Q3s 98s T7s K2o Q2s J6s 22 J7o J5s Q5o
        T8o 97s Q4o J4s T6s Q3o J3s 87s 98o T7o J6o Q2o 96s
        T5s J2s J5o 97o 86s T4s T6o J4o T3s 95s 76s J3o 87o
        T2s 96o 85s T5o J2o 94s T4o 86o 75s 93s 65s 84s 95o
        T3o 92s 76o T2o 74s 85o 54s 64s 83s 94o 75o 93o 82s
        73s 65o 84o 53s 63s 92o 43s 74o 54o 64o 72s 52s 62s
        83o 82o 42s 73o 53o 63o 32s 43o 72o 52o 62o 42o 32o
    """.trim().split(Regex("\\s+"))

    private const val RANKS = "23456789TJQKA"

    private val percentileByClass: Map<String, Double> = run {
        var combosAbove = 0
        ORDER.associateWith { hand ->
            (combosAbove / 1326.0).also { combosAbove += combos(hand) }
        }
    }

    /** Indexed by `code(a) * 52 + code(b)`. */
    private val percentileByCodes: DoubleArray = DoubleArray(52 * 52).also { table ->
        for (a in 0 until 52) for (b in 0 until 52) {
            if (a != b) table[a * 52 + b] = percentileByClass.getValue(classOf(a, b))
        }
    }

    fun percentile(pocket: List<Card>): Double =
        percentile(FastHandEvaluator.code(pocket[0]), FastHandEvaluator.code(pocket[1]))

    internal fun percentile(codeA: Int, codeB: Int): Double = percentileByCodes[codeA * 52 + codeB]

    fun classOf(pocket: List<Card>): String =
        classOf(FastHandEvaluator.code(pocket[0]), FastHandEvaluator.code(pocket[1]))

    internal fun classOf(codeA: Int, codeB: Int): String {
        val hi = maxOf(codeA shr 2, codeB shr 2)
        val lo = minOf(codeA shr 2, codeB shr 2)
        return when {
            hi == lo -> "${RANKS[hi]}${RANKS[lo]}"
            (codeA and 3) == (codeB and 3) -> "${RANKS[hi]}${RANKS[lo]}s"
            else -> "${RANKS[hi]}${RANKS[lo]}o"
        }
    }

    /** Every class name, in no particular order (for generating [ORDER]). */
    internal fun allClasses(): List<String> = buildList {
        for (hi in 12 downTo 0) for (lo in hi downTo 0) {
            if (hi == lo) add("${RANKS[hi]}${RANKS[lo]}") else {
                add("${RANKS[hi]}${RANKS[lo]}s")
                add("${RANKS[hi]}${RANKS[lo]}o")
            }
        }
    }

    private fun combos(hand: String) = when {
        hand.length == 2 -> 6
        hand.endsWith("s") -> 4
        else -> 12
    }
}
