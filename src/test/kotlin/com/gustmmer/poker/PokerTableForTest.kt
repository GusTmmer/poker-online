package com.gustmmer.poker

import com.gustmmer.poker.round.PokerRound
import org.mockito.Mockito.mock

class PokerTableForTest(
    players: List<Player>,
    blinds: Blinds,
    ruleSet: RuleSet,
) : PokerTable(players.toMutableList(), blinds, ruleSet) {

    override fun createNewPokerRound(): PokerRound {
        return mock()
    }
}