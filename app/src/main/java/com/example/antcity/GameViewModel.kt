package com.example.antcity

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antcity.core.Action
import com.example.antcity.core.ApplyResult
import com.example.antcity.core.AttackRequest
import com.example.antcity.core.BattleReport
import com.example.antcity.core.BuildingType
import com.example.antcity.core.GameState
import com.example.antcity.core.Sim
import com.example.antcity.core.Snapshot
import com.example.antcity.core.SyncRequest
import com.example.antcity.core.TargetInfo
import com.example.antcity.core.UnitType
import com.example.antcity.data.Api
import com.example.antcity.data.ApiError
import com.example.antcity.data.SaveStore
import com.example.antcity.data.SavedGame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.UUID

/**
 * Client side of the protocol: the device shows a *prediction* made by running the shared rules
 * over the last server snapshot plus the not-yet-uploaded actions. The server replays the same
 * actions and its answer always wins.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SaveStore(app.filesDir)

    var game by mutableStateOf(store.load())
        private set

    /** Snapshot state with all pending actions applied (not advanced to "now"). */
    private var predicted: GameState? = null

    /** What the player sees: [predicted] advanced to the current server time. */
    var city by mutableStateOf<GameState?>(null)
        private set
    var now by mutableLongStateOf(0L)
        private set

    var message by mutableStateOf<String?>(null)
    var online by mutableStateOf(true)
        private set
    var busy by mutableStateOf(false)
        private set
    var targets by mutableStateOf<List<TargetInfo>>(emptyList())
        private set
    var reports by mutableStateOf<List<BattleReport>>(emptyList())
        private set
    var lastReport by mutableStateOf<BattleReport?>(null)

    private val syncMutex = Mutex()
    private var pendingSyncJob: Job? = null

    init {
        refold()
        viewModelScope.launch {
            var ticks = 0
            while (true) {
                tick()
                if (ticks++ % 30 == 0 && game != null) syncNow()
                delay(1_000)
            }
        }
    }

    private fun serverNow() = System.currentTimeMillis() + (game?.clockOffset ?: 0)

    private fun tick() {
        now = serverNow()
        city = predicted?.let { Sim.advanceTo(it, now) }
    }

    /** Actions the local rules reject are skipped here; the server will reject them too. */
    private fun fold(base: GameState, actions: List<Action>): GameState =
        actions.fold(base) { s, a -> (Sim.step(s, a).second as? ApplyResult.Ok)?.state ?: s }

    private fun refold() {
        predicted = game?.let { fold(it.snapshot.state, it.pending) }
        tick()
    }

    private fun update(g: SavedGame?) {
        game = g
        if (g == null) store.clear() else store.save(g)
        refold()
    }

    /** Adopts a new authoritative snapshot, keeping actions made after [sentIds] were uploaded. */
    private fun adopt(snapshot: Snapshot, sentIds: Set<String>, requestStartedAt: Long) {
        val g = game ?: return
        val roundTrip = System.currentTimeMillis() - requestStartedAt
        val offset = snapshot.serverTime + roundTrip / 2 - System.currentTimeMillis()
        // Actions still pending are re-based on the new snapshot. Never let them predate it, so that
        // client and server clamp timestamps identically.
        val pending = g.pending.filter { it.id !in sentIds }
            .map { if (it.at < snapshot.state.time) it.withAt(snapshot.state.time) else it }
        update(g.copy(snapshot = snapshot, pending = pending, clockOffset = offset))
    }

    // --- Setup ---------------------------------------------------------------------------------

    fun register(serverUrl: String, name: String) = viewModelScope.launch {
        busy = true
        try {
            val url = serverUrl.trim().trimEnd('/')
            val r = Api(url).register(name.trim())
            update(SavedGame(url, r.playerId, r.token, name.trim(), r.snapshot, clockOffset = r.snapshot.serverTime - System.currentTimeMillis()))
        } catch (e: ApiError) {
            message = e.message
        } catch (e: IOException) {
            message = "Cannot reach server: ${e.message}"
        } finally {
            busy = false
        }
    }

    fun logout() = update(null)

    // --- Player actions ------------------------------------------------------------------------

    fun build(type: BuildingType, x: Int, y: Int) = perform { id, at -> Action.Build(id, at, type, x, y) }
    fun upgrade(buildingId: Int) = perform { id, at -> Action.Upgrade(id, at, buildingId) }
    fun demolish(buildingId: Int) = perform { id, at -> Action.Demolish(id, at, buildingId) }
    fun train(unit: UnitType, count: Int) = perform { id, at -> Action.Train(id, at, unit, count) }

    private fun perform(make: (id: String, at: Long) -> Action) {
        val g = game ?: return
        val base = predicted ?: return
        val action = make(UUID.randomUUID().toString(), maxOf(serverNow(), base.time))
        when (val r = Sim.step(base, action).second) {
            is ApplyResult.Rejected -> message = r.reason
            is ApplyResult.Ok -> {
                update(g.copy(pending = g.pending + action))
                scheduleSync()
            }
        }
    }

    // --- Server --------------------------------------------------------------------------------

    private fun scheduleSync() {
        pendingSyncJob?.cancel()
        pendingSyncJob = viewModelScope.launch {
            delay(1_500) // batch quick successive taps into one upload
            syncNow()
        }
    }

    fun syncNowAsync() = viewModelScope.launch { syncNow() }

    private suspend fun syncNow(): Unit = syncMutex.withLock {
        val g = game ?: return@withLock
        val sent = g.pending
        val startedAt = System.currentTimeMillis()
        call(g) { api ->
            if (sent.isEmpty()) {
                adopt(api.state(), emptySet(), startedAt)
            } else {
                val expected = fold(g.snapshot.state, sent).hash()
                val resp = api.sync(SyncRequest(g.snapshot.version, sent, expected))
                adopt(resp.snapshot, sent.map { it.id }.toSet(), startedAt)
                resp.results.filter { !it.accepted }.takeIf { it.isNotEmpty() }?.let { rejected ->
                    message = "Server rejected ${rejected.size} action(s): ${rejected.mapNotNull { it.reason }.distinct().joinToString()}"
                }
            }
        }
    }

    fun loadTargets() = viewModelScope.launch {
        val g = game ?: return@launch
        call(g) { api ->
            targets = api.targets()
            reports = api.reports()
        }
    }

    fun attack(target: TargetInfo, army: Map<UnitType, Int>) = viewModelScope.launch {
        busy = true
        try {
            syncNow() // the server must know about every unit we trained
            val g = game ?: return@launch
            val startedAt = System.currentTimeMillis()
            call(g) { api ->
                val resp = api.attack(AttackRequest(target.playerId, army))
                adopt(resp.snapshot, emptySet(), startedAt)
                lastReport = resp.report
                targets = api.targets()
                reports = api.reports()
            }
        } finally {
            busy = false
        }
    }

    private suspend fun call(g: SavedGame, block: suspend (Api) -> Unit): Boolean = try {
        block(Api(g.serverUrl, g.token))
        online = true
        true
    } catch (e: ApiError) {
        online = true
        message = if (e.code == 401) "The server doesn't know this city anymore. Log out to start over." else e.message
        false
    } catch (e: IOException) {
        online = false
        false
    }
}
