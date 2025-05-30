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
    val roundState: WireablePokerRoundState?
)

data class PokerTableState(
    val id: Int,
    val players: MutableList<Player>,
    val playerOrdering: PlayerOrdering,
    val blinds: Blinds,
    val roundState: PokerRoundState?,
//    val ruleSet: RuleSet,
) : Wireable<WireablePokerTableState> {

    companion object {
        fun restore(state: WireablePokerTableState): PokerTableState {
            val playerMap = state.players.associateBy { it.id }

            return PokerTableState(
                id = state.id,
                players = state.players.toMutableList(),
                playerOrdering = PlayerOrdering.restore(state.playerOrdering, state.players),
                blinds = state.blinds,
                roundState = state.roundState?.let { PokerRoundState.restore(it, playerMap) }
            )
        }
    }

    override fun toWire() = WireablePokerTableState(
        id = id,
        players = players,
        playerOrdering = playerOrdering.toWire(),
        blinds = blinds,
        roundState = roundState?.toWire()
    )
}
