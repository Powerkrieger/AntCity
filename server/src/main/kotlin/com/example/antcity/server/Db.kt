package com.example.antcity.server

import com.example.antcity.core.ActionResult
import com.example.antcity.core.BattleReport
import com.example.antcity.core.GameJson
import com.example.antcity.core.GameState
import com.example.antcity.core.LogEntry
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

data class PlayerRow(
    val id: String,
    val name: String,
    val createdAt: Long,
    val genesis: GameState,
    /** State right after the last log entry (not advanced further). */
    val checkpoint: GameState,
    val version: Long,
)

data class IntegrityEvent(val playerId: String, val playerName: String, val at: Long, val kind: String, val detail: String)

/** Thin JDBC layer over SQLite. Not thread-safe; [GameService] serializes all access. */
class Db(path: String) {
    private val conn: Connection

    init {
        File(path).absoluteFile.parentFile?.mkdirs()
        conn = DriverManager.getConnection("jdbc:sqlite:$path")
        conn.createStatement().use { st ->
            st.executeUpdate("PRAGMA journal_mode = WAL")
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS players (
                    id TEXT PRIMARY KEY,
                    name TEXT UNIQUE NOT NULL COLLATE NOCASE,
                    token_hash TEXT UNIQUE NOT NULL,
                    created_at INTEGER NOT NULL,
                    genesis TEXT NOT NULL,
                    checkpoint TEXT NOT NULL,
                    version INTEGER NOT NULL
                )"""
            )
            // The complete history of every city: genesis + log must replay to the checkpoint.
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS log (
                    player_id TEXT NOT NULL REFERENCES players(id),
                    seq INTEGER NOT NULL,
                    entry TEXT NOT NULL,
                    received_at INTEGER NOT NULL,
                    PRIMARY KEY (player_id, seq)
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS processed_actions (
                    player_id TEXT NOT NULL,
                    action_id TEXT NOT NULL,
                    accepted INTEGER NOT NULL,
                    reason TEXT,
                    PRIMARY KEY (player_id, action_id)
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS reports (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    attacker_id TEXT NOT NULL,
                    defender_id TEXT NOT NULL,
                    report TEXT NOT NULL
                )"""
            )
            st.executeUpdate(
                """CREATE TABLE IF NOT EXISTS integrity_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id TEXT NOT NULL,
                    at INTEGER NOT NULL,
                    kind TEXT NOT NULL,
                    detail TEXT NOT NULL
                )"""
            )
        }
        conn.autoCommit = false
        conn.commit()
    }

    fun <T> tx(block: () -> T): T = try {
        block().also { conn.commit() }
    } catch (e: Throwable) {
        conn.rollback()
        throw e
    }

    private fun readPlayer(rs: java.sql.ResultSet) = PlayerRow(
        id = rs.getString("id"),
        name = rs.getString("name"),
        createdAt = rs.getLong("created_at"),
        genesis = GameJson.decodeFromString(GameState.serializer(), rs.getString("genesis")),
        checkpoint = GameJson.decodeFromString(GameState.serializer(), rs.getString("checkpoint")),
        version = rs.getLong("version"),
    )

    fun insertPlayer(id: String, name: String, tokenHash: String, createdAt: Long, genesis: GameState) {
        val json = GameJson.encodeToString(GameState.serializer(), genesis)
        conn.prepareStatement("INSERT INTO players VALUES (?, ?, ?, ?, ?, ?, 0)").use {
            it.setString(1, id); it.setString(2, name); it.setString(3, tokenHash)
            it.setLong(4, createdAt); it.setString(5, json); it.setString(6, json)
            it.executeUpdate()
        }
    }

    fun nameTaken(name: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM players WHERE name = ?").use {
            it.setString(1, name)
            it.executeQuery().use { rs -> rs.next() }
        }

    fun player(id: String): PlayerRow? =
        conn.prepareStatement("SELECT * FROM players WHERE id = ?").use {
            it.setString(1, id)
            it.executeQuery().use { rs -> if (rs.next()) readPlayer(rs) else null }
        }

    fun playerIdByTokenHash(hash: String): String? =
        conn.prepareStatement("SELECT id FROM players WHERE token_hash = ?").use {
            it.setString(1, hash)
            it.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }

    fun allPlayers(): List<PlayerRow> =
        conn.prepareStatement("SELECT * FROM players").use {
            it.executeQuery().use { rs -> generateSequence { if (rs.next()) readPlayer(rs) else null }.toList() }
        }

    fun saveCheckpoint(id: String, state: GameState, version: Long) {
        conn.prepareStatement("UPDATE players SET checkpoint = ?, version = ? WHERE id = ?").use {
            it.setString(1, GameJson.encodeToString(GameState.serializer(), state))
            it.setLong(2, version)
            it.setString(3, id)
            it.executeUpdate()
        }
    }

    fun appendLog(playerId: String, entry: LogEntry, receivedAt: Long) {
        conn.prepareStatement(
            "INSERT INTO log VALUES (?, (SELECT COALESCE(MAX(seq), 0) + 1 FROM log WHERE player_id = ?), ?, ?)"
        ).use {
            it.setString(1, playerId); it.setString(2, playerId)
            it.setString(3, GameJson.encodeToString(LogEntry.serializer(), entry))
            it.setLong(4, receivedAt)
            it.executeUpdate()
        }
    }

    /** The full log with the time the server received each entry. */
    fun log(playerId: String): List<Pair<LogEntry, Long>> =
        conn.prepareStatement("SELECT entry, received_at FROM log WHERE player_id = ? ORDER BY seq").use {
            it.setString(1, playerId)
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) GameJson.decodeFromString(LogEntry.serializer(), rs.getString(1)) to rs.getLong(2) else null
                }.toList()
            }
        }

    fun processed(playerId: String, actionId: String): ActionResult? =
        conn.prepareStatement("SELECT accepted, reason FROM processed_actions WHERE player_id = ? AND action_id = ?").use {
            it.setString(1, playerId); it.setString(2, actionId)
            it.executeQuery().use { rs -> if (rs.next()) ActionResult(actionId, rs.getInt(1) == 1, rs.getString(2)) else null }
        }

    fun recordProcessed(playerId: String, result: ActionResult) {
        conn.prepareStatement("INSERT INTO processed_actions VALUES (?, ?, ?, ?)").use {
            it.setString(1, playerId); it.setString(2, result.id)
            it.setInt(3, if (result.accepted) 1 else 0); it.setString(4, result.reason)
            it.executeUpdate()
        }
    }

    fun insertReport(attackerId: String, defenderId: String, build: (Long) -> BattleReport): BattleReport {
        conn.prepareStatement("INSERT INTO reports (attacker_id, defender_id, report) VALUES (?, ?, '')").use {
            it.setString(1, attackerId); it.setString(2, defenderId)
            it.executeUpdate()
        }
        val id = conn.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { rs -> rs.next(); rs.getLong(1) } }
        val report = build(id)
        conn.prepareStatement("UPDATE reports SET report = ? WHERE id = ?").use {
            it.setString(1, GameJson.encodeToString(BattleReport.serializer(), report))
            it.setLong(2, id)
            it.executeUpdate()
        }
        return report
    }

    fun reports(playerId: String, limit: Int): List<BattleReport> =
        conn.prepareStatement("SELECT report FROM reports WHERE attacker_id = ? OR defender_id = ? ORDER BY id DESC LIMIT ?").use {
            it.setString(1, playerId); it.setString(2, playerId); it.setInt(3, limit)
            it.executeQuery().use { rs ->
                generateSequence { if (rs.next()) GameJson.decodeFromString(BattleReport.serializer(), rs.getString(1)) else null }.toList()
            }
        }

    fun recordIntegrityEvent(playerId: String, at: Long, kind: String, detail: String) {
        conn.prepareStatement("INSERT INTO integrity_events (player_id, at, kind, detail) VALUES (?, ?, ?, ?)").use {
            it.setString(1, playerId); it.setLong(2, at); it.setString(3, kind); it.setString(4, detail)
            it.executeUpdate()
        }
    }

    fun integrityEvents(limit: Int): List<IntegrityEvent> =
        conn.prepareStatement(
            "SELECT e.player_id, p.name, e.at, e.kind, e.detail FROM integrity_events e JOIN players p ON p.id = e.player_id ORDER BY e.id DESC LIMIT ?"
        ).use {
            it.setInt(1, limit)
            it.executeQuery().use { rs ->
                generateSequence {
                    if (rs.next()) IntegrityEvent(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)) else null
                }.toList()
            }
        }
}
