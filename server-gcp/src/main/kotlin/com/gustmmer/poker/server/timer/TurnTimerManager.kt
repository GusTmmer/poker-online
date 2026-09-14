package com.gustmmer.poker.server.timer

import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PokerTable
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
 * Auto-plays an absent player's turn when their clock expires, and plays computer players' turns: a
 * bot's turn rides the same durable timer, scheduled for the bot's "thinking" delay, and its expiry
 * plays the bot's move instead of timing anyone out.
 *
 * The countdown itself lives in a [TaskScheduler] (durable Cloud Tasks in production, an in-process timer for dev/tests) rather than in
 * this object's memory — so it survives restarts and scale-to-zero, and there is no per-instance timer
 * map to keep. The only durable timer state is the persisted `turnTimerStartedAt`, which doubles as the
 * idempotency token. Each auto-play/auto-pause is a commit, so clients are updated by the
 * [com.gustmmer.poker.server.bus.TableUpdateBus] fan-out — this class never broadcasts directly.
 */
class TurnTimerManager(
    private val persistence: PokerTablePersistence,
    private val scheduler: TaskScheduler,
    /** Scales computer players' thinking delays; see [com.gustmmer.poker.server.config.ServerConfig.botDelayScale]. */
    private val botDelayScale: Double = 1.0,
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

        // A computer player's "clock" is just its thinking time; the expiry plays its move.
        val delayMs = if (currentPlayer.isBot) {
            val thinkMs = PokerTable(state, persistence).botDecisionForCurrentPlayer()?.thinkMs ?: 0L
            (thinkMs * botDelayScale).toLong()
        } else {
            durationMs
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
        scheduler.scheduleTurnTimeout(tableId, token = startedAt, delayMs = delayMs)
    }

    fun cancelTimer(tableId: Int) {
        scheduler.cancel(tableId)
    }

    /**
     * Post-commit follow-up for anything that may have ended or advanced a hand (an action, a new hand
     * dealt straight to showdown, a player folded by leaving or a kick). A finished hand is cleared to
     * WAITING in its *own* commit, so its showdown reveal fans out as a distinct frame first. Then the
     * turn timer is armed for the next bettor, or stopped.
     */
    suspend fun settleAfterCommit(tableId: Int) {
        withTable(tableId, persistence) { table ->
            val round = table.currentState.roundState
            // Re-checked inside the transaction: a hand dealt in the gap is never clobbered.
            if (round != null && !round.pokerRoundStage.isBettingRound()) table.clearRoundState()
            ServiceResult.Ok(Unit)
        }

        val state = loadTable(tableId, persistence) ?: return
        val roundState = state.roundState
        if (state.gameStatus == GameStatus.RUNNING && roundState != null && roundState.pokerRoundStage.isBettingRound()) {
            startTimer(tableId, state.config.turnTimerSeconds * 1000L)
        } else {
            cancelTimer(tableId)
        }
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
                    // The commit fans out to clients via the bus subscription. Stop if nothing was played
                    // (or it was rejected) — looping again would never make progress.
                    val played = withTable(tableId, persistence) { ServiceResult.Ok(it.autoPlayForCurrentPlayer()) }
                    if (played !is ServiceResult.Ok || played.value == null) {
                        log.warn("Auto-play made no progress on table {}: {}", tableId, played)
                        break
                    }
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
     * all in one commit — then cascades through any further absent players. A computer player's expiry
     * plays its move instead.
     */
    suspend fun onTimerFired(tableId: Int, token: Long) {
        var autoPaused = false
        val result = withTable(tableId, persistence) { table ->
            val s = table.currentState
            if (s.turnTimerStartedAt != token) return@withTable ServiceResult.Ok(false) // stale/superseded
            if (s.gameStatus != GameStatus.RUNNING) return@withTable ServiceResult.Ok(false) // paused/over
            val roundState = s.roundState ?: return@withTable ServiceResult.Ok(false)
            if (!roundState.pokerRoundStage.isBettingRound()) return@withTable ServiceResult.Ok(false)

            if (roundState.playerOrdering.bettingPlayer().isBot) {
                return@withTable ServiceResult.Ok(table.playBotTurn() != null)
            }

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
