package com.gustmmer.poker.persistence

import com.gustmmer.poker.PokerTableState
import com.gustmmer.poker.WireablePokerTableState
import kotlinx.serialization.json.Json

class JsonSerializer : PokerTableStateSerializer {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override fun serialize(pokerTableState: PokerTableState): String {
        return json.encodeToString(WireablePokerTableState.serializer(), pokerTableState.toWire())
    }

    override fun deserialize(serializedPokerTableState: String): PokerTableState {
        return PokerTableState.restore(json.decodeFromString<WireablePokerTableState>(serializedPokerTableState))
    }
}