package com.gustmmer.poker.server.service

import com.gustmmer.poker.ActiveVote
import com.gustmmer.poker.GameStatus
import com.gustmmer.poker.PlayerStatus
import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.isGameOver
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
        val result = withTable(tableId, persistence) { table ->
            validate(table.currentState, resolution)?.let { return@withTable it }

            val eligible = eligibleVoters(table.currentState, resolution, initiatorId)
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

            val commit = if (vote.passed) {
                val effect = applyResolution(table, resolution)
                table.closeVote(vote.id)
                VoteCommit(vote.toResponse(VoteOutcome.PASSED), effect = effect)
            } else {
                VoteCommit(vote.toResponse(VoteOutcome.PENDING), scheduleTimeoutFor = vote.id)
            }
            ServiceResult.Ok(commit)
        }
        return when (result) {
            is ServiceResult.Failed -> result
            is ServiceResult.Ok -> ServiceResult.Ok(applyCommit(tableId, result.value))
        }
    }

    suspend fun castVote(
        tableId: Int,
        sessionId: String,
        playerId: Int,
        yes: Boolean,
    ): ServiceResult<VoteSessionResponse>? {
        val result: ServiceResult<VoteCommit?> = withTable(tableId, persistence) { table ->
            val vote = table.currentState.activeVotes.find { it.id == sessionId }
            if (vote == null || playerId !in vote.eligibleVoters) {
                return@withTable ServiceResult.Ok<VoteCommit?>(null) // nothing staged → no write; signals not-found
            }
            table.castVote(sessionId, playerId, yes)
            val updated = table.currentState.activeVotes.first { it.id == sessionId }
            val commit = when {
                updated.passed -> {
                    val effect = applyResolution(table, VoteResolution.from(updated))
                    table.closeVote(sessionId)
                    VoteCommit(updated.toResponse(VoteOutcome.PASSED), effect = effect, cancelTimeoutFor = sessionId)
                }
                updated.failed -> {
                    table.closeVote(sessionId)
                    VoteCommit(updated.toResponse(VoteOutcome.FAILED), cancelTimeoutFor = sessionId)
                }
                else -> VoteCommit(updated.toResponse(VoteOutcome.PENDING))
            }
            ServiceResult.Ok<VoteCommit?>(commit)
        }
        return when (result) {
            is ServiceResult.Failed -> result
            is ServiceResult.Ok -> result.value?.let { ServiceResult.Ok(applyCommit(tableId, it)) } // null → not found
        }
    }

    /** What a committed vote operation produced; consumed by [applyCommit] after the write. */
    private data class VoteCommit(
        val response: VoteSessionResponse,
        val effect: ResolutionEffect? = null,
        val scheduleTimeoutFor: String? = null, // a new pending vote → arm its timeout
        val cancelTimeoutFor: String? = null,   // a vote resolved → cancel its timeout
    )

    /** Runs the post-commit side effects a vote operation asked for, then returns its response. */
    private suspend fun applyCommit(tableId: Int, commit: VoteCommit): VoteSessionResponse {
        commit.cancelTimeoutFor?.let { scheduler.cancelVote(it) }
        commit.scheduleTimeoutFor?.let { scheduler.scheduleVoteTimeout(tableId, it, voteTimeoutSeconds * 1000) }
        commit.effect?.let { runPostCommit(tableId, it) }
        return commit.response
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

    /**
     * Who gets a vote: players who are actually present (status ONLINE), read from persisted state so
     * the set is identical on every instance. The initiator is acting right now, so count them in even
     * if their status hasn't caught up. Offline/idle players don't inflate the required-votes denominator
     * (otherwise a vote among a few present players could be mathematically impossible to pass).
     */
    private fun eligibleVoters(s: PokerTableState, resolution: VoteResolution, initiatorId: Int): Set<Int> {
        val online = s.players.filter { it.status == PlayerStatus.ONLINE }.map { it.id }.toMutableSet()
        online.add(initiatorId)
        if (resolution is VoteResolution.KickPlayer) online.remove(resolution.targetPlayerId)
        return online
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
