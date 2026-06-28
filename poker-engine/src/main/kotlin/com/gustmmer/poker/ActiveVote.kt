package com.gustmmer.poker

import kotlinx.serialization.Serializable

/**
 * An in-progress vote, stored as part of [PokerTableState] so it is shared across server instances and
 * fans out on every committed change like any other table state. Purely ID-based (player ids, a string
 * resolution type), so it serializes directly with the table — no [com.gustmmer.poker.persistence.Wireable]
 * indirection needed.
 *
 * The engine only *stores* the tally; the server decides eligibility, [requiredVotes], and what a
 * passed vote does (pause/kick/etc.). [resolutionType]/[targetPlayerId] mirror the server's
 * `VoteResolution` so the server can reconstruct it.
 */
@Serializable
data class ActiveVote(
    val id: String,
    val resolutionType: String,
    val targetPlayerId: Int? = null,
    val eligibleVoters: Set<Int>,
    val requiredVotes: Int,
    val yesVoters: Set<Int> = emptySet(),
    val noVoters: Set<Int> = emptySet(),
    val createdAtMillis: Long,
) {
    val passed: Boolean get() = yesVoters.size >= requiredVotes
    val failed: Boolean get() = noVoters.size >= requiredVotes
}
