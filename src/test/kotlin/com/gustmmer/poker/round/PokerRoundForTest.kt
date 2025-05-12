package com.gustmmer.poker.round

import com.gustmmer.poker.Blinds
import com.gustmmer.poker.Player
import com.gustmmer.poker.PlayerIO
import com.gustmmer.poker.SystemCommand
import com.gustmmer.poker.deck.Card
import com.gustmmer.poker.deck.Deck
import com.gustmmer.poker.deck.DeckForTests
import com.gustmmer.poker.deck.toCards
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*

class PokerRoundForTest(
    pokerRound: PokerRound,
    private val players: List<Player>,
    private val playerCommandChannel: SendChannel<PlayerCommand>,
) : PokerRound(pokerRound) {
    enum class BettingRound(val seq: Int) {
        BLINDS(1),
        FLOP(2),
        TURN(3),
        RIVER(4)
    }

    private var currentBettingRound = 0

    var afterBlindBets: (PokerRoundForTest.() -> Unit)? = null
    var afterFlopBets: (PokerRoundForTest.() -> Unit)? = null
    var afterTurnBets: (PokerRoundForTest.() -> Unit)? = null
    var afterRiverBets: (PokerRoundForTest.() -> Unit)? = null
    var afterPokerRound: (PokerRoundForTest.() -> Unit)? = null

    private lateinit var executedTriggers: MutableSet<PokerRoundForTest.() -> Unit>

    override suspend fun execute() {
        executedTriggers = mutableSetOf()

        try {
            withTimeout(10) { super.execute() }
        } catch (e: TimeoutCancellationException) {
            fail("Missing player action(s) in betting round(s)")
        }

        afterPokerRound?.let { run(it) }

        assertExecutedAllDefinedTriggers()
    }

    override suspend fun betting(bettingPot: Pot, bettingRoundSpec: PokerRoundSpec) {
        super.betting(bettingPot, bettingRoundSpec)

        onBettingRoundEnded()

        currentBettingRound++
    }

    suspend fun fold(playerId: Int) = playerCommandChannel.send(Fold(players[playerId]))
    suspend fun call(playerId: Int) = playerCommandChannel.send(Call(players[playerId]))
    suspend fun raise(playerId: Int, raise: Int) = playerCommandChannel.send(Raise(players[playerId], raise))
    suspend fun allIn(playerId: Int) = playerCommandChannel.send(AllIn(players[playerId]))

    fun assertPlayerChips(playerId: Int, chips: Int) = assertEquals(chips, players[playerId].chips)

    fun assertWentThroughBettingRound(bettingRound: BettingRound) {
        assertTrue(currentBettingRound >= bettingRound.seq, "Poker round did not reach '$bettingRound'")
    }

    private fun onBettingRoundEnded() {
        val afterRoundBlock = when (currentBettingRound) {
            0 -> afterBlindBets
            1 -> afterFlopBets
            2 -> afterTurnBets
            3 -> afterRiverBets
            else -> null
        }

        afterRoundBlock?.let { executeTrigger(it) }
    }

    private fun executeTrigger(block: (PokerRoundForTest.() -> Unit)) {
        run(block)
        executedTriggers.add(block)
    }

    private fun assertExecutedAllDefinedTriggers() {
        afterBlindBets?.let { assert(it in executedTriggers) { "'afterBlindBets' was defined but not reached" } }
        afterFlopBets?.let { assert(it in executedTriggers) { "'afterFlopBets' was defined but not reached" } }
        afterTurnBets?.let { assert(it in executedTriggers) { "'afterTurnBets' was defined but not reached" } }
        afterRiverBets?.let { assert(it in executedTriggers) { "'afterRiverBets' was defined but not reached" } }
    }

    companion object {
        fun setup(blinds: Blinds, communityCards: String): PokerRoundSetup = PokerRoundSetup()
            .withBlinds(blinds)
            .withCommunityCards(communityCards.toCards())

        suspend fun withShuffledDeck(
            blinds: Blinds,
            playerCount: Int,
            playerChips: Int,
            block: suspend PokerRoundForTest.() -> Unit,
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
    private var lastPlayerId = 1
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

    suspend fun execute(block: suspend PokerRoundForTest.() -> Unit) {
        build().run {
            val totalChipsInRoundStart = playersInRound.sumOf { it.chips }

            block()
            execute()

            assertEquals(playersInRound.sumOf { it.chips }, totalChipsInRoundStart)
        }
    }

    private fun build(): PokerRoundForTest {
        val deck = this.deck
            ?: DeckForTests(playersInRound.flatMap { it.pocketCards } + communityCards)

        val systemCommandChannel = Channel<SystemCommand>()
        val playerCommandChannel = Channel<PlayerCommand>(UNLIMITED)
        val events = Channel<Any>()

        val playerIO = PlayerIO(playerCommandChannel, systemCommandChannel, events)

        val playerOrdering = PlayerOrdering.forNewTable(playersInRound)
        val roundSpec = PokerRoundSpec(playersInRound, playerOrdering, blinds)
        val pokerRound = PokerRound(deck, roundSpec, playerIO)

        return PokerRoundForTest(pokerRound, playersInRound, playerCommandChannel)
    }
}
