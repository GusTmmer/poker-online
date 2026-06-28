package com.gustmmer.poker

import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.CardListSerializer
import kotlinx.serialization.Serializable

enum class PlayerStatus {
    ONLINE,
    OFFLINE,
    IDLE,
    ELIMINATED,
}

enum class RoundStatus {
    FOLDED,
    ACTIVE,
}

@Serializable
data class WireablePlayer(
    val id: Int,
    val name: String,
    val status: PlayerStatus = PlayerStatus.ONLINE,
    val roundStatus: RoundStatus = RoundStatus.ACTIVE,
    @Serializable(with = CardListSerializer::class)
    val pocketCards: List<Card> = emptyList(),
    val chips: Int = 0,
)

fun WireablePlayer.restore(): Player {
    val player = Player(id, name)
    player.addChips(chips)
    when (status) {
        PlayerStatus.OFFLINE -> player.setAsOffline()
        PlayerStatus.IDLE -> player.setAsIdle()
        PlayerStatus.ELIMINATED -> player.setAsEliminated()
        PlayerStatus.ONLINE -> {}
    }
    if (pocketCards.isNotEmpty()) {
        player.setPocketCards(pocketCards)
    }
    // roundStatus after setPocketCards is ACTIVE; fold if the player had folded (but isn't eliminated — setAsEliminated already sets FOLDED)
    if (roundStatus == RoundStatus.FOLDED && status != PlayerStatus.ELIMINATED) {
        player.fold()
    }
    return player
}

class Player(val id: Int, val name: String = "Player $id") {

    var status: PlayerStatus = PlayerStatus.ONLINE
        private set

    private var roundStatus = RoundStatus.ACTIVE
        private set

    var pocketCards = emptyList<Card>()
        private set

    var chips = 0
        private set

    override fun toString(): String {
        return "[$id:$name]{$roundStatus}:($$chips)"
    }

    fun setPocketCards(cards: List<Card>) {
        pocketCards = cards
        roundStatus = RoundStatus.ACTIVE
    }

    fun setAsOffline() {
        status = PlayerStatus.OFFLINE
    }

    fun setAsOnline() {
        status = PlayerStatus.ONLINE
    }

    fun setAsIdle() {
        status = PlayerStatus.IDLE
    }

    fun setAsEliminated() {
        status = PlayerStatus.ELIMINATED
        roundStatus = RoundStatus.FOLDED
    }

    fun addChips(chips: Int) {
        this.chips += chips
    }

    fun removeChips(chips: Int) {
        require(this.chips - chips >= 0) { "Cannot remove $chips chips from $name holding only ${this.chips}" }
        this.chips -= chips
    }

    fun isActive() = roundStatus == RoundStatus.ACTIVE

    fun isEliminated() = status == PlayerStatus.ELIMINATED

    fun isParticipating() = status != PlayerStatus.ELIMINATED

    fun fold() {
        roundStatus = RoundStatus.FOLDED
    }

    fun canBet() = isActive() && chips > 0

    fun toWire() = WireablePlayer(
        id = id,
        name = name,
        status = status,
        roundStatus = roundStatus,
        pocketCards = pocketCards,
        chips = chips,
    )
}

fun Collection<Player>.active() = filter(Player::isActive)

fun Collection<Player>.onlyOneIsActive(): Boolean = count(Player::isActive) == 1

fun Collection<Player>.canMoreThanOneBet(): Boolean = count(Player::canBet) > 1

fun Collection<Player>.participating() = filter(Player::isParticipating)
