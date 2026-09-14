package com.gustmmer.poker.bot

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.DeckForTests
import com.gustmmer.poker.deck.toCards
import com.gustmmer.poker.round.*
import kotlin.random.Random

/**
 * A live hand dealt from a fixed deck, for putting a bot in an exact spot. Seat 0 is the button; with 3+
 * players seat 1 posts the small blind and seat 2 the big blind.
 *
 * @param pockets each seat's pocket cards, e.g. `"AS KD"`
 * @param board the five board cards in dealing order; any left unspecified are filled from the rest of the deck
 */
class BotSpot(
    pockets: List<String>,
    board: String = "",
    chips: Int = 10_000,
    blinds: Blinds = Blinds(big = 100, small = 50),
    personality: BotPersonality = BotPersonality.BALANCED,
) {
    val players = pockets.indices.map { Player(it, "P$it", personality).apply { addChips(chips) } }

    var round: PokerRoundState
        private set

    init {
        val dealt = pockets.flatMap { it.toCards() } + (if (board.isBlank()) emptyList() else board.toCards())
        val filler = Card.cards.filterNot { it in dealt }.shuffled(Random(7))
        val boardCards = dealt.drop(pockets.size * 2)
        val deck = DeckForTests(dealt.take(pockets.size * 2) + boardCards + filler.take(5 - boardCards.size))
        round = PokerRound(PokerRoundState.forNewRound(deck, blinds, players, PlayerOrdering.forNewTable(players))).start()
    }

    val current: Player get() = round.playerOrdering.bettingPlayer()

    fun view(): BotView = BotView.from(round, current)

    fun act(command: (Int) -> PlayerCommand): BotSpot {
        round = PokerRound(round).processCommand(command(current.id))
        return this
    }

    fun fold() = act(::Fold)
    fun call() = act(::Call)
    fun raise(by: Int) = act { Raise(it, by) }

    /** Checks/calls until the street ends. */
    fun callAround(): BotSpot {
        val street = round.pokerRoundStage
        while (round.pokerRoundStage == street) call()
        return this
    }

    /** Checks/calls seats until it's [seat]'s turn. */
    fun callUntil(seat: Int): BotSpot {
        while (current.id != seat) call()
        return this
    }

    fun foldUntil(seat: Int): BotSpot {
        while (current.id != seat) fold()
        return this
    }
}

/** A [Random] whose single deviation roll always (0.0) or never (0.99) succeeds. */
class FixedRoll(private val value: Double) : Random() {
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextDouble(): Double = value

    companion object {
        val NEVER = FixedRoll(0.99)
        val ALWAYS = FixedRoll(0.0)
    }
}
