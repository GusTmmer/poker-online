package com.gustmmer.poker

import com.gustmmer.poker.deck.Card

enum class PlayerStatus {
    ONLINE,
    OFFLINE,
}

enum class RoundStatus {
    FOLDED,
    ACTIVE,
}

class Player(private val id: Int) {

    var status: PlayerStatus = PlayerStatus.ONLINE
        private set

    var roundStatus = RoundStatus.ACTIVE
        private set

    var pocketCards = emptyList<Card>()
        private set

    var chips = 0
        private set

    override fun toString(): String {
        return "[$id]{$roundStatus}:($$chips)"
    }

    fun setPocketCards(cards: List<Card>) {
        pocketCards = cards
        roundStatus = RoundStatus.ACTIVE
    }

    fun setAsOffline() {
        status = PlayerStatus.OFFLINE
        roundStatus = RoundStatus.FOLDED
    }

    fun addChips(chips: Int) {
        this.chips += chips
    }

    fun removeChips(chips: Int) {
        assert(this.chips - chips >= 0)
        this.chips -= chips
    }

    fun isActive() = roundStatus == RoundStatus.ACTIVE

    fun fold() {
        roundStatus = RoundStatus.FOLDED
    }

    fun canBet() = isActive() && chips > 0
}

fun Collection<Player>.active() = filter(Player::isActive)

fun Collection<Player>.hasSingleActive(): Boolean = count(Player::isActive) == 1

fun Collection<Player>.canMoreThanOneBet(): Boolean = count(Player::canBet) > 1
