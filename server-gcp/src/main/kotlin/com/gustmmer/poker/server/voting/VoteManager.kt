package com.gustmmer.poker.server.voting

import com.gustmmer.poker.server.websocket.TableConnectionManager
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

enum class VoteType { PAUSE, UNPAUSE, KICK }

data class ActiveVote(
    val type: VoteType,
    val tableId: Int,
    val targetPlayerId: Int? = null,
    val requiredVotes: Int,
    val voters: MutableSet<Int> = mutableSetOf(),
    val job: Job,
)

data class VoteResult(
    val passed: Boolean,
    val currentVotes: Int,
    val requiredVotes: Int,
    val message: String,
)

class VoteManager(
    private val connectionManager: TableConnectionManager,
    private val timeoutSeconds: Long = 60,
) {
    private val activeVotes = ConcurrentHashMap<String, ActiveVote>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun vote(
        tableId: Int,
        playerId: Int,
        type: VoteType,
        onlinePlayerCount: Int,
        targetPlayerId: Int? = null,
    ): VoteResult {
        val key = voteKey(tableId, type, targetPlayerId)

        val vote = activeVotes.getOrPut(key) {
            val job = scope.launch {
                delay(timeoutSeconds * 1000)
                onVoteTimeout(key, tableId)
            }
            ActiveVote(
                type = type,
                tableId = tableId,
                targetPlayerId = targetPlayerId,
                requiredVotes = (onlinePlayerCount / 2) + 1,
                job = job,
            )
        }

        vote.voters.add(playerId)

        if (vote.voters.size >= vote.requiredVotes) {
            activeVotes.remove(key)
            vote.job.cancel()
            return VoteResult(
                passed = true,
                currentVotes = vote.voters.size,
                requiredVotes = vote.requiredVotes,
                message = "Vote passed",
            )
        }

        return VoteResult(
            passed = false,
            currentVotes = vote.voters.size,
            requiredVotes = vote.requiredVotes,
            message = "Vote recorded (${vote.voters.size}/${vote.requiredVotes})",
        )
    }

    fun hasActiveVote(tableId: Int, type: VoteType, targetPlayerId: Int? = null): Boolean {
        return activeVotes.containsKey(voteKey(tableId, type, targetPlayerId))
    }

    private fun voteKey(tableId: Int, type: VoteType, targetPlayerId: Int?): String {
        return "$tableId:$type:${targetPlayerId ?: ""}"
    }

    private suspend fun onVoteTimeout(key: String, tableId: Int) {
        val vote = activeVotes.remove(key) ?: return
        connectionManager.broadcastMessage(
            tableId,
            "vote_failed",
            "${vote.type.name} vote failed (timeout)"
        )
    }
}
