package com.gustmmer.poker.hand

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.PokerRoundState
import kotlin.random.Random

/** Each player's chance of winning with [boardCards] community cards showing (ties split; sums to 1). */
data class StreetOdds(val boardCards: Int, val equities: Map<Int, Double>)

/**
 * Win probabilities of tabled hands — the percentages shown beside the players' cards while an all-in
 * board is run out. Only what the table can see goes in: the contenders' pocket cards and the board so
 * far. Folded hands stay unknown and the actual undealt deck is never consulted.
 */
object ShowdownOdds {

    /** Above this many possible board completions, sample instead of enumerating (pre-flop: 1.7M boards). */
    private const val MAX_EXACT_BOARDS = 100_000
    private const val SAMPLES = 50_000

    private val STREET_ENDS = listOf(3, 4, 5)

    /**
     * Odds for every board size the players will watch during the runout of a hand that reached showdown
     * with the board incomplete when the betting ended — starting with the board as it stood then, and
     * one entry per street after, through the river. Empty for any other hand.
     */
    fun forRunout(round: PokerRoundState): List<StreetOdds> {
        if (round.pokerRoundStage != PokerRoundStage.SHOWDOWN || round.communityCards.size != 5) return emptyList()
        val pockets = round.players
            .filter { it.isActive() && it.pocketCards.size == 2 }
            .associate { it.id to it.pocketCards }
        if (pockets.size < 2) return emptyList()

        // The street the betting ended on is the street of the last public action.
        val shownWhenBettingEnded = when (round.actions.lastOrNull()?.stage) {
            PokerRoundStage.BET_BLINDS -> 0
            PokerRoundStage.BET_FLOP -> 3
            PokerRoundStage.BET_TURN -> 4
            else -> return emptyList() // river showdown (nothing to run out) or no action log
        }

        return (listOf(shownWhenBettingEnded) + STREET_ENDS.filter { it > shownWhenBettingEnded })
            .map { shown -> StreetOdds(shown, compute(pockets, round.communityCards.take(shown))) }
    }

    /** Each player's share of every possible completion of [board] (ties split), keyed like [pockets]. */
    fun compute(pockets: Map<Int, List<Card>>, board: List<Card>): Map<Int, Double> {
        require(pockets.size >= 2) { "Odds need at least two hands" }
        require(pockets.values.all { it.size == 2 }) { "Every hand needs two pocket cards" }
        require(board.size <= 5) { "A board has at most 5 cards" }

        val ids = pockets.keys.toList()
        val known = (pockets.values.flatten() + board).map(FastHandEvaluator::code)
        require(known.toSet().size == known.size) { "A card appears twice" }
        val unseen = (0 until 52).filterNot { it in known }.toIntArray()
        val missing = 5 - board.size

        val hands = Array(ids.size) { i ->
            IntArray(7).also { hand ->
                pockets.getValue(ids[i]).forEachIndexed { c, card -> hand[c] = FastHandEvaluator.code(card) }
                board.forEachIndexed { b, card -> hand[2 + b] = FastHandEvaluator.code(card) }
            }
        }
        val shares = DoubleArray(ids.size)
        val scores = IntArray(ids.size)

        fun settle(completion: IntArray) {
            var best = Int.MIN_VALUE
            for (i in hands.indices) {
                for (k in 0 until missing) hands[i][2 + board.size + k] = completion[k]
                scores[i] = FastHandEvaluator.score(hands[i], 7)
                if (scores[i] > best) best = scores[i]
            }
            val winners = scores.count { it == best }
            for (i in hands.indices) if (scores[i] == best) shares[i] += 1.0 / winners
        }

        val completion = IntArray(missing)
        val boards = if (combinations(unseen.size, missing) <= MAX_EXACT_BOARDS) {
            var count = 0L
            forEachCombination(unseen, missing, completion) { settle(completion); count++ }
            count
        } else {
            val rng = Random(known.fold(17L) { h, c -> 31 * h + c })
            val pool = unseen.copyOf()
            repeat(SAMPLES) {
                for (k in 0 until missing) {
                    val j = k + rng.nextInt(pool.size - k)
                    pool[k] = pool[j].also { pool[j] = pool[k] }
                    completion[k] = pool[k]
                }
                settle(completion)
            }
            SAMPLES.toLong()
        }

        return ids.indices.associate { ids[it] to shares[it] / boards }
    }

    private fun combinations(n: Int, k: Int): Long =
        (0 until k).fold(1L) { acc, i -> acc * (n - i) / (i + 1) }

    /** Calls [action] with [out] holding each [k]-card combination of [cards] in turn. */
    private fun forEachCombination(cards: IntArray, k: Int, out: IntArray, action: () -> Unit) {
        fun pick(from: Int, depth: Int) {
            if (depth == k) return action()
            for (i in from..cards.size - (k - depth)) {
                out[depth] = cards[i]
                pick(i + 1, depth + 1)
            }
        }
        pick(0, 0)
    }
}
