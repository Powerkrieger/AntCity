package com.example.antcity.data

import com.example.antcity.core.Action
import com.example.antcity.core.GameJson
import com.example.antcity.core.Snapshot
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Everything the app persists. The game state itself is never authoritative on the device:
 * it's the last server [snapshot] plus the [pending] actions that haven't been uploaded yet.
 */
@Serializable
data class SavedGame(
    val serverUrl: String,
    val playerId: String,
    val token: String,
    val name: String,
    val snapshot: Snapshot,
    val pending: List<Action> = emptyList(),
    /** serverTime - deviceTime, so that action timestamps use the server's clock. */
    val clockOffset: Long = 0,
)

class SaveStore(dir: File) {
    private val file = File(dir, "antcity-save.json")

    fun load(): SavedGame? = runCatching {
        if (file.exists()) GameJson.decodeFromString(SavedGame.serializer(), file.readText()) else null
    }.getOrNull()

    fun save(game: SavedGame) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(GameJson.encodeToString(SavedGame.serializer(), game))
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }
}
