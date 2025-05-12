package com.gustmmer.poker.round

import com.gustmmer.poker.Player

enum class CommandType {
    FOLD,
    CALL,
    RAISE,
    ALL_IN,
}

sealed class PlayerCommand(
    val player: Player,
    val type: CommandType,
)

class Call(player: Player) : PlayerCommand(player, CommandType.CALL)
class Raise(player: Player, val value: Int) : PlayerCommand(player, CommandType.RAISE)
class AllIn(player: Player) : PlayerCommand(player, CommandType.ALL_IN)
class Fold(player: Player) : PlayerCommand(player, CommandType.FOLD)
