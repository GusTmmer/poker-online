package com.gustmmer.poker.bot

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.hand.FastHandEvaluator
import kotlin.random.Random

/**
 * Monte Carlo win probability of a hand against opponents whose hands are unknown. Only the bot's own
 * cards and the face-up board go in; every opponent hand and every undealt board card is sampled from
 * the cards the bot cannot see.
 *
 * Opponent ranges: each opponent gets a [PreflopChart] percentile *ceiling* (1.0 = any two cards,
 * 0.2 = only the top 20% of starting hands), and is dealt a hand from under it by rejection sampling.
 *
 * Deterministic: the sampler is seeded from its inputs, so the same spot always yields the same number.
 */
object EquityCalculator {

    const val DEFAULT_ITERATIONS = 600

    /** Opponents beyond this barely move the estimate; capping keeps multi-way spots fast. */
    const val MAX_OPPONENTS = 5

    private const val REJECTION_TRIES = 40

    fun equity(
        pocket: List<Card>,
        board: List<Card>,
        opponentCeilings: List<Double>,
        iterations: Int = DEFAULT_ITERATIONS,
    ): Double {
        require(pocket.size == 2) { "Expected 2 pocket cards, got ${pocket.size}" }
        require(board.size <= 5) { "A board has at most 5 cards" }
        val ceilings = opponentCeilings.sorted().take(MAX_OPPONENTS)
        if (ceilings.isEmpty()) return 1.0

        val known = (pocket + board).map(FastHandEvaluator::code)
        val unseen = (0 until 52).filterNot { it in known }.toIntArray()
        val rng = Random(seedOf(known, ceilings, iterations))

        val boardNeeded = 5 - board.size
        val mine = IntArray(7)
        val theirs = IntArray(7)
        val opponentCards = IntArray(ceilings.size * 2)
        var total = 0.0

        repeat(iterations) {
            // Cards dealt this iteration are swapped to the front of `unseen`; `next` is the first free slot.
            var next = 0
            for (i in ceilings.indices) {
                next = dealOpponent(unseen, next, ceilings[i], rng)
                opponentCards[i * 2] = unseen[next - 2]
                opponentCards[i * 2 + 1] = unseen[next - 1]
            }
            for (k in 0 until boardNeeded) {
                swap(unseen, next, next + rng.nextInt(unseen.size - next))
                next++
            }

            known.forEachIndexed { i, c -> mine[i] = c }
            for (k in 0 until boardNeeded) mine[known.size + k] = unseen[next - boardNeeded + k]
            val myScore = FastHandEvaluator.score(mine, 7)

            var best = true
            var ties = 0
            for (i in ceilings.indices) {
                theirs[0] = opponentCards[i * 2]
                theirs[1] = opponentCards[i * 2 + 1]
                for (b in 2 until 7) theirs[b] = mine[b]
                val score = FastHandEvaluator.score(theirs, 7)
                if (score > myScore) {
                    best = false
                    break
                }
                if (score == myScore) ties++
            }
            if (best) total += 1.0 / (ties + 1)
        }
        return total / iterations
    }

    /**
     * Picks two free cards for an opponent whose hand must rank within [ceiling], moving them to
     * `unseen[next]`, `unseen[next + 1]`. Falls back to the last draw when the range is exhausted by the
     * cards already out, so a narrow range never stalls the loop.
     */
    private fun dealOpponent(unseen: IntArray, next: Int, ceiling: Double, rng: Random): Int {
        val free = unseen.size - next
        var a = 0
        var b = 0
        for (attempt in 0 until REJECTION_TRIES) {
            a = next + rng.nextInt(free)
            b = next + rng.nextInt(free - 1).let { if (it >= a - next) it + 1 else it }
            if (ceiling >= 1.0 || PreflopChart.percentile(unseen[a], unseen[b]) < ceiling) break
        }
        swap(unseen, next, a)
        // `a`'s card now sits at `next`; if `b` pointed at `next`, that card moved to `a`.
        swap(unseen, next + 1, if (b == next) a else b)
        return next + 2
    }

    private fun swap(arr: IntArray, i: Int, j: Int) {
        val t = arr[i]
        arr[i] = arr[j]
        arr[j] = t
    }

    private fun seedOf(known: List<Int>, ceilings: List<Double>, iterations: Int): Long {
        var h = 1125899906842597L
        known.sorted().forEach { h = 31 * h + it }
        ceilings.forEach { h = 31 * h + (it * 1000).toLong() }
        return 31 * h + iterations
    }
}
