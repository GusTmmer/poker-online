package com.gustmmer.poker

import com.gustmmer.poker.persistence.Wireable
import com.gustmmer.poker.round.PlayerOrdering
import com.gustmmer.poker.round.PokerRoundState
import com.gustmmer.poker.round.WireablePlayerOrdering
import com.gustmmer.poker.round.WireablePokerRoundState
import kotlinx.serialization.Serializable

@Serializable
data class WireablePokerTableState(
    val id: Int,
    val players: List<Player>,
    val playerOrdering: WireablePlayerOrdering,
    val blinds: Blinds,
    val roundState: WireablePokerRoundState?,
    val config: TableConfig,
    val isPaused: Boolean = false,
    val turnTimeRemainingMs: Long? = null,
    val version: Long = 0,
    val roundsSinceLastEscalation: Int = 0,
    val initialPlayerCount: Int = 0,
)

data class PokerTableState(
    val id: Int,
    val players: MutableList<Player>,
    val playerOrdering: PlayerOrdering,
    val blinds: Blinds,
    val roundState: PokerRoundState?,
    val config: TableConfig,
    val isPaused: Boolean = false,
    val turnTimeRemainingMs: Long? = null,
    val version: Long = 0,
    val roundsSinceLastEscalation: Int = 0,
    val initialPlayerCount: Int = 0,
) : Wireable<WireablePokerTableState> {

    companion object {
        fun restore(state: WireablePokerTableState): PokerTableState {
            val playerMap = state.players.associateBy { it.id }

            return PokerTableState(
                id = state.id,
                players = state.players.toMutableList(),
                playerOrdering = PlayerOrdering.restore(state.playerOrdering, state.players),
                blinds = state.blinds,
                roundState = state.roundState?.let { PokerRoundState.restore(it, playerMap) },
                config = state.config,
                isPaused = state.isPaused,
                turnTimeRemainingMs = state.turnTimeRemainingMs,
                version = state.version,
                roundsSinceLastEscalation = state.roundsSinceLastEscalation,
                initialPlayerCount = state.initialPlayerCount,
            )
        }
    }

    override fun toWire() = WireablePokerTableState(
        id = id,
        players = players,
        playerOrdering = playerOrdering.toWire(),
        blinds = blinds,
        roundState = roundState?.toWire(),
        config = config,
        isPaused = isPaused,
        turnTimeRemainingMs = turnTimeRemainingMs,
        version = version,
        roundsSinceLastEscalation = roundsSinceLastEscalation,
        initialPlayerCount = initialPlayerCount,
    )
}
