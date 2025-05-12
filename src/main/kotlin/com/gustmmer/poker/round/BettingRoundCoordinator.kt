package com.gustmmer.poker.round

import com.gustmmer.poker.Player
import com.gustmmer.poker.PlayerIO
import com.gustmmer.poker.hasSingleActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

class BettingRoundCoordinator(
    roundSpec: PokerRoundSpec,
    private val pot: Pot,
    private val playerIO: PlayerIO,
) {
    private val blinds = roundSpec.blinds
    private val players = roundSpec.players
    private val playerOrdering = roundSpec.playerOrdering

    private var lastRaiser: Player = playerOrdering.bettingPlayer()

    suspend fun handleBetting() = coroutineScope {
        val bettingPlayers = players.filter(Player::canBet)

        moveToFirstPlayerWhoCanBet()

        do {
            if (!handlePlayerCommand()) {
                continue
            }

            if (bettingPlayers.hasSingleActive()) {
                break
            }

            moveToNextPlayerWhoCanBet()
        } while (playerOrdering.bettingPlayer() != lastRaiser)

        pot.reBalanceBets()
    }

    private fun moveToFirstPlayerWhoCanBet() {
        while (!playerOrdering.bettingPlayer().canBet()) {
            playerOrdering.moveToNextBettingPos()
        }
    }

    private fun moveToNextPlayerWhoCanBet() {
        do {
            playerOrdering.moveToNextBettingPos()
        } while (playerOrdering.bettingPlayer().let { !it.canBet() && it != lastRaiser })
    }

    private suspend fun handlePlayerCommand(): Boolean {
        val command = getCommandFromBettingPlayer()
        val player = command.player

        return when (command.type) {
            CommandType.FOLD -> player.fold().let { true }
            CommandType.CALL -> handleCall(player)
            CommandType.RAISE -> handleRaise((command as Raise).value, player)
            CommandType.ALL_IN -> handleAllIn(player)
        }.also {
            println("Processed command ${command.type} for ${command.player}. Pot: $pot")
        }
    }

    private suspend fun getCommandFromBettingPlayer(): PlayerCommand {
        val eachWaitTimeMs = 10_000
        val maxWaitTimeMs = 60_000

        var totalWaitedTimeMs = 0

        while (true) {
            var command: PlayerCommand? = null
            withTimeoutOrNull(10_000) {
                command = playerIO.playerCommands.receive()
            }

            if (command == null) {
                totalWaitedTimeMs += eachWaitTimeMs
                if (totalWaitedTimeMs < maxWaitTimeMs) {
                    continue
                } else {
                    command = Fold(playerOrdering.bettingPlayer())
                }
            }

            if (command!!.player == playerOrdering.bettingPlayer()) {
                return command!!
            }
        }
    }

    private fun handleCall(player: Player): Boolean {
        pot.addPlayerChips(player, min(player.chips, pot.chipsToMatchCurrentBet(player)))
        return true
    }

    private fun handleRaise(raise: Int, player: Player): Boolean {
        if (raise < blinds.big
            || raise < pot.currentBet()
            || player.chips < pot.chipsToMatchCurrentBet(player) + raise
        ) {
            // Invalid raise
            /** TODO: Communicate this to the player * */
            return false
        }

        lastRaiser = player
        pot.addPlayerChips(player, pot.chipsToMatchCurrentBet(player) + raise)
        return true
    }

    private fun handleAllIn(player: Player): Boolean {
        if (player.chips > pot.chipsToMatchCurrentBet(player)) {
            lastRaiser = player
        }

        pot.addPlayerChips(player, player.chips)
        return true
    }
}
