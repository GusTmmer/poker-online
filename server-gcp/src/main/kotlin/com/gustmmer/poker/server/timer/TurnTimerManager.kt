package com.gustmmer.poker.server.timer

import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.websocket.TableConnectionManager
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class TurnTimerState(
    val tableId: Int,
    val playerId: Int,
    val startedAt: Instant,
    val durationMs: Long,
    val job: Job,
)

class TurnTimerManager(
    private val persistence: PokerTablePersistence,
    private val connectionManager: TableConnectionManager,
) {
    private val activeTimers = ConcurrentHashMap<Int, TurnTimerState>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun startTimer(table: PokerTable, durationMs: Long) {
        cancelTimer(table.id)

        val roundState = table.currentState.roundState ?: return
        if (!roundState.pokerRoundStage.isBettingRound()) return

        val currentPlayer = roundState.playerOrdering.bettingPlayer()

        if (currentPlayer.status == PlayerStatus.IDLE || currentPlayer.status == PlayerStatus.OFFLINE) {
            scope.launch { resolveImmediately(table) }
            return
        }

        val job = scope.launch {
            delay(durationMs)
            onTimerExpired(table.id)
        }

        activeTimers[table.id] = TurnTimerState(
            tableId = table.id,
            playerId = currentPlayer.id,
            startedAt = Instant.now(),
            durationMs = durationMs,
            job = job,
        )
    }

    fun cancelTimer(tableId: Int) {
        activeTimers.remove(tableId)?.job?.cancel()
    }

    fun pauseTimer(tableId: Int): Long? {
        val timer = activeTimers.remove(tableId) ?: return null
        timer.job.cancel()
        val elapsed = Instant.now().toEpochMilli() - timer.startedAt.toEpochMilli()
        return (timer.durationMs - elapsed).coerceAtLeast(0)
    }

    fun getRemainingMs(tableId: Int): Long? {
        val timer = activeTimers[tableId] ?: return null
        val elapsed = Instant.now().toEpochMilli() - timer.startedAt.toEpochMilli()
        return (timer.durationMs - elapsed).coerceAtLeast(0)
    }

    /**
     * Lazy evaluation: resolves expired turns when reconnecting after all players were away.
     * Cascades through offline/idle players until reaching an online player or round end.
     */
    suspend fun resolveExpiredTurns(table: PokerTable, turnTimerMs: Long) {
        while (true) {
            val roundState = table.currentState.roundState ?: break
            if (!roundState.pokerRoundStage.isBettingRound()) break

            val currentPlayer = roundState.playerOrdering.bettingPlayer()

            when (currentPlayer.status) {
                PlayerStatus.OFFLINE, PlayerStatus.IDLE -> {
                    table.autoPlayForCurrentPlayer()
                    connectionManager.broadcastGameState(table.currentState)
                }
                PlayerStatus.ONLINE -> {
                    startTimer(table, turnTimerMs)
                    break
                }
                PlayerStatus.ELIMINATED -> break
            }
        }
    }

    private suspend fun onTimerExpired(tableId: Int) {
        activeTimers.remove(tableId) ?: return
        val table = PokerTable.restore(tableId, persistence) ?: return

        val roundState = table.currentState.roundState ?: return
        if (!roundState.pokerRoundStage.isBettingRound()) return

        val currentPlayer = roundState.playerOrdering.bettingPlayer()
        currentPlayer.setAsIdle()

        table.autoPlayForCurrentPlayer()
        connectionManager.broadcastGameState(table.currentState)

        resolveExpiredTurns(table, table.currentState.config.turnTimerSeconds * 1000L)
    }

    private suspend fun resolveImmediately(table: PokerTable) {
        table.autoPlayForCurrentPlayer()
        connectionManager.broadcastGameState(table.currentState)
        resolveExpiredTurns(table, table.currentState.config.turnTimerSeconds * 1000L)
    }
}
