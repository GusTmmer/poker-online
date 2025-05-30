package com.gustmmer.poker.round

enum class CommandType {
    FOLD,
    CALL,
    RAISE,
    ALL_IN,
}

sealed class PlayerCommand(
    val playerId: Int,
    val type: CommandType,
)

class Call(playerId: Int) : PlayerCommand(playerId, CommandType.CALL)
class Raise(playerId: Int, val value: Int) : PlayerCommand(playerId, CommandType.RAISE)
class AllIn(playerId: Int) : PlayerCommand(playerId, CommandType.ALL_IN)
class Fold(playerId: Int) : PlayerCommand(playerId, CommandType.FOLD)
