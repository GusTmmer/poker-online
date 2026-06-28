package com.gustmmer.poker.server.voting

import com.gustmmer.poker.ActiveVote

/**
 * What a passed vote does. The vote *tally* now lives in the table state ([ActiveVote]); this sealed
 * type only describes the consequence the server executes when a vote passes. [typeName]/[kickTargetId]
 * mirror `ActiveVote.resolutionType`/`targetPlayerId` so the two convert freely.
 */
sealed class VoteResolution {
    data object PauseGame : VoteResolution()
    data object UnpauseGame : VoteResolution()
    data class KickPlayer(val targetPlayerId: Int) : VoteResolution()
    data object RestartGame : VoteResolution()

    val typeName: String
        get() = when (this) {
            is PauseGame -> "PAUSE_GAME"
            is UnpauseGame -> "UNPAUSE_GAME"
            is KickPlayer -> "KICK_PLAYER"
            is RestartGame -> "RESTART_GAME"
        }

    val kickTargetId: Int? get() = (this as? KickPlayer)?.targetPlayerId

    companion object {
        fun of(resolutionType: String, targetPlayerId: Int?): VoteResolution = when (resolutionType) {
            "PAUSE_GAME" -> PauseGame
            "UNPAUSE_GAME" -> UnpauseGame
            "KICK_PLAYER" -> KickPlayer(requireNotNull(targetPlayerId) { "KICK_PLAYER needs a target" })
            "RESTART_GAME" -> RestartGame
            else -> error("Unknown resolution type: $resolutionType")
        }

        fun from(vote: ActiveVote): VoteResolution = of(vote.resolutionType, vote.targetPlayerId)
    }
}

enum class VoteOutcome { PASSED, FAILED, PENDING }
