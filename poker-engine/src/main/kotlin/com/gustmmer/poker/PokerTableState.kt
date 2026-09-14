package com.gustmmer.poker

import com.gustmmer.poker.persistence.Wireable
import com.gustmmer.poker.round.PlayerOrdering
import com.gustmmer.poker.round.PokerRoundState
import com.gustmmer.poker.round.WireablePlayerOrdering
import com.gustmmer.poker.round.WireablePokerRoundState
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(PokerTableState::class.java)

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
    val activeVotes: List<ActiveVote> = emptyList(),
    val pendingRemovals: List<Int> = emptyList(),
)

fun PokerTableState.isGameOver(): Boolean = players.participating().size <= 1

/** True when at least half of non-eliminated human players are IDLE. Computer players never idle. */
fun PokerTableState.majorityIdle(): Boolean {
    val active = players.filter { it.status != PlayerStatus.ELIMINATED && !it.isBot }
    if (active.isEmpty()) return false
    return active.count { it.status == PlayerStatus.IDLE } * 2 >= active.size
}

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
    val activeVotes: List<ActiveVote> = emptyList(),
    /**
     * Players who left or were kicked while a hand they were dealt into was still live. They stay seated
     * (folded) so the hand's pots and ordering keep resolving against them, and are dropped when the
     * hand is cleared.
     */
    val pendingRemovals: Set<Int> = emptySet(),
) : Wireable<WireablePokerTableState> {

    companion object {
        fun restore(state: WireablePokerTableState): PokerTableState {
            val players = state.players.map { it.restore() }
            val playerMap = players.associateBy { it.id }

            // A round that references a player no longer seated can't be restored (records written before
            // removals were deferred). Drop the hand rather than brick the table.
            val roundState = state.roundState?.takeIf { round ->
                val referenced = round.players + round.pots.flatMap { it.betsByPlayer.keys }
                (referenced.all { it in playerMap }).also { ok ->
                    if (!ok) log.error("Table {} round references unseated players; discarding the hand", state.id)
                }
            }

            // Infer gameStatus for records written before this field existed (roundState present but gameStatus defaulted to WAITING)
            val gameStatus = when {
                roundState == null && state.roundState != null -> GameStatus.WAITING
                roundState != null && state.turnTimeRemainingMs != null -> GameStatus.PAUSED
                roundState != null && state.gameStatus == GameStatus.WAITING -> GameStatus.RUNNING
                else -> state.gameStatus
            }

            return PokerTableState(
                id = state.id,
                players = players.toMutableList(),
                // Table-level positions index the participating players, as clearRoundState computes them.
                playerOrdering = PlayerOrdering.restore(state.playerOrdering, players.participating()),
                blinds = state.blinds,
                roundState = roundState?.let { PokerRoundState.restore(it, playerMap) },
                config = state.config,
                gameStatus = gameStatus,
                readyPlayers = state.readyPlayers.toSet(),
                turnTimeRemainingMs = state.turnTimeRemainingMs,
                turnTimerStartedAt = state.turnTimerStartedAt,
                version = state.version,
                roundsSinceLastEscalation = state.roundsSinceLastEscalation,
                initialPlayerCount = state.initialPlayerCount,
                activeVotes = state.activeVotes,
                pendingRemovals = state.pendingRemovals.filter { it in playerMap }.toSet(),
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
        activeVotes = activeVotes,
        pendingRemovals = pendingRemovals.toList(),
    )
}
