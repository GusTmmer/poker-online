package com.gustmmer.poker.round

import com.gustmmer.poker.Player
import com.gustmmer.poker.persistence.Wireable
import kotlinx.serialization.Serializable


@Serializable
data class WireablePlayerOrdering(
    val dealerPos: Int,
    val bettingPos: Int,
)

class PlayerOrdering private constructor(
    private val players: List<Player>,
    private val dealerPos: Int,
    private var bettingPos: Int,
) : Wireable<WireablePlayerOrdering> {
    companion object {
        fun restore(playerOrdering: WireablePlayerOrdering, players: List<Player>): PlayerOrdering {
            return PlayerOrdering(
                players = players,
                dealerPos = playerOrdering.dealerPos,
                bettingPos = playerOrdering.bettingPos,
            )
        }

        fun forNewTable(players: List<Player>): PlayerOrdering {
            val dealerPos = 0
            return PlayerOrdering(players, dealerPos, underTheGunPos(dealerPos, players.size))
        }

        private fun forNewHand(players: List<Player>, newDealerPos: Int): PlayerOrdering {
            return PlayerOrdering(players, newDealerPos, underTheGunPos(newDealerPos, players.size))
        }

        /**
         * Post-flop action starts left of the button: the small blind, or heads-up the big blind (the
         * button is the small blind there and acts last after the flop). The coordinator then skips
         * forward to the first player who still has to act.
         */
        private fun forNewBettingRound(players: List<Player>, dealerPos: Int): PlayerOrdering {
            return PlayerOrdering(players, dealerPos, (dealerPos + 1) % players.size)
        }

        /** First to act pre-flop: left of the big blind, or heads-up the button (who posts the small blind). */
        private fun underTheGunPos(dealerPos: Int, playerCount: Int): Int =
            if (playerCount == 2) dealerPos else (dealerPos + 3) % playerCount

        /** Heads-up the button posts the small blind; otherwise the seat to its left does. */
        private fun smallBlindPos(dealerPos: Int, playerCount: Int): Int =
            if (playerCount == 2) dealerPos else (dealerPos + 1) % playerCount

        private fun bigBlindPos(dealerPos: Int, playerCount: Int): Int =
            if (playerCount == 2) (dealerPos + 1) % playerCount else (dealerPos + 2) % playerCount
    }

    override fun toWire() = WireablePlayerOrdering(
        dealerPos,
        bettingPos
    )

    fun moveToNextBettingPos() {
        bettingPos = (bettingPos + 1) % players.size
    }

    /**
     * Moves the betting position clockwise to the first player matching [predicate], starting with the
     * current seat when [includeCurrent], else the next one. Visits each seat at most once, so it can
     * never spin; returns false (position unchanged) when no seat matches.
     */
    fun moveToFirstMatching(includeCurrent: Boolean, predicate: (Player) -> Boolean): Boolean {
        val start = if (includeCurrent) 0 else 1
        for (offset in start until start + players.size) {
            val pos = (bettingPos + offset) % players.size
            if (predicate(players[pos])) {
                bettingPos = pos
                return true
            }
        }
        return false
    }

    fun bettingPlayer(): Player {
        return players[bettingPos]
    }

    fun forCurrentHand(players: List<Player>): PlayerOrdering {
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

    private fun smallBlindPos(): Int = smallBlindPos(dealerPos, players.size)

    private fun bigBlindPos(): Int = bigBlindPos(dealerPos, players.size)
}
