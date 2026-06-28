package com.gustmmer.poker.server.timer

import com.google.cloud.tasks.v2.CloudTasksClient
import com.google.cloud.tasks.v2.HttpMethod
import com.google.cloud.tasks.v2.HttpRequest
import com.google.cloud.tasks.v2.QueueName
import com.google.cloud.tasks.v2.Task
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Production [TaskScheduler]: enqueues a Cloud Task that POSTs the internal expiry endpoint at the
 * scheduled time. The task carries the shared [internalToken] (the endpoint rejects calls without it)
 * and the turn [token] in the body. Durable — it fires across restarts and scale-to-zero (the delivery
 * cold-starts an instance), and lands on whichever instance is available.
 *
 * [cancel] is a deliberate no-op: deleting a scheduled task would mean tracking task names, and it
 * isn't needed for correctness — a fired-but-obsolete task is rejected by the handler's token check.
 */
class CloudTasksScheduler(
    projectId: String,
    location: String,
    queue: String,
    private val serviceUrl: String,
    private val internalToken: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) : TaskScheduler {

    private val client = CloudTasksClient.create()
    private val queuePath = QueueName.of(projectId, location, queue).toString()

    override fun scheduleTurnTimeout(tableId: Int, token: Long, delayMs: Long) {
        enqueue("/internal/timer-expire/$tableId", """{"token":$token}""", delayMs, "turn-timeout table $tableId")
    }

    override fun scheduleVoteTimeout(tableId: Int, sessionId: String, delayMs: Long) {
        enqueue("/internal/vote-expire/$tableId/$sessionId", "{}", delayMs, "vote-timeout $sessionId")
    }

    private fun enqueue(path: String, body: String, delayMs: Long, label: String) {
        // Enqueue off the calling coroutine — the gRPC call is blocking and must not stall game flow.
        scope.launch {
            runCatching {
                val scheduleSeconds = (System.currentTimeMillis() + delayMs) / 1000
                val task = Task.newBuilder()
                    .setHttpRequest(
                        HttpRequest.newBuilder()
                            .setUrl("$serviceUrl$path")
                            .setHttpMethod(HttpMethod.POST)
                            .putHeaders("X-Internal-Token", internalToken)
                            .putHeaders("Content-Type", "application/json")
                            .setBody(ByteString.copyFromUtf8(body))
                            .build()
                    )
                    .setScheduleTime(Timestamp.newBuilder().setSeconds(scheduleSeconds).build())
                    .build()
                client.createTask(queuePath, task)
            }.onFailure { log.error("Failed to enqueue {} task", label, it) }
        }
    }

    override fun cancel(tableId: Int) {
        // No-op by design: the handler's turn-token check makes an obsolete delivery harmless.
    }

    override fun cancelVote(sessionId: String) {
        // No-op by design: the vote-expiry handler closes a vote only if it still exists.
    }

    companion object {
        private val log = LoggerFactory.getLogger(CloudTasksScheduler::class.java)
    }
}
