package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.persistence.Wireable
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

private val log = LoggerFactory.getLogger(BettingRoundCoordinator::class.java)

@Serializable
data class WireableBettingRoundState(
    val pot: WireablePot,
    val lastRaiser: Int?,
    val isComplete: Boolean,
    /** Null on records written before action tracking existed — restored as "everyone who can bet". */
    val toAct: List<Int>? = null,
    val actedSinceFullRaise: List<Int> = emptyList(),
    /** Null on older records — restored as the big blind. */
    val lastRaiseSize: Int? = null,
)

/**
 * One street of betting.
 *
 * - [toAct]: players who still owe an action on this street. The street is over once nobody does
 *   (or nobody is left to bet against).
 * - [actedSinceFullRaise]: players who have acted since the last *full* raise. An all-in that is
 *   smaller than a full raise puts them back in [toAct] to match it, but they may only call or fold —
 *   the under-raise does not reopen raising for them.
 * - [lastRaiseSize]: the largest bet/raise increment so far on this street (at least the big blind),
 *   i.e. the minimum size of the next raise.
 */
data class BettingRoundState(
    val pot: Pot,
    val lastRaiser: Player?,
    val isComplete: Boolean,
    val toAct: Set<Player>,
    val actedSinceFullRaise: Set<Player>,
    val lastRaiseSize: Int,
) : Wireable<WireableBettingRoundState> {

    companion object {
        fun forNewBettingRound(pot: Pot, players: List<Player>, blinds: Blinds) = BettingRoundState(
            pot = pot,
            lastRaiser = null,
            isComplete = false,
            toAct = players.filter(Player::canBet).toSet(),
            actedSinceFullRaise = emptySet(),
            lastRaiseSize = blinds.big,
        )

        fun restore(
            state: WireableBettingRoundState,
            playerMap: Map<Int, Player>,
            roundPlayers: List<Player>,
            blinds: Blinds,
        ): BettingRoundState {
            return BettingRoundState(
                pot = Pot.restore(state.pot, playerMap),
                lastRaiser = state.lastRaiser?.let { playerMap[it] },
                isComplete = state.isComplete,
                toAct = state.toAct?.mapNotNull { playerMap[it] }?.toSet()
                    ?: roundPlayers.filter(Player::canBet).toSet(),
                actedSinceFullRaise = state.actedSinceFullRaise.mapNotNull { playerMap[it] }.toSet(),
                lastRaiseSize = state.lastRaiseSize ?: blinds.big,
            )
        }
    }

    override fun toWire(): WireableBettingRoundState = WireableBettingRoundState(
        pot = pot.toWire(),
        lastRaiser = lastRaiser?.id,
        isComplete = isComplete,
        toAct = toAct.map { it.id },
        actedSinceFullRaise = actedSinceFullRaise.map { it.id },
        lastRaiseSize = lastRaiseSize,
    )

    /** What [player] may do right now, in chips. The single source of truth for clients. */
    fun optionsFor(player: Player, players: List<Player>, blinds: Blinds): BettingOptions {
        val toCall = pot.chipsToMatchCurrentBet(player)
        val maxRaiseBy = max(0, player.chips - toCall)
        val someoneToRaiseAgainst = players.any { it !== player && it.canBet() }
        return BettingOptions(
            amountToCall = min(toCall, player.chips),
            minRaiseBy = max(blinds.big, lastRaiseSize),
            maxRaiseBy = maxRaiseBy,
            canRaise = player.canBet() && maxRaiseBy > 0 && someoneToRaiseAgainst &&
                player !in actedSinceFullRaise,
        )
    }
}

/**
 * [minRaiseBy]/[maxRaiseBy] are raise increments on top of the call. When [maxRaiseBy] is below
 * [minRaiseBy] the only raise available is an all-in.
 */
data class BettingOptions(
    val amountToCall: Int,
    val minRaiseBy: Int,
    val maxRaiseBy: Int,
    val canRaise: Boolean,
)

class BettingRoundCoordinator(
    state: BettingRoundState,
    private val players: List<Player>,
    private val playerOrdering: PlayerOrdering,
    private val blinds: Blinds,
) {
    private val pot = state.pot
    private var lastRaiser = state.lastRaiser
    private val toAct = state.toAct.toMutableSet()
    private val actedSinceFullRaise = state.actedSinceFullRaise.toMutableSet()
    private var lastRaiseSize = state.lastRaiseSize

    private val minRaiseBy: Int
        get() = max(blinds.big, lastRaiseSize)

    fun processPlayerCommand(playerCommand: PlayerCommand): BettingRoundState {
        log.debug("Processing {} from player {}", playerCommand.type, playerCommand.playerId)

        validateCommandIsFromExpectedPlayer(playerCommand)

        val player = players.first { it.id == playerCommand.playerId }

        when (playerCommand.type) {
            CommandType.FOLD -> player.fold()
            CommandType.CALL -> handleCall(player)
            CommandType.RAISE -> handleRaise((playerCommand as Raise).value, player)
            CommandType.ALL_IN -> handleAllIn(player)
        }

        toAct.remove(player)
        actedSinceFullRaise.add(player)

        return settle(includeCurrentSeat = false)
    }

    /**
     * Folds [player] while it is *not* their turn (they left or were kicked). The current bettor keeps
     * the turn; the street ends if that leaves a single active player.
     */
    fun processOutOfTurnFold(player: Player): BettingRoundState {
        player.fold()
        toAct.remove(player)
        return settle(includeCurrentSeat = true)
    }

    /**
     * Ends the street if no one has to act any more, otherwise moves the turn to the next player who
     * does. Called after every action, and at the start of a street (where the blinds may already have
     * put everyone but one player all-in).
     */
    fun settle(includeCurrentSeat: Boolean = true): BettingRoundState {
        toAct.retainAll { it.canBet() }

        if (isBettingOver()) {
            pot.reBalanceBets()
            return snapshot(isComplete = true)
        }

        playerOrdering.moveToFirstMatching(includeCurrentSeat) { it in toAct }
        return snapshot(isComplete = false)
    }

    private fun isBettingOver(): Boolean {
        if (players.count(Player::isActive) <= 1) return true
        if (toAct.isEmpty()) return true
        // No opponent left who can put in chips: a lone player with chips only acts if they still owe some.
        val bettors = players.filter(Player::canBet)
        return bettors.size <= 1 && bettors.all { pot.chipsToMatchCurrentBet(it) == 0 }
    }

    private fun snapshot(isComplete: Boolean) = BettingRoundState(
        pot = pot,
        lastRaiser = lastRaiser,
        isComplete = isComplete,
        toAct = toAct.toSet(),
        actedSinceFullRaise = actedSinceFullRaise.toSet(),
        lastRaiseSize = lastRaiseSize,
    )

    private fun validateCommandIsFromExpectedPlayer(playerCommand: PlayerCommand) {
        val expected = playerOrdering.bettingPlayer()
        if (expected.id != playerCommand.playerId || expected !in toAct) {
            throw IllegalStateException("Trying to handle command from unexpected player. It's not the player's turn")
        }
    }

    private fun handleCall(player: Player) {
        pot.addPlayerChips(player, min(player.chips, pot.chipsToMatchCurrentBet(player)))
    }

    private fun handleRaise(raise: Int, player: Player) {
        val toCall = pot.chipsToMatchCurrentBet(player)
        require(player !in actedSinceFullRaise) { "Raising is not reopened for player ${player.id}; call or fold" }
        require(raise >= minRaiseBy) { "Raise of $raise is invalid; the minimum raise is $minRaiseBy" }
        require(toCall + raise <= player.chips) { "Raise of $raise is invalid; player ${player.id} cannot cover it" }

        pot.addPlayerChips(player, toCall + raise)
        registerFullRaise(player, raise)
    }

    private fun handleAllIn(player: Player) {
        val increment = player.chips - pot.chipsToMatchCurrentBet(player)
        require(increment <= 0 || player !in actedSinceFullRaise) {
            "Raising is not reopened for player ${player.id}; call or fold"
        }

        pot.addPlayerChips(player, player.chips)

        when {
            increment >= minRaiseBy -> registerFullRaise(player, increment)
            // An under-raise: everyone else must match the extra chips, but may not re-raise because of it.
            increment > 0 -> toAct.addAll(players.filter { it !== player && it.canBet() })
        }
    }

    private fun registerFullRaise(player: Player, raiseSize: Int) {
        lastRaiser = player
        lastRaiseSize = raiseSize
        toAct.clear()
        toAct.addAll(players.filter { it !== player && it.canBet() })
        actedSinceFullRaise.clear()
    }
}
