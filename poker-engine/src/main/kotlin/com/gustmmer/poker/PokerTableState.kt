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

/**
 * How far the players' screens have been told to animate this hand: [actionsSeen] entries of the hand's
 * action log have been accounted for, and the table is busy playing them until [busyUntil] (epoch ms).
 * Lets a turn's clock wait until the table actually shows it's that player's turn. Null at a hand's start.
 */
@Serializable
data class PresentationCursor(val actionsSeen: Int, val busyUntil: Long)

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
    val turnClockStartsAt: Long? = null,
    val turnTimerEndsAt: Long? = null,
    val presentation: PresentationCursor? = null,
    val version: Long = 0,
    val roundsSinceLastEscalation: Int = 0,
    val initialPlayerCount: Int = 0,
    val activeVotes: List<ActiveVote> = emptyList(),
    val pendingRemovals: List<Int> = emptyList(),
    /** 0 on records written before it existed — restored as one past the highest seated id. */
    val nextPlayerId: Int = 0,
    /** Null on records written before tables had owners — restored as the earliest-seated human. */
    val ownerId: Int? = null,
)

/**
 * Time left on the current turn at [now], head start included; null when no turn clock is running. States
 * written before the deadline was persisted fall back to the configured turn length from its start.
 */
fun PokerTableState.turnTimeRemainingMs(now: Long): Long? {
    val endsAt = turnTimerEndsAt ?: turnTimerStartedAt?.let { it + config.turnTimerSeconds * 1000L } ?: return null
    return (endsAt - now).coerceAtLeast(0)
}

fun PokerTableState.isGameOver(): Boolean = players.participating().size <= 1

/**
 * Whether this game has dealt its first hand. Until it has, only the owner can start play; after a restart
 * the new game's first hand is the owner's again.
 */
fun PokerTableState.firstHandDealt(): Boolean = initialPlayerCount > 0

/** Humans who are here to play: seated, not eliminated, and ONLINE. Readiness is measured against them. */
fun PokerTableState.presentHumans(): List<Player> =
    players.filter { !it.isBot && it.isParticipating() && it.status == PlayerStatus.ONLINE }

/** True when more than half of the [presentHumans] are ready. */
fun PokerTableState.majorityReady(): Boolean {
    val present = presentHumans()
    return present.isNotEmpty() && present.count { it.id in readyPlayers } * 2 > present.size
}

/**
 * Who owns the table once [departing] players are gone: the current owner while they stay, otherwise the
 * human who has been seated longest (ids are handed out in join order and never reused). Null with no human left.
 */
internal fun ownerAmong(players: List<Player>, currentOwner: Int?, departing: Set<Int> = emptySet()): Int? {
    val staying = players.filter { !it.isBot && it.id !in departing }
    return currentOwner?.takeIf { owner -> staying.any { it.id == owner } } ?: staying.minOfOrNull { it.id }
}

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
    /** When the current turn began; also its timer's idempotency token. */
    val turnTimerStartedAt: Long? = null,
    /**
     * When the current turn's countdown starts: [turnTimerStartedAt] plus a head start while the players'
     * screens finish animating what led to the turn (see [presentation]).
     */
    val turnClockStartsAt: Long? = null,
    /** When the current turn times out (epoch ms). */
    val turnTimerEndsAt: Long? = null,
    val presentation: PresentationCursor? = null,
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
    /**
     * The id the next player to join gets. Ids are never reused: a session cookie names its player by id, so
     * a player who left or was kicked would otherwise take over whoever joined into their old id.
     */
    val nextPlayerId: Int = (players.maxOfOrNull { it.id } ?: -1) + 1,
    /**
     * The player who runs the table: only they can start a hand, start a new game, rename or open/close the
     * table. Created as the table's creator; passes to the longest-seated human when the owner leaves.
     */
    val ownerId: Int? = null,
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
                turnClockStartsAt = state.turnClockStartsAt,
                turnTimerEndsAt = state.turnTimerEndsAt,
                presentation = state.presentation,
                version = state.version,
                roundsSinceLastEscalation = state.roundsSinceLastEscalation,
                initialPlayerCount = state.initialPlayerCount,
                activeVotes = state.activeVotes,
                pendingRemovals = state.pendingRemovals.filter { it in playerMap }.toSet(),
                nextPlayerId = maxOf(state.nextPlayerId, (players.maxOfOrNull { it.id } ?: -1) + 1),
                ownerId = ownerAmong(players, state.ownerId, departing = state.pendingRemovals.toSet()),
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
        turnClockStartsAt = turnClockStartsAt,
        turnTimerEndsAt = turnTimerEndsAt,
        presentation = presentation,
        version = version,
        roundsSinceLastEscalation = roundsSinceLastEscalation,
        initialPlayerCount = initialPlayerCount,
        activeVotes = activeVotes,
        pendingRemovals = pendingRemovals.toList(),
        nextPlayerId = nextPlayerId,
        ownerId = ownerId,
    )
}
