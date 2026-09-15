package com.gustmmer.poker.server.websocket

import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.hand.ShowdownOdds
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import com.gustmmer.poker.hand.rankings.HandRanking
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.potBreakdown
import com.gustmmer.poker.server.bus.Subscription
import com.gustmmer.poker.server.bus.TableUpdateBus
import io.ktor.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class VoteSummaryView(
    val sessionId: String,
    val resolutionType: String,
    val targetPlayerId: Int? = null,
    val yesCount: Int,
    val noCount: Int,
    val requiredVotes: Int,
)

@Serializable
data class GameStateUpdate(
    val type: String,
    val tableId: Int,
    val players: List<PlayerView>,
    val communityCards: List<String>,
    val potTotal: Int,
    val nextPlayerIdToAct: Int?,
    val roundStage: String?,
    val blinds: BlindInfo,
    val gameStatus: String,
    val readyPlayerIds: List<Int> = emptyList(),
    val activeVotes: List<VoteSummaryView> = emptyList(),
    val message: String? = null,
    /** Epoch-millisecond timestamp when the current player's turn timer expires. Null when no timer is running. */
    val turnTimerEndsAt: Long? = null,
    /** The recipient's own betting limits while a betting round is live and they are in the hand; else null. */
    val myBettingOptions: BettingOptionsView? = null,
    /**
     * Only on a showdown reached before the river: each contender's chance of winning for every board the
     * players will watch being run out (see [ShowdownOdds.forRunout]). Empty otherwise.
     */
    val runoutOdds: List<RunoutOddsView> = emptyList(),
    /** Main pot first, then side pots; amounts sum to [potTotal]. Winners and reason only at a contested showdown. */
    val pots: List<PotView> = emptyList(),
)

@Serializable
data class PotView(
    val amount: Int,
    val contenderIds: List<Int>,
    val winnerIds: List<Int> = emptyList(),
    /** What decided the pot when the hand names don't: "Queen kicker", "Split pot". */
    val reason: String? = null,
)

@Serializable
data class RunoutOddsView(val boardCards: Int, val equities: List<PlayerEquityView>)

/** [equity] is a probability, 0..1. */
@Serializable
data class PlayerEquityView(val playerId: Int, val equity: Double)

/** Chip limits for the recipient's next action. Raise amounts are increments on top of [amountToCall]. */
@Serializable
data class BettingOptionsView(
    val amountToCall: Int,
    val minRaiseBy: Int,
    val maxRaiseBy: Int,
    val canRaise: Boolean,
)

@Serializable
data class BestHandView(val name: String, val cards: List<String>)

@Serializable
data class PlayerView(
    val id: Int,
    val name: String,
    val chips: Int,
    val status: String,
    val isActive: Boolean,
    val currentBet: Int,
    val pocketCards: List<String>?,
    val isDealer: Boolean = false,
    val isSmallBlind: Boolean = false,
    val isBigBlind: Boolean = false,
    val bestHand: BestHandView? = null,
    /** AGGRESSIVE / BALANCED / DEFENSIVE for computer players; null for humans. */
    val botPersonality: String? = null,
)

private fun HandRanking.toDisplayName(): String = when (this) {
    HandRanking.HIGH_CARD -> "High Card"
    HandRanking.ONE_PAIR -> "One Pair"
    HandRanking.TWO_PAIR -> "Two Pair"
    HandRanking.THREE_OF_A_KIND -> "Three of a Kind"
    HandRanking.STRAIGHT -> "Straight"
    HandRanking.FLUSH -> "Flush"
    HandRanking.FULL_HOUSE -> "Full House"
    HandRanking.FOUR_OF_A_KIND -> "Four of a Kind"
    HandRanking.STRAIGHT_FLUSH -> "Straight Flush"
}

@Serializable
data class BlindInfo(val big: Int, val small: Int)

data class PlayerConnection(val tableId: Int, val playerId: Int, val session: WebSocketSession)

class TableConnectionManager(private val bus: TableUpdateBus) {
    private val connections = ConcurrentHashMap<Int, ConcurrentHashMap<Int, WebSocketSession>>()

    // One bus subscription per table this instance holds sockets for. The handler fans a committed
    // change out to our local sockets — so a change committed on ANY instance reaches every player,
    // wherever their socket landed. Opened on the first local socket, cancelled on the last.
    private val subscriptions = ConcurrentHashMap<Int, Subscription>()

    /**
     * Registers [session] for the player, returning any previous session it displaced so the caller
     * can close it. Returns null when there was no prior session (or it was the same instance).
     */
    fun addConnection(tableId: Int, playerId: Int, session: WebSocketSession): WebSocketSession? {
        val tableConnections = connections.getOrPut(tableId) { ConcurrentHashMap() }
        subscriptions.computeIfAbsent(tableId) { id ->
            bus.subscribe(id) { state -> broadcastGameState(state) }
        }
        return tableConnections.put(playerId, session).takeIf { it !== session }
    }

    fun removeConnection(tableId: Int, playerId: Int, session: WebSocketSession) {
        val tableConnections = connections[tableId] ?: return
        tableConnections.remove(playerId, session)
        if (tableConnections.isEmpty()) {
            connections.remove(tableId, tableConnections)
            subscriptions.remove(tableId)?.cancel()
        }
    }

    fun getOnlinePlayerCount(tableId: Int): Int {
        return connections[tableId]?.size ?: 0
    }

    fun isPlayerConnected(tableId: Int, playerId: Int): Boolean {
        return connections[tableId]?.containsKey(playerId) == true
    }

    suspend fun broadcastGameState(state: PokerTableState) {
        val tableConnections = connections[state.id] ?: return
        // Votes are part of the table state now, so they ride along automatically — no separate source.
        val voteViews = state.activeVotes.map {
            VoteSummaryView(it.id, it.resolutionType, it.targetPlayerId, it.yesVoters.size, it.noVoters.size, it.requiredVotes)
        }

        // The same for every recipient (a contender's cards are public at showdown), so computed once.
        val runoutOdds = state.roundState?.let(ShowdownOdds::forRunout).orEmpty().map { street ->
            RunoutOddsView(street.boardCards, street.equities.map { (id, equity) -> PlayerEquityView(id, equity) })
        }

        val pots = state.roundState?.potBreakdown().orEmpty().map {
            PotView(it.amount, it.contenderIds, it.winnerIds, it.reason)
        }

        for ((playerId, session) in tableConnections) {
            val update = buildGameStateUpdate(state, playerId, voteViews, runoutOdds, pots)
            val json = Json.encodeToString(update)
            val sent = runCatching { session.send(Frame.Text(json)) }.isSuccess
            if (!sent) {
                // Session is dead but its finally-block hasn't cleaned up yet — remove it now so
                // future broadcasts don't silently drop for this player.
                tableConnections.remove(playerId, session)
            }
        }
    }

    suspend fun broadcastMessage(tableId: Int, type: String, message: String) {
        val tableConnections = connections[tableId] ?: return

        @Serializable
        data class SimpleMessage(val type: String, val message: String)

        val json = Json.encodeToString(SimpleMessage(type, message))
        for ((_, session) in tableConnections) {
            runCatching { session.send(Frame.Text(json)) }
        }
    }

    private fun buildGameStateUpdate(
        state: PokerTableState,
        forPlayerId: Int,
        activeVotes: List<VoteSummaryView> = emptyList(),
        runoutOdds: List<RunoutOddsView> = emptyList(),
        pots: List<PotView> = emptyList(),
    ): GameStateUpdate {
        val roundState = state.roundState
        val isShowdown = roundState?.pokerRoundStage == PokerRoundStage.SHOWDOWN
        val dealerId = roundState?.playerOrdering?.dealer()?.id
        val smallBlindId = roundState?.playerOrdering?.smallBlindPlayer()?.id
        val bigBlindId = roundState?.playerOrdering?.bigBlindPlayer()?.id

        val activePlayers = state.players.count { it.isActive() }
        val isActualShowdown = isShowdown && activePlayers > 1

        val players = state.players.map { player ->
            val showCards = player.id == forPlayerId || (isShowdown && player.isActive())
            val communityCards = roundState?.communityCards ?: emptyList()
            val bestHand = if (
                isActualShowdown && player.isActive() && player.pocketCards.isNotEmpty() &&
                communityCards.size + player.pocketCards.size >= 5
            ) {
                val hand = TexasHoldEmHandEvaluator.getMatchingPokerHand(communityCards, player.pocketCards)
                BestHandView(hand.ranking.toDisplayName(), hand.cards.map { it.toString() })
            } else null
            PlayerView(
                id = player.id,
                name = player.name,
                chips = player.chips,
                status = player.status.name,
                isActive = player.isActive(),
                currentBet = roundState?.bettingRoundState?.pot?.playerBet(player) ?: 0,
                pocketCards = if (roundState != null && showCards && player.pocketCards.isNotEmpty()) {
                    player.pocketCards.map { it.toString() }
                } else null,
                isDealer = player.id == dealerId,
                isSmallBlind = player.id == smallBlindId,
                isBigBlind = player.id == bigBlindId,
                bestHand = bestHand,
                botPersonality = player.botPersonality?.name,
            )
        }

        val nextPlayerIdToAct = roundState?.let {
            if (it.pokerRoundStage.isBettingRound()) it.playerOrdering.bettingPlayer().id else null
        }

        // A computer player's turn has no clock to show: it acts within seconds, on its own schedule.
        val botToAct = roundState?.takeIf { it.pokerRoundStage.isBettingRound() }?.playerOrdering?.bettingPlayer()?.isBot == true
        val turnTimerEndsAt = state.turnTimerStartedAt
            ?.takeUnless { botToAct }
            ?.let { it + state.config.turnTimerSeconds * 1000L }

        val bettingState = roundState?.bettingRoundState
        val me = roundState?.players?.find { it.id == forPlayerId }
        val myBettingOptions = if (
            bettingState != null && me != null && me.isActive() && roundState.pokerRoundStage.isBettingRound()
        ) {
            bettingState.optionsFor(me, roundState.players, roundState.blinds)
                .let { BettingOptionsView(it.amountToCall, it.minRaiseBy, it.maxRaiseBy, it.canRaise) }
        } else null

        return GameStateUpdate(
            type = "game_state",
            tableId = state.id,
            players = players,
            communityCards = roundState?.communityCards?.map { it.toString() } ?: emptyList(),
            potTotal = roundState?.pots?.sumOf { it.totalBets() } ?: 0,
            nextPlayerIdToAct = nextPlayerIdToAct,
            roundStage = roundState?.pokerRoundStage?.name,
            blinds = BlindInfo(state.blinds.big, state.blinds.small),
            gameStatus = state.gameStatus.name,
            readyPlayerIds = state.readyPlayers.toList(),
            activeVotes = activeVotes,
            turnTimerEndsAt = turnTimerEndsAt,
            myBettingOptions = myBettingOptions,
            runoutOdds = runoutOdds,
            pots = pots,
        )
    }
}
