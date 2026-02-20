package com.gustmmer.poker

import kotlinx.serialization.Serializable

@Serializable
data class TableConfig(
    val startingChips: Int,
    val turnTimerSeconds: Int,
    val maxPlayers: Int,
    val isOpen: Boolean = true,
    val blindEscalationOrbits: Int = 2,
    val blindEscalationMultiplier: Double = 2.0,
)
