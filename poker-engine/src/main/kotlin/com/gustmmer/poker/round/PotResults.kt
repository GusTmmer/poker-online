package com.gustmmer.poker.round

import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Rank
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import com.gustmmer.poker.hand.rankings.HandRanking
import com.gustmmer.poker.hand.rankings.PokerHand

/**
 * One pot as the table sees it. [winnerIds] and [reason] are filled only once the hand is shown down;
 * [reason] says what decided a pot when the hand names alone don't ("Queen kicker", "Split pot").
 */
data class PotBreakdown(
    val amount: Int,
    val contenderIds: List<Int>,
    val winnerIds: List<Int> = emptyList(),
    val reason: String? = null,
)

/**
 * The winners of [pot]: the best of [rankedHands] (sorted best first) among players with chips in it,
 * ties included. The single rule both the payout ([PokerRound]) and the table view use.
 */
fun potWinners(pot: Pot, rankedHands: List<Pair<Player, PokerHand>>): Set<Player> {
    val handsInPot = rankedHands.filter { (player, _) -> pot.hasPlayerBet(player) }
    val (_, winningHand) = handsInPot.first()
    return handsInPot
        .takeWhile { (_, hand) -> hand.compareTo(winningHand) == 0 }
        .map { (player, _) -> player }
        .toSet()
}

/** Every active player's best hand, best first. */
fun PokerRoundState.rankedHands(): List<Pair<Player, PokerHand>> = players
    .filter { it.isActive() && it.pocketCards.size == 2 }
    .map { it to TexasHoldEmHandEvaluator.getMatchingPokerHand(communityCards, it.pocketCards) }
    .sortedByDescending { (_, hand) -> hand }

/**
 * The round's pots, main pot first. Winners are resolved only at a contested showdown — earlier the
 * board isn't complete, and a hand won by folds has already paid out and cleared its pots.
 */
fun PokerRoundState.potBreakdown(): List<PotBreakdown> {
    val contested = pokerRoundStage == PokerRoundStage.SHOWDOWN &&
        communityCards.size == 5 && players.count(Player::isActive) > 1
    val ranked = if (contested) rankedHands() else emptyList()

    return pots.filter { it.totalBets() > 0 }.map { pot ->
        val contenders = pot.contributors().filter(Player::isActive).sortedBy { it.id }
        if (!contested || contenders.isEmpty()) {
            return@map PotBreakdown(pot.totalBets(), contenders.map { it.id })
        }

        val winners = potWinners(pot, ranked)
        val reason = when {
            winners.size > 1 -> "Split pot"
            else -> {
                val inPot = ranked.filter { (player, _) -> pot.hasPlayerBet(player) }
                val runnerUp = inPot.firstOrNull { (player, _) -> player !in winners }?.second
                runnerUp?.let { decidingReason(inPot.first().second, it) }
            }
        }
        PotBreakdown(pot.totalBets(), contenders.map { it.id }, winners.map { it.id }.sorted(), reason)
    }
}

/**
 * What separates [winner] from [runnerUp] when both make the same kind of hand — "Queen kicker",
 * "Ace high", "higher Jacks". Null when the hand names already differ, or the hands tie.
 */
fun decidingReason(winner: PokerHand, runnerUp: PokerHand): String? {
    if (winner.ranking != runnerUp.ranking) return null
    val index = winner.cards.indices.firstOrNull { winner.cards[it].rank != runnerUp.cards.getOrNull(it)?.rank }
        ?: return null
    val rank = winner.cards[index].rank

    return when (winner.ranking) {
        HandRanking.HIGH_CARD, HandRanking.FLUSH ->
            if (index == 0) "${rank.displayName()} high" else "${rank.displayName()} kicker"
        HandRanking.STRAIGHT, HandRanking.STRAIGHT_FLUSH -> "${rank.displayName()} high"
        HandRanking.ONE_PAIR, HandRanking.TWO_PAIR, HandRanking.THREE_OF_A_KIND,
        HandRanking.FOUR_OF_A_KIND, HandRanking.FULL_HOUSE ->
            if (index >= madeCardCount(winner.ranking)) "${rank.displayName()} kicker"
            else "higher ${rank.pluralName()}"
    }
}

/** Cards that form the hand itself; the rest of the five are kickers. */
private fun madeCardCount(ranking: HandRanking): Int = when (ranking) {
    HandRanking.ONE_PAIR -> 2
    HandRanking.THREE_OF_A_KIND -> 3
    HandRanking.TWO_PAIR, HandRanking.FOUR_OF_A_KIND -> 4
    else -> 5
}

private fun Rank.displayName(): String = name.lowercase().replaceFirstChar(Char::uppercase)

private fun Rank.pluralName(): String = if (this == Rank.SIX) "Sixes" else "${displayName()}s"
