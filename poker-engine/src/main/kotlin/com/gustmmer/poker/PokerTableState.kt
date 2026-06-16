package com.gustmmer.poker

import com.gustmmer.poker.persistence.Wireable
import com.gustmmer.poker.round.PlayerOrdering
import com.gustmmer.poker.round.PokerRoundState
import com.gustmmer.poker.round.WireablePlayerOrdering
import com.gustmmer.poker.round.WireablePokerRoundState
import kotlinx.serialization.Serializable

@Serializable
enum class GameStatus { WAITING, RUNNING, PAUSED }

@Serializable
data class WireablePokerTableState(
    val id: Int,
    val players: List<WireablePlayer>,
    val playerOrdering: WireablePlayerOrdering,
    val blinds: Blinds,
    val roundState: WireablePokerRoundState?,
    val config: TableConfig,
    val gameStatus: GameStatus = GameStatus.WAITING,
    val readyPlayers: List<Int> = emptyList(),
    val turnTimeRemainingMs: Long? = null,
    val turnTimerStartedAt: Long? = null,
    val version: Long = 0,
    val roundsSinceLastEscalation: Int = 0,
    val initialPlayerCount: Int = 0,
)

fun PokerTableState.isGameOver(): Boolean = players.participating().size <= 1

data class PokerTableState(
    val id: Int,
    val players: MutableList<Player>,
    val playerOrdering: PlayerOrdering,
    val blinds: Blinds,
    val roundState: PokerRoundState?,
    val config: TableConfig,
    val gameStatus: GameStatus = GameStatus.WAITING,
    val readyPlayers: Set<Int> = emptySet(),
    val turnTimeRemainingMs: Long? = null,
    val turnTimerStartedAt: Long? = null,
    val version: Long = 0,
    val roundsSinceLastEscalation: Int = 0,
    val initialPlayerCount: Int = 0,
) : Wireable<WireablePokerTableState> {

    companion object {
        fun restore(state: WireablePokerTableState): PokerTableState {
            val players = state.players.map { it.restore() }
            val playerMap = players.associateBy { it.id }

            // Infer gameStatus for records written before this field existed (roundState present but gameStatus defaulted to WAITING)
            val gameStatus = when {
                state.roundState != null && state.turnTimeRemainingMs != null -> GameStatus.PAUSED
                state.roundState != null && state.gameStatus == GameStatus.WAITING -> GameStatus.RUNNING
                else -> state.gameStatus
            }

            return PokerTableState(
                id = state.id,
                players = players.toMutableList(),
                playerOrdering = PlayerOrdering.restore(state.playerOrdering, players),
                blinds = state.blinds,
                roundState = state.roundState?.let { PokerRoundState.restore(it, playerMap) },
                config = state.config,
                gameStatus = gameStatus,
                readyPlayers = state.readyPlayers.toSet(),
                turnTimeRemainingMs = state.turnTimeRemainingMs,
                turnTimerStartedAt = state.turnTimerStartedAt,
                version = state.version,
                roundsSinceLastEscalation = state.roundsSinceLastEscalation,
                initialPlayerCount = state.initialPlayerCount,
            )
        }
    }

    override fun toWire() = WireablePokerTableState(
        id = id,
        players = players.map { it.toWire() },
        playerOrdering = playerOrdering.toWire(),
        blinds = blinds,
        roundState = roundState?.toWire(),
        config = config,
        gameStatus = gameStatus,
        readyPlayers = readyPlayers.toList(),
        turnTimeRemainingMs = turnTimeRemainingMs,
        turnTimerStartedAt = turnTimerStartedAt,
        version = version,
        roundsSinceLastEscalation = roundsSinceLastEscalation,
        initialPlayerCount = initialPlayerCount,
    )
}
