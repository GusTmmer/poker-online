package com.gustmmer.poker.server.bus

import com.gustmmer.poker.PokerTableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-process [TableUpdateBus] for local dev and tests. There are no other instances to reach, so
 * "fan-out" is just delivering to the subscribers registered in this process. Commits reach it through
 * [com.gustmmer.poker.server.persistence.NotifyingPersistence], which calls [publish] after a
 * successful write — mirroring how Firestore's listener fires after a committed write in production.
 */
class InMemoryTableUpdateBus(
    // limitedParallelism(1) serializes deliveries so subscribers observe commits in publish order —
    // matching Firestore's per-document ordering guarantee. Commits for one table are already
    // sequential (optimistic concurrency), so this preserves their order.
    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob()),
) : TableUpdateBus {

    private class Sub(val handler: suspend (PokerTableState) -> Unit)

    private val subsByTable = ConcurrentHashMap<Int, MutableSet<Sub>>()

    override fun subscribe(tableId: Int, onChange: suspend (PokerTableState) -> Unit): Subscription {
        val sub = Sub(onChange)
        subsByTable.getOrPut(tableId) { ConcurrentHashMap.newKeySet() }.add(sub)
        return Subscription {
            subsByTable[tableId]?.let { set ->
                set.remove(sub)
                if (set.isEmpty()) subsByTable.remove(tableId, set)
            }
        }
    }

    /** Notify subscribers of [state]'s table that it changed. Called by the persistence decorator. */
    fun publish(state: PokerTableState) {
        val subs = subsByTable[state.id] ?: return
        for (sub in subs) scope.launch { sub.handler(state) }
    }
}
