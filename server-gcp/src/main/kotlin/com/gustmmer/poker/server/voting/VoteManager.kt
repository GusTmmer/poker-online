package com.gustmmer.poker.server.voting

import com.gustmmer.poker.persistence.PokerTablePersistence
import com.gustmmer.poker.server.websocket.TableConnectionManager
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed class VoteResolution {
    data object PauseGame : VoteResolution()
    data object UnpauseGame : VoteResolution()
    data class KickPlayer(val targetPlayerId: Int) : VoteResolution()
    data object RestartGame : VoteResolution()
    data object IncreaseBlinds : VoteResolution()

    val typeName: String get() = when (this) {
        is PauseGame -> "PAUSE_GAME"
        is UnpauseGame -> "UNPAUSE_GAME"
        is KickPlayer -> "KICK_PLAYER"
        is RestartGame -> "RESTART_GAME"
        is IncreaseBlinds -> "INCREASE_BLINDS"
    }

    val kickTargetId: Int? get() = (this as? KickPlayer)?.targetPlayerId
}

data class VotingSession(
    val id: String,
    val tableId: Int,
    val resolution: VoteResolution,
    val eligibleVoters: Set<Int>,
    val requiredVotes: Int,
    val yesVoters: MutableSet<Int> = mutableSetOf(),
    val noVoters: MutableSet<Int> = mutableSetOf(),
    val createdAt: Instant,
    val job: Job,
)

data class VoteSummary(
    val sessionId: String,
    val resolutionType: String,
    val targetPlayerId: Int?,
    val yesCount: Int,
    val noCount: Int,
    val requiredVotes: Int,
)

enum class VoteOutcome { PASSED, FAILED, PENDING }

data class VoteResult(val outcome: VoteOutcome, val session: VotingSession)

fun VotingSession.toSummary() = VoteSummary(
    sessionId = id,
    resolutionType = resolution.typeName,
    targetPlayerId = resolution.kickTargetId,  // null for non-kick resolutions
    yesCount = yesVoters.size,
    noCount = noVoters.size,
    requiredVotes = requiredVotes,
)

class VoteManager(
    private val connectionManager: TableConnectionManager,
    private val persistence: PokerTablePersistence,
    private val timeoutSeconds: Long = 60,
) {
    private val sessionsById = ConcurrentHashMap<String, VotingSession>()
    private val sessionKeyToId = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun createSession(
        tableId: Int,
        resolution: VoteResolution,
        eligibleVoters: Set<Int>,
        initiatorId: Int,
    ): VoteResult {
        val key = sessionKey(tableId, resolution)

        // Cancel any existing session for the same resolution
        sessionKeyToId.remove(key)?.let { oldId ->
            sessionsById.remove(oldId)?.job?.cancel()
        }

        val sessionId = UUID.randomUUID().toString()
        val requiredVotes = (eligibleVoters.size + 1) / 2  // ceil(n/2)

        val job = scope.launch {
            delay(timeoutSeconds * 1000)
            onTimeout(key, tableId, sessionId)
        }

        val session = VotingSession(
            id = sessionId,
            tableId = tableId,
            resolution = resolution,
            eligibleVoters = eligibleVoters,
            requiredVotes = requiredVotes,
            yesVoters = mutableSetOf(initiatorId),
            createdAt = Instant.now(),
            job = job,
        )

        sessionsById[sessionId] = session
        sessionKeyToId[key] = sessionId

        return evaluate(key, session)
    }

    fun castVote(sessionId: String, playerId: Int, yes: Boolean): VoteResult? {
        val session = sessionsById[sessionId] ?: return null
        if (playerId !in session.eligibleVoters) return null

        if (yes) {
            session.noVoters.remove(playerId)
            session.yesVoters.add(playerId)
        } else {
            session.yesVoters.remove(playerId)
            session.noVoters.add(playerId)
        }

        return evaluate(sessionKey(session.tableId, session.resolution), session)
    }

    fun getOpenSessions(tableId: Int): List<VotingSession> {
        return sessionsById.values.filter { it.tableId == tableId }
    }

    private fun sessionKey(tableId: Int, resolution: VoteResolution): String {
        return "$tableId:${resolution.typeName}:${resolution.kickTargetId ?: ""}"
    }

    private fun evaluate(key: String, session: VotingSession): VoteResult {
        return when {
            session.yesVoters.size >= session.requiredVotes -> {
                session.job.cancel()
                sessionsById.remove(session.id)
                sessionKeyToId.remove(key)
                VoteResult(VoteOutcome.PASSED, session)
            }
            session.noVoters.size >= session.requiredVotes -> {
                session.job.cancel()
                sessionsById.remove(session.id)
                sessionKeyToId.remove(key)
                VoteResult(VoteOutcome.FAILED, session)
            }
            else -> VoteResult(VoteOutcome.PENDING, session)
        }
    }

    private suspend fun onTimeout(key: String, tableId: Int, sessionId: String) {
        val existingId = sessionKeyToId[key]
        if (existingId != sessionId) return  // already replaced

        sessionKeyToId.remove(key)
        val session = sessionsById.remove(sessionId) ?: return

        connectionManager.broadcastMessage(tableId, "vote_failed", "${session.resolution.typeName} vote timed out")

        val state = persistence.loadState(tableId) ?: return
        val remainingVotes = sessionsById.values.filter { it.tableId == tableId }.map { it.toSummary() }
        connectionManager.broadcastGameState(state, remainingVotes)
    }
}
