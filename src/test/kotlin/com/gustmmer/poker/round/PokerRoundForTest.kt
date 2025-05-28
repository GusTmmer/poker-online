package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.deck.DeckForTests
import com.gustmmer.poker.deck.toCards
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class PokerRoundForTest(private var state: PokerRoundState) {

    private val players
        get() = state.players

    private val reachedPokerRoundStages = mutableSetOf<PokerRoundStage>()

    fun fold(playerId: Int) = executePlayerCommand(Fold(players[playerId]))
    fun call(playerId: Int) = executePlayerCommand(Call(players[playerId]))
    fun raise(playerId: Int, raise: Int) = executePlayerCommand(Raise(players[playerId], raise))
    fun allIn(playerId: Int) = executePlayerCommand(AllIn(players[playerId]))

    fun executeTestBlock(block: PokerRoundForTest.() -> Unit) {
        state = PokerRound(state).start()

        this.block()
    }

    private fun executePlayerCommand(command: PlayerCommand) {
        state = PokerRound(state).processCommand(command)
        reachedPokerRoundStages.add(state.pokerRoundStage)
    }

    fun assertPlayerChips(playerId: Int, chips: Int) = assertEquals(chips, players[playerId].chips)

    fun assertWentThroughBettingRound(pokerRoundStage: PokerRoundStage) {
        assertTrue(
            pokerRoundStage in reachedPokerRoundStages,
            "Poker round did not reach '$pokerRoundStage'"
        )
    }

    companion object {
        fun setup(blinds: Blinds, communityCards: String): PokerRoundSetup = PokerRoundSetup()
            .withBlinds(blinds)
            .withCommunityCards(communityCards.toCards())

        fun withShuffledDeck(
            blinds: Blinds,
            playerCount: Int,
            playerChips: Int,
            block: PokerRoundForTest.() -> Unit,
        ) {
            PokerRoundSetup()
                .withBlinds(blinds)
                .withShuffledDeck()
                .apply { repeat(playerCount) { withPlayer(playerChips) } }
                .execute(block)
        }
    }
}

class PokerRoundSetup {
    private var deck: Deck? = null
    private var lastPlayerId = 0
    private val playersInRound = mutableListOf<Player>()
    private var communityCards: List<Card> = emptyList()
    private lateinit var blinds: Blinds

    fun withPlayer(
        chips: Int,
        hand: String? = null,
    ): PokerRoundSetup {
        Player(lastPlayerId++).apply {
            addChips(chips)
            hand?.let { setPocketCards(it.toCards()) }
        }.let {
            playersInRound.add(it)
        }
        return this
    }

    fun withCommunityCards(cards: List<Card>): PokerRoundSetup {
        communityCards = cards
        return this
    }

    fun withShuffledDeck(): PokerRoundSetup {
        deck = Deck.shuffled()
        return this
    }

    fun withBlinds(blinds: Blinds): PokerRoundSetup {
        this.blinds = blinds
        return this
    }

    fun execute(block: PokerRoundForTest.() -> Unit) {
        build().run {
            val totalChipsInRoundStart = playersInRound.sumOf { it.chips }

            executeTestBlock(block)

            assertEquals(
                playersInRound.sumOf { it.chips },
                totalChipsInRoundStart,
                "Player chips should be the same before and after the round"
            )
        }
    }

    private fun build(): PokerRoundForTest {
        val deck = this.deck
            ?: DeckForTests(playersInRound.flatMap { it.pocketCards } + communityCards)

        val playerOrdering = PlayerOrdering.forNewTable(playersInRound)
        val state = PokerRoundState.forNewRound(deck, blinds, playersInRound, playerOrdering)

        return PokerRoundForTest(state)
    }
}
