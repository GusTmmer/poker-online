package com.gustmmer.poker

sealed class SystemCommand {
    data object Continue : SystemCommand()
}