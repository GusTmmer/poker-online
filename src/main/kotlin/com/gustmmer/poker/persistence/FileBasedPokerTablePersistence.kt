package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class FileBasedPokerTablePersistence(
    private val serializer: PokerTableStateSerializer,
    private val baseDir: Path = Path.of("poker_tables"),
) : PokerTablePersistence {

    companion object {
        fun json() = FileBasedPokerTablePersistence(JsonSerializer())
    }

    override fun loadState(pokerTableId: Int): PokerTableState? {
        val file = getTableFile(pokerTableId)
        if (!file.exists()) {
            return null
        }

        return try {
            serializer.deserialize(Files.readString(file.toPath()))
        } catch (e: Exception) {
            println("Error loading state from $file: ${e.message}")
            null
        }
    }

    override fun saveState(state: PokerTableState) {
        val file = getTableFile(state.id)

        Files.createDirectories(file.parentFile.toPath())

        Files.writeString(file.toPath(), serializer.serialize(state))
    }

    private fun getTableFile(pokerTableId: Int): File {
        return baseDir.resolve("$pokerTableId.json").toFile()
    }
} 