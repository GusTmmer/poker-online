package com.gustmmer.poker.round

import com.gustmmer.poker.Player

class PlayerOrdering private constructor(
    private val players: List<Player>,
    private val dealerPos: Int,
    private var bettingPos: Int,
) {
    companion object {
        fun forNewTable(players: List<Player>): PlayerOrdering {
            val dealerPos = 0
            return PlayerOrdering(players, dealerPos, underTheGunPos(dealerPos, players.size))
        }

        private fun forNewHand(players: List<Player>, newDealerPos: Int): PlayerOrdering {
            return PlayerOrdering(players, newDealerPos, underTheGunPos(newDealerPos, players.size))
        }

        private fun forNewBettingRound(players: List<Player>, dealerPos: Int): PlayerOrdering {
            return PlayerOrdering(players, dealerPos, smallBlindPos(dealerPos, players.size))
        }

        /** First non-blind position */
        private fun underTheGunPos(dealerPos: Int, playerCount: Int): Int = (dealerPos + 3) % playerCount

        private fun smallBlindPos(dealerPos: Int, playerCount: Int): Int = (dealerPos + 1) % playerCount
    }

    fun moveToNextBettingPos() {
        bettingPos = (bettingPos + 1) % players.size
    }

    fun bettingPlayer(): Player {
        return players[bettingPos]
    }

    fun forSameHand(players: List<Player>): PlayerOrdering {
        return forNewHand(players, dealerPos % players.size)
    }

    fun forNextHand(players: List<Player>): PlayerOrdering {
        return forNewHand(players, (dealerPos + 1) % players.size)
    }

    fun forNextBettingRound(): PlayerOrdering {
        return forNewBettingRound(players, dealerPos)
    }

    fun dealer() = players[dealerPos]

    fun bigBlindPlayer() = players[bigBlindPos()]

    fun smallBlindPlayer() = players[smallBlindPos()]

    private fun smallBlindPos(): Int = (dealerPos + 1) % players.size

    private fun bigBlindPos(): Int = (dealerPos + 2) % players.size
}