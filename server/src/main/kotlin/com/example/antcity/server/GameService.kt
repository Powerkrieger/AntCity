package com.example.antcity.server

import com.example.antcity.core.ActionResult
import com.example.antcity.core.ApplyResult
import com.example.antcity.core.AttackRequest
import com.example.antcity.core.AttackResponse
import com.example.antcity.core.Battle
import com.example.antcity.core.BattleReport
import com.example.antcity.core.GameState
import com.example.antcity.core.LogEntry
import com.example.antcity.core.RAIDED_SHIELD_MS
import com.example.antcity.core.RegisterResponse
import com.example.antcity.core.ServerEvent
import com.example.antcity.core.Sim
import com.example.antcity.core.Snapshot
import com.example.antcity.core.SyncRequest
import com.example.antcity.core.SyncResponse
import com.example.antcity.core.TargetInfo
import com.example.antcity.core.UnitType
import com.example.antcity.core.VerifyResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class ApiException(val status: Int, message: String) : RuntimeException(message)

/**
 * The authoritative game server. The client never uploads state, only actions; every action is
 * re-simulated here against the player's checkpoint, so a city can only contain what could have
 * been built from its genesis state in the time that has passed.
 */
class GameService(
    private val db: Db,
    private val clock: () -> Long = System::currentTimeMillis,
    /** How far in the future an action timestamp may lie (client clock drift). */
    private val maxClockSkewMs: Long = 10_000,
) {
    private val lock = Any()
    private val random = SecureRandom()

    companion object {
        const val MAX_ACTIONS_PER_SYNC = 500
        private val NAME_RE = Regex("^[A-Za-z0-9 _-]{3,20}$")

        fun hashToken(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun <T> locked(block: () -> T): T = synchronized(lock) { db.tx(block) }

    private fun requirePlayer(id: String) = db.player(id) ?: throw ApiException(404, "Unknown player")

    private fun snapshotOf(state: GameState, version: Long, now: Long) = Snapshot(Sim.advanceTo(state, now), version, now)

    fun register(rawName: String): RegisterResponse = locked {
        val name = rawName.trim()
        if (!NAME_RE.matches(name)) throw ApiException(400, "Name must be 3-20 letters, digits, spaces, _ or -")
        if (db.nameTaken(name)) throw ApiException(409, "Name is already taken")
        val now = clock()
        val id = UUID.randomUUID().toString()
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val genesis = GameState.genesis(now)
        db.insertPlayer(id, name, hashToken(token), now, genesis)
        RegisterResponse(id, token, snapshotOf(genesis, 0, now))
    }

    fun authenticate(token: String): String? = synchronized(lock) { db.playerIdByTokenHash(hashToken(token)) }

    fun snapshot(playerId: String): Snapshot = locked {
        val p = requirePlayer(playerId)
        snapshotOf(p.checkpoint, p.version, clock())
    }

    fun sync(playerId: String, req: SyncRequest): SyncResponse = locked {
        if (req.actions.size > MAX_ACTIONS_PER_SYNC) throw ApiException(413, "Too many actions in one sync")
        val p = requirePlayer(playerId)
        val now = clock()
        // If the city changed on the server since the client's snapshot (e.g. it was raided), some of the
        // client's actions may legitimately fail. Otherwise an honest client, running the same rules, never
        // sends an action the server rejects.
        val baseMatches = req.baseVersion == p.version
        var state = p.checkpoint
        var appended = 0
        var newResults = 0
        var allNewAccepted = true

        val results = req.actions.map { action ->
            db.processed(playerId, action.id)?.let { return@map it } // retransmission

            newResults++
            val result = if (action.at > now + maxClockSkewMs) {
                db.recordIntegrityEvent(playerId, now, "future_timestamp", "action ${action.id} at ${action.at}, server time $now")
                ActionResult(action.id, false, "Timestamp is in the future")
            } else {
                val (effective, r) = Sim.step(state, action)
                when (r) {
                    is ApplyResult.Ok -> {
                        state = r.state
                        db.appendLog(playerId, LogEntry.PlayerAction(effective), now)
                        appended++
                        ActionResult(action.id, true)
                    }
                    is ApplyResult.Rejected -> {
                        if (baseMatches) db.recordIntegrityEvent(playerId, now, "rejected_action", "${effective}: ${r.reason}")
                        ActionResult(action.id, false, r.reason)
                    }
                }
            }
            if (!result.accepted) allNewAccepted = false
            db.recordProcessed(playerId, result)
            result
        }

        if (baseMatches && allNewAccepted && newResults > 0 && req.expectedHash != null && req.expectedHash != state.hash()) {
            db.recordIntegrityEvent(playerId, now, "state_mismatch", "client ${req.expectedHash} server ${state.hash()}")
        }

        val version = if (appended > 0) p.version + 1 else p.version
        if (appended > 0) db.saveCheckpoint(playerId, state, version)
        SyncResponse(snapshotOf(state, version, now), results)
    }

    fun targets(playerId: String): List<TargetInfo> = locked {
        val now = clock()
        val me = requirePlayer(playerId)
        val myLevel = me.checkpoint.townHallLevel
        db.allPlayers()
            .filter { it.id != playerId }
            .map { p ->
                val s = Sim.advanceTo(p.checkpoint, now)
                TargetInfo(
                    playerId = p.id,
                    name = p.name,
                    townHallLevel = s.townHallLevel,
                    defense = Battle.defenseOf(s),
                    shielded = s.shieldUntil > now,
                    potentialLoot = s.wholeStock.map { it * Battle.LOOT_PERCENT_VICTORY / 100 },
                )
            }
            .sortedWith(compareBy({ it.shielded }, { kotlin.math.abs(it.townHallLevel - myLevel) }, { it.name }))
            .take(30)
    }

    fun attack(attackerId: String, req: AttackRequest): AttackResponse = locked {
        if (req.targetId == attackerId) throw ApiException(400, "You cannot attack yourself")
        val now = clock()
        val attacker = requirePlayer(attackerId)
        val defender = requirePlayer(req.targetId)
        val aState = Sim.advanceTo(attacker.checkpoint, now)
        val dState = Sim.advanceTo(defender.checkpoint, now)

        if (dState.shieldUntil > now) throw ApiException(409, "${defender.name} is protected by a shield")
        val army = UnitType.entries.associateWith { req.army[it] ?: 0 }
        if (army.values.any { it < 0 } || army.values.sum() == 0) throw ApiException(400, "Send at least one unit")
        if (army.any { (type, n) -> n > aState.unitsOf(type) }) throw ApiException(409, "You don't have those units")

        val outcome = Battle.resolve(army, dState)
        val raided = outcome.loot.total() > 0 || outcome.wallsBreached
        val attackerEvent = ServerEvent.RaidReturned(now, outcome.attackerLosses, outcome.loot)
        val defenderEvent = ServerEvent.Raided(now, outcome.loot, if (raided) now + RAIDED_SHIELD_MS else 0)

        val newA = (Sim.applyEvent(aState, attackerEvent) as ApplyResult.Ok).state
        val newD = (Sim.applyEvent(dState, defenderEvent) as ApplyResult.Ok).state
        db.appendLog(attackerId, LogEntry.Server(attackerEvent), now)
        db.appendLog(defender.id, LogEntry.Server(defenderEvent), now)
        db.saveCheckpoint(attackerId, newA, attacker.version + 1)
        db.saveCheckpoint(defender.id, newD, defender.version + 1)

        val report = db.insertReport(attackerId, defender.id) { id ->
            BattleReport(id, now, attacker.name, defender.name, army, outcome)
        }
        AttackResponse(report, Snapshot(newA, attacker.version + 1, now))
    }

    fun reports(playerId: String): List<BattleReport> = locked { db.reports(playerId, 30) }

    /**
     * Full audit of one city: replays genesis + the complete log with the game rules and checks that
     * every step was legal, that no entry was timestamped ahead of when the server received it, and
     * that the result equals the stored state.
     */
    fun verify(playerId: String): VerifyResponse = locked {
        val p = requirePlayer(playerId)
        val log = db.log(playerId)
        log.forEachIndexed { i, (entry, receivedAt) ->
            if (entry.at > receivedAt + maxClockSkewMs) {
                return@locked VerifyResponse(playerId, false, log.size, false, i, "Entry timestamped after it was received")
            }
        }
        when (val r = Sim.replay(p.genesis, log.map { it.first })) {
            is Sim.ReplayResult.Invalid -> VerifyResponse(playerId, false, log.size, false, r.index, r.reason)
            is Sim.ReplayResult.Valid -> {
                val matches = r.state == p.checkpoint
                VerifyResponse(playerId, matches, log.size, matches, reason = if (matches) null else "Stored state differs from replay")
            }
        }
    }

    fun verifyAll(): List<VerifyResponse> = locked { db.allPlayers().map { it.id } }.map(::verify)

    fun integrityEvents() = locked { db.integrityEvents(200) }
}
