package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player

data class PokerRoundSpec (
    val players: List<Player>,
    var playerOrdering: PlayerOrdering,
    val blinds: Blinds,
) {
    fun withNewPlayerOrdering(block: PlayerOrdering.() -> PlayerOrdering): PokerRoundSpec {
        playerOrdering = playerOrdering.run(block)
        return this
    }
}