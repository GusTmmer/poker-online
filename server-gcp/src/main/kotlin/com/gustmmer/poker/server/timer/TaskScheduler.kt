package com.gustmmer.poker.server.timer

/**
 * Schedules a future turn-timer expiry (Phase 2 of docs/path-b-plan.md). Replaces the in-process
 * `delay()` coroutine, whose timer died with the instance, with a durable external schedule that fires
 * even across restarts and scale-to-zero.
 *
 * Two implementations:
 *  - [CloudTasksScheduler] — production. Enqueues a Cloud Task that calls the internal expiry endpoint
 *    at the scheduled time; survives restarts and reaches whichever instance is up.
 *  - [InMemoryTaskScheduler] — local/dev/tests. A `delay()` coroutine that invokes the handler in-process.
 *
 * Expiry is delivered through [attachExpiry]: in-process schedulers call it directly; Cloud Tasks
 * delivers via HTTP to the internal endpoint, which then calls the same handler — so the handler
 * ([TurnTimerManager.onTimerFired]) is identical on both paths. Delivery is at-least-once, so the
 * handler must be idempotent (it validates a turn token).
 */
interface TaskScheduler {
    /** Schedule the turn-timer expiry for [tableId] at now + [delayMs], carrying [token]. */
    fun scheduleTurnTimeout(tableId: Int, token: Long, delayMs: Long)

    /** Schedule the vote-timeout expiry for [sessionId] on [tableId] at now + [delayMs]. */
    fun scheduleVoteTimeout(tableId: Int, sessionId: String, delayMs: Long)

    /** Best-effort cancel of the turn timer. Cloud Tasks may no-op and rely on the token check instead. */
    fun cancel(tableId: Int)

    /** Best-effort cancel of a vote timeout. Cloud Tasks no-ops (the expiry handler is idempotent). */
    fun cancelVote(sessionId: String)

    /** Registers the in-process turn-expiry handler. No-op for HTTP-delivered schedulers (Cloud Tasks). */
    fun attachExpiry(handler: suspend (tableId: Int, token: Long) -> Unit) {}

    /** Registers the in-process vote-expiry handler. No-op for HTTP-delivered schedulers (Cloud Tasks). */
    fun attachVoteExpiry(handler: suspend (tableId: Int, sessionId: String) -> Unit) {}
}
