package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.onlyOneIsActive
import kotlin.math.min

data class BettingRoundState(
    val pot: Pot,
    val lastRaiser: Player?,
    val isComplete: Boolean,
) {
    companion object {
        fun forNewBettingRound(pot: Pot) = BettingRoundState(
            pot = pot,
            lastRaiser = null,
            isComplete = false,
        )
    }
}

class BettingRoundCoordinator(
    state: BettingRoundState,
    private val players: List<Player>,
    private val playerOrdering: PlayerOrdering,
    private val blinds: Blinds,
) {
    private val pot = state.pot
    private var lastRaiser = state.lastRaiser ?: playerOrdering.bettingPlayer()

    fun processPlayerCommand(playerCommand: PlayerCommand): BettingRoundState {
        val bettingPlayers = players.filter(Player::canBet)

        moveToFirstPlayerWhoCanBet()

        handlePlayerCommand(playerCommand)

        if (bettingPlayers.onlyOneIsActive()) {
            return completeBettingRound()
        }

        moveToNextPlayerWhoCanBet()

        return if (playerOrdering.bettingPlayer() == lastRaiser) {
            completeBettingRound()
        } else {
            pendingBettingRound()
        }
    }

    private fun pendingBettingRound(): BettingRoundState {
        return BettingRoundState(pot, lastRaiser, isComplete = false)
    }

    private fun completeBettingRound(): BettingRoundState {
        pot.reBalanceBets()
        return BettingRoundState(pot, lastRaiser, isComplete = true)
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

    private fun handlePlayerCommand(playerCommand: PlayerCommand) {
        println("Processing ${playerCommand.type} from ${playerCommand.player}")

        validateCommandIsFromExpectedPlayer(playerCommand)

        when (playerCommand.type) {
            CommandType.FOLD -> playerCommand.player.fold()
            CommandType.CALL -> handleCall(playerCommand.player)
            CommandType.RAISE -> handleRaise((playerCommand as Raise).value, playerCommand.player)
            CommandType.ALL_IN -> handleAllIn(playerCommand.player)
        }
    }

    private fun validateCommandIsFromExpectedPlayer(playerCommand: PlayerCommand) {
        if (playerOrdering.bettingPlayer() != playerCommand.player) {
            throw IllegalStateException("Trying to handle command from unexpected player. It's not the player's turn")
        }
    }

    private fun handleCall(player: Player) {
        pot.addPlayerChips(player, min(player.chips, pot.chipsToMatchCurrentBet(player)))
    }

    private fun handleRaise(raise: Int, player: Player) {
        if (raise < blinds.big
            || raise < pot.currentBet()
            || player.chips < pot.chipsToMatchCurrentBet(player) + raise
        ) {
            throw IllegalArgumentException("Raise of $raise is invalid")
        }

        lastRaiser = player
        pot.addPlayerChips(player, pot.chipsToMatchCurrentBet(player) + raise)
    }

    private fun handleAllIn(player: Player) {
        if (player.chips > pot.chipsToMatchCurrentBet(player)) {
            lastRaiser = player
        }

        pot.addPlayerChips(player, player.chips)
    }
}

