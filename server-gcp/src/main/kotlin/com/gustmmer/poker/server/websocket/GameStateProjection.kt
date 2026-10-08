package com.gustmmer.poker.server.websocket

import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.firstHandDealt
import com.gustmmer.poker.hand.ShowdownOdds
import com.gustmmer.poker.hand.TexasHoldEmHandEvaluator
import com.gustmmer.poker.hand.rankings.HandRanking
import com.gustmmer.poker.round.PokerRoundStage
import com.gustmmer.poker.round.potBreakdown
import kotlinx.serialization.Serializable

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
    /**
     * When the countdown to [turnTimerEndsAt] starts: until then the table is still animating what led to the
     * turn, and the clock shows the full turn. Null when no timer is running.
     */
    val turnClockStartsAt: Long? = null,
    /** The recipient's own betting limits while a betting round is live and they are in the hand; else null. */
    val myBettingOptions: BettingOptionsView? = null,
    /**
     * Only on a showdown reached before the river: each contender's chance of winning for every board the
     * players will watch being run out (see [ShowdownOdds.forRunout]). Empty otherwise.
     */
    val runoutOdds: List<RunoutOddsView> = emptyList(),
    /** Main pot first, then side pots; amounts sum to [potTotal]. Winners and reason only at a contested showdown. */
    val pots: List<PotView> = emptyList(),
    /** Who runs the table: starts hands and new games, renames it. Null once no human is left. */
    val ownerId: Int? = null,
    /** False until this game's first hand is dealt (the owner's call); afterwards a ready majority deals the next. */
    val firstHandDealt: Boolean = false,
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

/**
 * What each player is shown of one committed table state. Everything that's the same for every recipient —
 * votes, runout odds, pots, showdown hands — is computed once in [of]; [forPlayer] adds what is private to
 * one player (their own pocket cards and betting options) and hides everyone else's cards until showdown.
 */
class GameStateProjection private constructor(
    private val state: PokerTableState,
    private val votes: List<VoteSummaryView>,
    private val runoutOdds: List<RunoutOddsView>,
    private val pots: List<PotView>,
    private val bestHands: Map<Int, BestHandView>,
) {
    companion object {
        fun of(state: PokerTableState): GameStateProjection {
            val votes = state.activeVotes.map {
                VoteSummaryView(it.id, it.resolutionType, it.targetPlayerId, it.yesVoters.size, it.noVoters.size, it.requiredVotes)
            }
            val runoutOdds = state.roundState?.let(ShowdownOdds::forRunout).orEmpty().map { street ->
                RunoutOddsView(street.boardCards, street.equities.map { (id, equity) -> PlayerEquityView(id, equity) })
            }
            val pots = state.roundState?.potBreakdown().orEmpty().map {
                PotView(it.amount, it.contenderIds, it.winnerIds, it.reason)
            }
            return GameStateProjection(state, votes, runoutOdds, pots, bestHands(state))
        }

        /** Each contender's best hand at a contested showdown (a lone survivor shows nothing); empty otherwise. */
        private fun bestHands(state: PokerTableState): Map<Int, BestHandView> {
            val roundState = state.roundState ?: return emptyMap()
            if (roundState.pokerRoundStage != PokerRoundStage.SHOWDOWN || state.players.count { it.isActive() } <= 1) {
                return emptyMap()
            }
            val board = roundState.communityCards
            return state.players
                .filter { it.isActive() && it.pocketCards.isNotEmpty() && board.size + it.pocketCards.size >= 5 }
                .associate { player ->
                    val hand = TexasHoldEmHandEvaluator.getMatchingPokerHand(board, player.pocketCards)
                    player.id to BestHandView(hand.ranking.toDisplayName(), hand.cards.map { it.toString() })
                }
        }
    }

    fun forPlayer(forPlayerId: Int): GameStateUpdate {
        val roundState = state.roundState
        val isShowdown = roundState?.pokerRoundStage == PokerRoundStage.SHOWDOWN
        val dealerId = roundState?.playerOrdering?.dealer()?.id
        val smallBlindId = roundState?.playerOrdering?.smallBlindPlayer()?.id
        val bigBlindId = roundState?.playerOrdering?.bigBlindPlayer()?.id

        val players = state.players.map { player ->
            val showCards = player.id == forPlayerId || (isShowdown && player.isActive())
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
                bestHand = bestHands[player.id],
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
            ?.let { state.turnTimerEndsAt ?: (it + state.config.turnTimerSeconds * 1000L) }
        val turnClockStartsAt = turnTimerEndsAt?.let { state.turnClockStartsAt ?: state.turnTimerStartedAt }

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
            activeVotes = votes,
            turnTimerEndsAt = turnTimerEndsAt,
            turnClockStartsAt = turnClockStartsAt,
            myBettingOptions = myBettingOptions,
            runoutOdds = runoutOdds,
            pots = pots,
            ownerId = state.ownerId,
            firstHandDealt = state.firstHandDealt(),
        )
    }
}
