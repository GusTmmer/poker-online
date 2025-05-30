package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.persistence.Wireable
import kotlinx.serialization.Serializable

@Serializable
data class WireablePokerRoundState(
    val deck: Deck,
    val pots: List<WireablePot>,
    val blinds: Blinds,
    val players: List<Int>,
    val playerOrdering: WireablePlayerOrdering,
    val pokerRoundStage: PokerRoundStage,
    val bettingRoundState: WireableBettingRoundState?,
    val communityCards: List<Card>,
)

data class PokerRoundState(
    val deck: Deck,
    val pots: MutableList<Pot>,
    val blinds: Blinds,
    val players: List<Player>,
    val playerOrdering: PlayerOrdering,
    val pokerRoundStage: PokerRoundStage,
    val bettingRoundState: BettingRoundState?,
    val communityCards: MutableList<Card>,
) : Wireable<WireablePokerRoundState> {

    override fun toWire() = WireablePokerRoundState(
        deck,
        pots.map { it.toWire() },
        blinds,
        players.map { it.id },
        playerOrdering.toWire(),
        pokerRoundStage,
        bettingRoundState?.toWire(),
        communityCards,
    )

    companion object {
        fun restore(state: WireablePokerRoundState, playerMap: Map<Int, Player>): PokerRoundState {
            val players = state.players.map { playerMap.getValue(it) }
            return PokerRoundState(
                deck = state.deck,
                pots = state.pots.map { Pot.restore(it, playerMap) }.toMutableList(),
                blinds = state.blinds,
                players = players,
                playerOrdering = PlayerOrdering.restore(state.playerOrdering, players),
                pokerRoundStage = state.pokerRoundStage,
                bettingRoundState = state.bettingRoundState?.let { BettingRoundState.restore(it, playerMap) },
                communityCards = state.communityCards.toMutableList(),
            )
        }

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
