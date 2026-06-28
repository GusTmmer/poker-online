package com.gustmmer.poker.server.service

import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.persistence.PokerTablePersistence
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ServiceResult<out T> {
    data class Ok<T>(val value: T, val status: HttpStatusCode = HttpStatusCode.OK) : ServiceResult<T>()
    data class Failed(val status: HttpStatusCode, val error: String) : ServiceResult<Nothing>()
}

suspend inline fun <reified T : Any> ApplicationCall.respond(result: ServiceResult<T>) {
    when (result) {
        is ServiceResult.Ok<T> -> respond(result.status, result.value)
        is ServiceResult.Failed -> respond(result.status, mapOf("error" to result.error))
    }
}

/**
 * Read-only access to a table. Returns an unmanaged snapshot — there is no commit path from it, so
 * it makes "I am only reading" the explicit, structural intent. Use [withTable] to change state.
 *
 * The Firestore SDK call is blocking (`.get()`), so it runs on [Dispatchers.IO] to keep it off the
 * Netty event-loop threads.
 */
suspend fun loadTable(tableId: Int, persistence: PokerTablePersistence): PokerTableState? =
    withContext(Dispatchers.IO) { persistence.loadState(tableId) }

/**
 * The single write path. Restores [tableId], runs [block] (which validates and *stages* mutations on
 * the table — mutators no longer persist on their own), then commits exactly once with an
 * optimistic-concurrency check. A conflicting commit restores fresh and retries the whole block, so
 * one `withTable` call == one consistent state transition.
 *
 * The commit is skipped when [block] returns [ServiceResult.Failed] or staged nothing (so a
 * read-and-bail or no-op flip writes nothing). Side effects that must observe the committed state —
 * broadcasts, timer scheduling — belong *after* this call returns, not inside [block].
 */
suspend fun <T> withTable(
    tableId: Int,
    persistence: PokerTablePersistence,
    maxAttempts: Int = 3,
    block: suspend (PokerTable) -> ServiceResult<T>,
): ServiceResult<T> {
    var attempts = 0
    while (true) {
        // restore (read) and commit (write) hit the blocking Firestore SDK — keep them on IO threads.
        val table = withContext(Dispatchers.IO) { PokerTable.restore(tableId, persistence) }
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Table not found")
        try {
            val result = block(table)
            if (result is ServiceResult.Ok) withContext(Dispatchers.IO) { table.commit() }
            return result
        } catch (_: ConcurrentModificationException) {
            if (++attempts >= maxAttempts) {
                return ServiceResult.Failed(HttpStatusCode.Conflict, "Concurrent update, please retry")
            }
        }
    }
}
