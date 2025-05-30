package com.gustmmer.poker

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.CardListSerializer
import kotlinx.serialization.Serializable

enum class PlayerStatus {
    ONLINE,
    OFFLINE,
}

enum class RoundStatus {
    FOLDED,
    ACTIVE,
}

@Serializable
class Player(val id: Int) {

    var status: PlayerStatus = PlayerStatus.ONLINE
        private set

    private var roundStatus = RoundStatus.ACTIVE
        private set

    @Serializable(with = CardListSerializer::class)
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

fun Collection<Player>.onlyOneIsActive(): Boolean = count(Player::isActive) == 1

fun Collection<Player>.canMoreThanOneBet(): Boolean = count(Player::canBet) > 1
