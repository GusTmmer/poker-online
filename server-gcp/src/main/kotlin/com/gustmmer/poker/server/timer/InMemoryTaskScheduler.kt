package com.gustmmer.poker.server.timer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-process [TaskScheduler] for local dev and tests — a `delay()` coroutine per table that calls
 * the attached expiry handler. Equivalent to the original in-memory turn timer; the only structural
 * change is that firing is routed through the same [TurnTimerManager.onTimerFired] entry point the
 * Cloud Tasks endpoint uses. Not durable across restarts (neither is the in-memory persistence it pairs with).
 */
class InMemoryTaskScheduler(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
) : TaskScheduler {

    private val jobs = ConcurrentHashMap<Int, Job>()
    private val voteJobs = ConcurrentHashMap<String, Job>()

    @Volatile
    private var expiry: (suspend (Int, Long) -> Unit)? = null

    @Volatile
    private var voteExpiry: (suspend (Int, String) -> Unit)? = null

    override fun attachExpiry(handler: suspend (tableId: Int, token: Long) -> Unit) {
        expiry = handler
    }

    override fun attachVoteExpiry(handler: suspend (tableId: Int, sessionId: String) -> Unit) {
        voteExpiry = handler
    }

    override fun scheduleTurnTimeout(tableId: Int, token: Long, delayMs: Long) {
        cancel(tableId)
        jobs[tableId] = scope.launch {
            delay(delayMs)
            jobs.remove(tableId)
            expiry?.invoke(tableId, token)
        }
    }

    override fun scheduleVoteTimeout(tableId: Int, sessionId: String, delayMs: Long) {
        cancelVote(sessionId)
        voteJobs[sessionId] = scope.launch {
            delay(delayMs)
            voteJobs.remove(sessionId)
            voteExpiry?.invoke(tableId, sessionId)
        }
    }

    override fun cancel(tableId: Int) {
        jobs.remove(tableId)?.cancel()
    }

    override fun cancelVote(sessionId: String) {
        voteJobs.remove(sessionId)?.cancel()
    }
}
