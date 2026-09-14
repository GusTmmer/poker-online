package com.gustmmer.poker

import com.gustmmer.poker.bot.BotPersonality
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
    /** Null for humans. */
    val botPersonality: BotPersonality? = null,
)

fun WireablePlayer.restore(): Player {
    val player = Player(id, name, botPersonality)
    player.addChips(chips)
    // Cards first: setPocketCards marks the player ACTIVE, so the statuses below must be applied after it —
    // otherwise an eliminated player holding a stale hand would come back as still in the hand.
    if (pocketCards.isNotEmpty()) {
        player.setPocketCards(pocketCards)
    }
    when (status) {
        PlayerStatus.OFFLINE -> player.setAsOffline()
        PlayerStatus.IDLE -> player.setAsIdle()
        PlayerStatus.ELIMINATED -> player.setAsEliminated()
        PlayerStatus.ONLINE -> {}
    }
    if (roundStatus == RoundStatus.FOLDED) {
        player.fold()
    }
    return player
}

class Player(
    val id: Int,
    val name: String = "Player $id",
    /** Set for computer players, which the server plays via [PokerTable.playBotTurn]; null for humans. */
    val botPersonality: BotPersonality? = null,
) {

    val isBot: Boolean
        get() = botPersonality != null

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
        pocketCards = emptyList()
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
        botPersonality = botPersonality,
    )
}

fun Collection<Player>.active() = filter(Player::isActive)

fun Collection<Player>.onlyOneIsActive(): Boolean = count(Player::isActive) == 1

fun Collection<Player>.canMoreThanOneBet(): Boolean = count(Player::canBet) > 1

fun Collection<Player>.participating() = filter(Player::isParticipating)
