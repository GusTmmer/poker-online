package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Deck

data class PokerRoundState(
    val deck: Deck,
    val pots: MutableList<Pot>,
    val blinds: Blinds,
    val players: List<Player>,
    val playerOrdering: PlayerOrdering,
    val pokerRoundStage: PokerRoundStage,
    val bettingRoundState: BettingRoundState?,
    val communityCards: MutableList<Card>,
) {

    companion object {
        fun forNewRound(
            deck: Deck,
            blinds: Blinds,
            players: List<Player>,
            playerOrdering: PlayerOrdering
        ): PokerRoundState = PokerRoundState(
            deck = deck,
            pots = mutableListOf(Pot.mainPot(blinds)),
            blinds = blinds,
            players = players,
            playerOrdering = playerOrdering,
            pokerRoundStage = PokerRoundStage.INIT,
            bettingRoundState = null,
            communityCards = mutableListOf(),
        )
    }

    fun toNextBettingStage(): PokerRoundState {
        assert(pokerRoundStage.isBettingRound() && !pokerRoundStage.isLastBettingRound())

        return copy(
            pokerRoundStage = pokerRoundStage.next(),
            playerOrdering = playerOrdering.forNextBettingRound(),
            bettingRoundState = BettingRoundState.forNewBettingRound(Pot.bettingPot())
        )
    }

    fun toBets(): PokerRoundState = copy(
        pokerRoundStage = PokerRoundStage.BET_BLINDS,
        bettingRoundState = BettingRoundState.forNewBettingRound(pots.last())
    )

    fun toShowdown(): PokerRoundState = copy(pokerRoundStage = PokerRoundStage.SHOWDOWN)
}
