package com.gustmmer.poker.server.service

import com.gustmmer.poker.ActiveVote
import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.isGameOver
import com.gustmmer.poker.participating
import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.routes.VoteSessionResponse
import com.gustmmer.poker.server.routes.toResponse
import com.gustmmer.poker.server.timer.TaskScheduler
import com.gustmmer.poker.server.timer.TurnTimerManager
import com.gustmmer.poker.server.voting.VoteOutcome
import com.gustmmer.poker.server.voting.VoteResolution
import com.gustmmer.poker.server.websocket.TableConnectionManager
import io.ktor.http.HttpStatusCode
import java.util.UUID

/**
 * Runs votes through the table itself: the tally lives in [PokerTableState.activeVotes], so opening or
 * casting a vote is just a [withTable] commit that fans out to every instance via the bus — no in-memory
 * session map, no cross-instance gap. When a cast tips a vote over the line, its consequence
 * (pause/unpause/kick/restart) is staged in the *same* commit and the vote is closed atomically; only the
 * post-commit side effects (timer transitions, ephemeral toasts) run afterwards.
 *
 * The vote timeout is a durable Cloud Task (via [TaskScheduler]) that calls [onVoteExpired] back — so it
 * survives restarts and reaches any instance, like the turn timer. [onVoteExpired] is idempotent: it
 * closes the vote only if [sessionId] is still open, so an at-least-once or post-resolution delivery is a
 * no-op (the UUID never collides with a re-opened vote).
 */
class VotingService(
    private val persistence: PokerTablePersistence,
    private val timerManager: TurnTimerManager,
    private val connectionManager: TableConnectionManager,
    private val scheduler: TaskScheduler,
    private val voteTimeoutSeconds: Long = 60,
) {
    suspend fun requestPause(tableId: Int, initiatorId: Int) =
        createSession(tableId, VoteResolution.PauseGame, initiatorId)

    suspend fun requestUnpause(tableId: Int, initiatorId: Int) =
        createSession(tableId, VoteResolution.UnpauseGame, initiatorId)

    suspend fun requestKick(tableId: Int, initiatorId: Int, targetPlayerId: Int) =
        createSession(tableId, VoteResolution.KickPlayer(targetPlayerId), initiatorId)

    suspend fun createSession(
        tableId: Int,
        resolution: VoteResolution,
        initiatorId: Int,
    ): ServiceResult<VoteSessionResponse> {
        var response: VoteSessionResponse? = null
        var effect: ResolutionEffect? = null
        var pendingSessionId: String? = null

        val result = withTable(tableId, persistence) { table ->
            validate(table.currentState, resolution)?.let { return@withTable it }

            val eligible = eligibleVoters(table.currentState, resolution)
            val vote = ActiveVote(
                id = UUID.randomUUID().toString(),
                resolutionType = resolution.typeName,
                targetPlayerId = resolution.kickTargetId,
                eligibleVoters = eligible,
                requiredVotes = eligible.size / 2 + 1,
                yesVoters = setOf(initiatorId),
                createdAtMillis = System.currentTimeMillis(),
            )
            table.openVote(vote)

            if (vote.passed) {
                effect = applyResolution(table, resolution)
                table.closeVote(vote.id)
                response = vote.toResponse(VoteOutcome.PASSED)
            } else {
                pendingSessionId = vote.id
                response = vote.toResponse(VoteOutcome.PENDING)
            }
            ServiceResult.Ok(Unit)
        }
        if (result is ServiceResult.Failed) return result

        effect?.let { runPostCommit(tableId, it) }
        pendingSessionId?.let { scheduler.scheduleVoteTimeout(tableId, it, voteTimeoutSeconds * 1000) }
        return ServiceResult.Ok(response!!)
    }

    suspend fun castVote(
        tableId: Int,
        sessionId: String,
        playerId: Int,
        yes: Boolean,
    ): ServiceResult<VoteSessionResponse>? {
        var response: VoteSessionResponse? = null
        var effect: ResolutionEffect? = null
        var resolved = false
        var notFound = false

        val result = withTable(tableId, persistence) { table ->
            val vote = table.currentState.activeVotes.find { it.id == sessionId }
            if (vote == null || playerId !in vote.eligibleVoters) {
                notFound = true
                return@withTable ServiceResult.Ok(Unit) // nothing staged → no write
            }
            table.castVote(sessionId, playerId, yes)
            val updated = table.currentState.activeVotes.first { it.id == sessionId }
            when {
                updated.passed -> {
                    effect = applyResolution(table, VoteResolution.from(updated))
                    table.closeVote(sessionId)
                    resolved = true
                    response = updated.toResponse(VoteOutcome.PASSED)
                }
                updated.failed -> {
                    table.closeVote(sessionId)
                    resolved = true
                    response = updated.toResponse(VoteOutcome.FAILED)
                }
                else -> response = updated.toResponse(VoteOutcome.PENDING)
            }
            ServiceResult.Ok(Unit)
        }
        if (notFound) return null
        if (result is ServiceResult.Failed) return result

        if (resolved) scheduler.cancelVote(sessionId)
        effect?.let { runPostCommit(tableId, it) }
        return ServiceResult.Ok(response!!)
    }

    /**
     * Cloud Tasks (or the in-memory scheduler) calls this when a vote's timeout elapses. Idempotent:
     * closes [sessionId] only if it is still open, broadcasting a timeout toast when it does.
     */
    suspend fun onVoteExpired(tableId: Int, sessionId: String) {
        val closed = withTable(tableId, persistence) { table ->
            val present = table.currentState.activeVotes.any { it.id == sessionId }
            if (present) table.closeVote(sessionId)
            ServiceResult.Ok(present)
        }
        if ((closed as? ServiceResult.Ok)?.value == true) {
            connectionManager.broadcastMessage(tableId, "vote_failed", "Vote timed out")
        }
    }

    suspend fun openSessions(tableId: Int): List<VoteSessionResponse> {
        val state = loadTable(tableId, persistence) ?: return emptyList()
        return state.activeVotes.map { it.toResponse(VoteOutcome.PENDING) }
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private fun validate(s: PokerTableState, resolution: VoteResolution): ServiceResult.Failed? = when (resolution) {
        is VoteResolution.RestartGame ->
            if (!s.isGameOver()) ServiceResult.Failed(HttpStatusCode.BadRequest, "Cannot restart while game is still active") else null
        is VoteResolution.PauseGame ->
            if (s.gameStatus == GameStatus.PAUSED) ServiceResult.Failed(HttpStatusCode.BadRequest, "Already paused") else null
        is VoteResolution.UnpauseGame ->
            if (s.gameStatus != GameStatus.PAUSED) ServiceResult.Failed(HttpStatusCode.BadRequest, "Not paused") else null
        is VoteResolution.KickPlayer -> {
            val target = s.players.find { it.id == resolution.targetPlayerId }
            when {
                target == null -> ServiceResult.Failed(HttpStatusCode.NotFound, "Target player not found")
                target.status != PlayerStatus.OFFLINE && target.status != PlayerStatus.IDLE ->
                    ServiceResult.Failed(HttpStatusCode.BadRequest, "Can only kick offline or idle players")
                else -> null
            }
        }
    }

    private fun eligibleVoters(s: PokerTableState, resolution: VoteResolution): Set<Int> {
        val participating = s.players.participating().map { it.id }
        return when (resolution) {
            is VoteResolution.KickPlayer -> participating.filterNot { it == resolution.targetPlayerId }.toSet()
            else -> participating.toSet()
        }
    }

    private sealed class ResolutionEffect {
        data object Paused : ResolutionEffect()
        data class Unpaused(val remainingMs: Long?) : ResolutionEffect()
        data class Kicked(val playerName: String) : ResolutionEffect()
        data object Restarted : ResolutionEffect()
    }

    /** Stages the table mutation for a passed vote and returns what post-commit side effects to run. */
    private fun applyResolution(table: PokerTable, resolution: VoteResolution): ResolutionEffect = when (resolution) {
        is VoteResolution.PauseGame -> {
            val s = table.currentState
            val remaining = s.turnTimerStartedAt?.let {
                (s.config.turnTimerSeconds * 1000L - (System.currentTimeMillis() - it)).coerceAtLeast(0)
            }
            table.pause(remaining)
            ResolutionEffect.Paused
        }
        is VoteResolution.UnpauseGame -> ResolutionEffect.Unpaused(table.unpause())
        is VoteResolution.KickPlayer -> {
            val name = table.currentState.players.find { it.id == resolution.targetPlayerId }?.name ?: "Player"
            table.kickPlayer(resolution.targetPlayerId)
            ResolutionEffect.Kicked(name)
        }
        is VoteResolution.RestartGame -> {
            table.restartGame()
            ResolutionEffect.Restarted
        }
    }

    private suspend fun runPostCommit(tableId: Int, effect: ResolutionEffect) {
        when (effect) {
            ResolutionEffect.Paused -> {
                timerManager.cancelTimer(tableId)
                connectionManager.broadcastMessage(tableId, "paused", "Game paused")
            }
            is ResolutionEffect.Unpaused -> {
                connectionManager.broadcastMessage(tableId, "unpaused", "Game resumed")
                val state = loadTable(tableId, persistence) ?: return
                if (effect.remainingMs != null && effect.remainingMs > 0) {
                    timerManager.startTimer(tableId, effect.remainingMs)
                } else {
                    timerManager.resolveExpiredTurns(tableId, state.config.turnTimerSeconds * 1000L)
                }
            }
            is ResolutionEffect.Kicked ->
                connectionManager.broadcastMessage(tableId, "player_kicked", "${effect.playerName} was kicked")
            ResolutionEffect.Restarted -> { /* the restarted state fans out via the bus */ }
        }
    }
}
