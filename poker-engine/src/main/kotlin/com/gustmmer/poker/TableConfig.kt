package com.gustmmer.poker

import kotlinx.serialization.Serializable

@Serializable
data class TableConfig(
    val startingChips: Int,
    val turnTimerSeconds: Int,
    val maxPlayers: Int,
    /** Human-friendly label so players can tell tables apart; empty means "unnamed" (UI falls back to id). */
    val name: String = "",
    val isOpen: Boolean = true,
    val blindEscalationOrbits: Int = 2,
    val blindEscalationMultiplier: Double = 2.0,
) {
    companion object {
        /** Max stored length of a table [name]; the server truncates to this and the UI caps input at it. */
        const val MAX_NAME_LENGTH = 40
    }
}
