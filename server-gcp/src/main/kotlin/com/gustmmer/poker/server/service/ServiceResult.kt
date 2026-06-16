package com.gustmmer.poker.server.service

import com.gustmmer.poker.PokerTable
import com.gustmmer.poker.persistence.PokerTablePersistence
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

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
 * Restores [tableId], runs [block], and retries on [ConcurrentModificationException] —
 * the optimistic-concurrency conflict thrown by [PokerTable]'s persistence layer.
 */
suspend fun <T> withTable(
    tableId: Int,
    persistence: PokerTablePersistence,
    maxAttempts: Int = 3,
    block: suspend (PokerTable) -> ServiceResult<T>,
): ServiceResult<T> {
    var attempts = 0
    while (true) {
        val table = PokerTable.restore(tableId, persistence)
            ?: return ServiceResult.Failed(HttpStatusCode.NotFound, "Table not found")
        try {
            return block(table)
        } catch (_: ConcurrentModificationException) {
            if (++attempts >= maxAttempts) {
                return ServiceResult.Failed(HttpStatusCode.Conflict, "Concurrent update, please retry")
            }
        }
    }
}
