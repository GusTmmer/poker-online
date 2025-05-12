package com.gustmmer.poker

import com.gustmmer.poker.round.PlayerCommand
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

data class PlayerIO(
    val playerCommands: ReceiveChannel<PlayerCommand>,
    val systemCommands: ReceiveChannel<SystemCommand>,
    val events: SendChannel<Any>? = null
)