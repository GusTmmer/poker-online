package com.gustmmer.poker.server.bus

import com.gustmmer.poker.PokerTableState

/**
 * Cross-instance fan-out of committed table changes (Phase 1 of docs/path-b-plan.md).
 *
 * The problem it solves: on Cloud Run a table's players may be connected to *different* instances, so
 * the instance that commits a change can't reach the others' WebSockets directly. Instead, every
 * instance holding a socket for a table [subscribe]s to that table; whenever *any* instance commits,
 * the bus delivers the fresh [PokerTableState] to every subscriber, which then pushes to its own local
 * sockets.
 *
 * Two implementations:
 *  - [FirestoreTableUpdateBus] — production. Firestore snapshot listeners; the database itself is the
 *    publisher (the commit write triggers delivery), so there is no explicit publish step.
 *  - [InMemoryTableUpdateBus] — local/dev/tests. A single process, so commits publish in-process via
 *    the [com.gustmmer.poker.server.persistence.NotifyingPersistence] decorator.
 */
interface TableUpdateBus {
    /**
     * Starts delivering committed changes for [tableId] to [onChange]. [onChange] runs on a background
     * coroutine, so it must not assume the caller's context. Cancel the returned [Subscription] to stop.
     */
    fun subscribe(tableId: Int, onChange: suspend (PokerTableState) -> Unit): Subscription
}

fun interface Subscription {
    fun cancel()
}
