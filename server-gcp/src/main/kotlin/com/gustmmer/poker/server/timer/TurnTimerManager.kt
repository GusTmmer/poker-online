package com.gustmmer.poker.server.timer

import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.majorityIdle
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.service.ServiceResult
import com.gustmmer.poker.server.service.loadTable
import com.gustmmer.poker.server.service.withTable
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Auto-plays an absent player's turn when their clock expires. The countdown itself lives in a
 * [TaskScheduler] (durable Cloud Tasks in production, an in-process timer for dev/tests) rather than in
 * this object's memory — so it survives restarts and scale-to-zero, and there is no per-instance timer
 * map to keep. The only durable timer state is the persisted `turnTimerStartedAt`, which doubles as the
 * idempotency token. Each auto-play/auto-pause is a commit, so clients are updated by the
 * [com.gustmmer.poker.server.bus.TableUpdateBus] fan-out — this class never broadcasts directly.
 */
class TurnTimerManager(
    private val persistence: PokerTablePersistence,
    private val scheduler: TaskScheduler,
) {
    private val log = LoggerFactory.getLogger(TurnTimerManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    suspend fun startTimer(tableId: Int, durationMs: Long) {
        scheduler.cancel(tableId)

        val state = loadTable(tableId, persistence) ?: return
        val roundState = state.roundState ?: return
        if (!roundState.pokerRoundStage.isBettingRound()) return

        val currentPlayer = roundState.playerOrdering.bettingPlayer()

        // The player whose turn it is isn't here — resolve their turn now rather than start a clock.
        if (currentPlayer.status == PlayerStatus.IDLE || currentPlayer.status == PlayerStatus.OFFLINE) {
            scope.launch { resolveExpiredTurns(tableId, durationMs) }
            return
        }

        // The persisted start time IS the turn token: every new turn re-arms with a fresh timestamp, so
        // a late or duplicate delivery of a prior turn's task no longer matches and is ignored. Also
        // the broadcast clock (`turnTimerEndsAt`).
        val startedAt = Instant.now().toEpochMilli()
        val persisted = withTable(tableId, persistence) {
            it.timerStarted(startedAt)
            ServiceResult.Ok(Unit)
        }
        if (persisted is ServiceResult.Failed) {
            log.warn("Could not persist turn-timer start for table {}: {}", tableId, persisted.error)
        }
        scheduler.scheduleTurnTimeout(tableId, token = startedAt, delayMs = durationMs)
    }

    fun cancelTimer(tableId: Int) {
        scheduler.cancel(tableId)
    }

    /**
     * Stops the timer and returns the milliseconds left on the current turn (to resume after an
     * unpause), derived from the persisted start time. Null when no round/timer is active.
     */
    suspend fun pauseTimer(tableId: Int): Long? {
        scheduler.cancel(tableId)
        val state = loadTable(tableId, persistence) ?: return null
        val startedAt = state.turnTimerStartedAt ?: return null
        val durationMs = state.config.turnTimerSeconds * 1000L
        val elapsed = Instant.now().toEpochMilli() - startedAt
        return (durationMs - elapsed).coerceAtLeast(0)
    }

    /**
     * Cascades through offline/idle players, auto-playing each turn (one commit + broadcast per step)
     * until an ONLINE player is reached (whose timer is armed) or the round ends. Used both for lazy
     * resolution on reconnect and to continue past an auto-played turn.
     */
    suspend fun resolveExpiredTurns(tableId: Int, turnTimerMs: Long) {
        while (true) {
            val state = loadTable(tableId, persistence) ?: return
            val roundState = state.roundState ?: break
            if (!roundState.pokerRoundStage.isBettingRound()) break

            when (roundState.playerOrdering.bettingPlayer().status) {
                PlayerStatus.OFFLINE, PlayerStatus.IDLE -> {
                    // The commit fans out to clients via the bus subscription.
                    withTable(tableId, persistence) { it.autoPlayForCurrentPlayer(); ServiceResult.Ok(Unit) }
                }
                PlayerStatus.ONLINE -> {
                    startTimer(tableId, turnTimerMs)
                    return
                }
                PlayerStatus.ELIMINATED -> break
            }
        }

        // If auto-plays ended the round, transition to WAITING so clients can ready-up.
        val finalRound = loadTable(tableId, persistence)?.roundState
        if (finalRound != null && !finalRound.pokerRoundStage.isBettingRound()) {
            withTable(tableId, persistence) { it.clearRoundState(); ServiceResult.Ok(Unit) }
        }
    }

    /**
     * Entry point when a scheduled timer fires — called directly by [InMemoryTaskScheduler] or by the
     * internal endpoint that Cloud Tasks delivers to. Idempotent: a delivery is ignored unless [token]
     * still matches the table's current `turnTimerStartedAt` (the player already acted or a new turn
     * began otherwise), the game is RUNNING (not paused/over), and a betting round is live. Otherwise it
     * idles the timed-out player and either auto-pauses (now majority-idle) or auto-plays their turn —
     * all in one commit — then cascades through any further absent players.
     */
    suspend fun onTimerFired(tableId: Int, token: Long) {
        var autoPaused = false
        val result = withTable(tableId, persistence) { table ->
            val s = table.currentState
            if (s.turnTimerStartedAt != token) return@withTable ServiceResult.Ok(false) // stale/superseded
            if (s.gameStatus != GameStatus.RUNNING) return@withTable ServiceResult.Ok(false) // paused/over
            val roundState = s.roundState ?: return@withTable ServiceResult.Ok(false)
            if (!roundState.pokerRoundStage.isBettingRound()) return@withTable ServiceResult.Ok(false)

            roundState.playerOrdering.bettingPlayer().setAsIdle()
            if (s.majorityIdle()) {
                table.pause(null)
                autoPaused = true
            } else {
                table.autoPlayForCurrentPlayer()
            }
            ServiceResult.Ok(true)
        }

        if (result !is ServiceResult.Ok || !result.value) return
        if (autoPaused) return

        val state = loadTable(tableId, persistence) ?: return
        resolveExpiredTurns(tableId, state.config.turnTimerSeconds * 1000L)
    }
}
