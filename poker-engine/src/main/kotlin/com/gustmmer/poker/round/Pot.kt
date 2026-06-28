package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.persistence.Wireable
import kotlinx.serialization.Serializable
import kotlin.math.max

@Serializable
data class WireablePot(
    val id: Int,
    val betsByPlayer: Map<Int, Int>,
    val minBet: Int,
)

class Pot(
    private val id: Int,
    private val betsByPlayer: MutableMap<Player, Int>,
    private val minBet: Int = 0,
) : Wireable<WireablePot> {

    companion object {
        fun restore(pot: WireablePot, playerMap: Map<Int, Player>): Pot = Pot(
            id = pot.id,
            betsByPlayer = pot.betsByPlayer.mapKeys { playerMap.getValue(it.key) }.toMutableMap().withDefault { 0 },
            minBet = pot.minBet
        )

        fun bettingPot(): Pot = Pot(-1, mutableMapOf<Player, Int>().withDefault { 0 })
        fun mainPot(blinds: Blinds): Pot = Pot(0, mutableMapOf<Player, Int>().withDefault { 0 }, blinds.big)
        private fun sidePot(id: Int, bets: MutableMap<Player, Int>) = Pot(id, bets)
    }

    override fun toWire(): WireablePot = WireablePot(
        id = id,
        betsByPlayer = betsByPlayer.mapKeys { it.key.id },
        minBet = minBet
    )

    fun chipsToMatchCurrentBet(player: Player): Int {
        return (currentBet() - betsByPlayer.getValue(player)).also {
            check(it >= 0) { "Player ${player.id} has bet more than the current bet" }
        }
    }

    fun addPlayerChips(player: Player, chips: Int) {
        require(player.chips >= chips) { "Player ${player.id} cannot bet $chips with only ${player.chips} chips" }

        player.removeChips(chips)

        betsByPlayer[player] = betsByPlayer.getValue(player) + chips
    }

    fun hasPlayerBet(player: Player): Boolean {
        return player in betsByPlayer
    }

    fun playerBet(player: Player): Int {
        return betsByPlayer.getOrDefault(player, 0)
    }

    fun activePlayerCount(): Int {
        return betsByPlayer.keys.count(Player::isActive)
    }

    fun resolveWinnerWithSingleActivePlayer() {
        check(activePlayerCount() == 1) { "Expected exactly one active player, found ${activePlayerCount()}" }
        val winner = betsByPlayer.keys.first(Player::isActive)
        distributeToWinners(setOf(winner))
    }

    fun distributeToWinners(winningPlayers: Set<Player>) {
        val total = totalBets()
        val perWinner = total / winningPlayers.size
        val remainder = total % winningPlayers.size
        winningPlayers.forEachIndexed { i, player ->
            player.addChips(perWinner + if (i == 0) remainder else 0)
        }
    }

    fun mergeBetsFromPot(pot: Pot) {
        if (pot.id == this.id) {
            pot.betsByPlayer.forEach { (p, bet) -> betsByPlayer[p] = bet }
            return
        }

        pot.betsByPlayer.forEach { (p, bet) -> betsByPlayer[p] = betsByPlayer.getValue(p) + bet }
    }

    fun totalBets(): Int = betsByPlayer.values.sum()

    fun currentBet(): Int = max(minBet, betsByPlayer.maxOfOrNull { it.value } ?: 0)

    fun reBalanceBets() {
        if (betsByPlayer.isEmpty()) {
            return
        }

        val sortedBets = betsByPlayer.entries.sortedByDescending { it.value }

        if (sortedBets.size == 1) {
            sortedBets.single().let { (player, bet) -> giveChipsBackToPlayer(player, bet) }
            return
        }

        val largestBet = sortedBets[0]
        val secondLargestBet = sortedBets[1]

        if (largestBet.value != secondLargestBet.value) {
            giveChipsBackToPlayer(largestBet.key, largestBet.value - secondLargestBet.value)
        }
    }

    fun sidePotOrNull(): Pot? {
        val potWithOnlyActivePlayers = betsByPlayer.filterKeys(Player::isActive)

        val minBet = potWithOnlyActivePlayers.minOf { (_, bet) -> bet }
        val potBet = betsByPlayer.maxOfOrNull { it.value } ?: 0

        if (potBet == minBet) {
            return null
        }

        val sidePot = potWithOnlyActivePlayers
            .mapValues { (_, bet) -> bet - minBet }
            .filterValues { it > 0 }
            .let { sidePot(id + 1, it.toMutableMap()) }

        betsByPlayer.entries.forEach { it.setValue(minBet) }

        return sidePot
    }

    override fun toString(): String {
        return getName() + betsByPlayer.toString()
    }

    private fun giveChipsBackToPlayer(player: Player, chips: Int) {
        check(betsByPlayer.getValue(player) >= chips) { "Cannot refund $chips to player ${player.id}; bet is ${betsByPlayer.getValue(player)}" }

        player.addChips(chips)

        betsByPlayer[player] = betsByPlayer.getValue(player) - chips
    }

    private fun getName(): String = when (id) {
        0 -> "Main Pot: "
        else -> "Side Pot #$id: "
    }
}
