package com.example.antcity

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.example.antcity.core.GameJson
import com.example.antcity.core.hex.Choice
import com.example.antcity.core.hex.Hex
import com.example.antcity.core.hex.HexBuilding
import com.example.antcity.core.hex.Offer
import com.example.antcity.core.hex.PlaceResult
import com.example.antcity.core.hex.Placed
import com.example.antcity.core.hex.RunConfig
import com.example.antcity.core.hex.RunState
import com.example.antcity.core.hex.Runs
import com.example.antcity.core.hex.TownResult
import com.example.antcity.core.hex.TownScoring
import kotlinx.serialization.Serializable
import java.io.File

/** Offline playground for tuning the hex layout scoring. */
class SandboxViewModel(app: Application) : AndroidViewModel(app) {

    @Serializable
    private data class Saved(val run: RunState, val levels: Map<HexBuilding, Int>, val heatmap: Boolean)

    private val file = File(app.filesDir, "sandbox.json")

    var run by mutableStateOf(Runs.start(RunConfig(seed = System.currentTimeMillis())))
        private set
    /** Simulated account progression: the level new copies of each type arrive at. */
    var levels by mutableStateOf<Map<HexBuilding, Int>>(emptyMap())
        private set
    var heatmap by mutableStateOf(false)
        private set

    var choice by mutableStateOf<Choice?>(null)
        private set
    var ghost by mutableStateOf<Hex?>(null)
        private set
    var facing by mutableIntStateOf(0)
        private set
    var inspected by mutableStateOf<Hex?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private var history = listOf<RunState>()

    init {
        runCatching { GameJson.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()?.let {
            run = it.run
            levels = it.levels
            heatmap = it.heatmap
        }
    }

    private fun persist() {
        file.writeText(GameJson.encodeToString(Saved.serializer(), Saved(run, levels, heatmap)))
    }

    val offers: List<Offer> get() = Runs.currentOffers(run, levels)

    val selectedOffer: Offer?
        get() = when (val c = choice) {
            null -> null
            Choice.Backlog -> run.backlog
            is Choice.Offered -> offers.getOrNull(c.index)
        }

    fun score(buildings: List<Placed> = run.buildings): TownResult = TownScoring.score(run.config.radius, buildings)

    /** The town as it would be with the selected building placed at [hex]. */
    fun withSelectedAt(hex: Hex): List<Placed>? {
        val offer = selectedOffer ?: return null
        if (!run.isFree(hex)) return null
        return run.buildings + Placed(offer.type, hex, offer.level, if (offer.type.hasFacing) facing else 0, run.day)
    }

    /** Per free tile: change of the selected building's main stat, and whether any other stat would drop. */
    data class Heat(val gain: Int, val hurts: Boolean)

    fun heat(): Map<Hex, Heat> {
        val offer = selectedOffer
        if (!heatmap || offer == null) return emptyMap()
        val now = score().totals
        return Hex.disk(run.config.radius).filter(run::isFree).associateWith { hex ->
            val delta = score(withSelectedAt(hex)!!).totals - now
            Heat(delta[offer.type.primary], com.example.antcity.core.hex.Stat.entries.any { delta[it] < 0 })
        }
    }

    fun select(c: Choice) {
        choice = if (choice == c) null else c
        ghost = null
        inspected = null
    }

    /** A card is being dragged onto the map. */
    fun beginDrag(c: Choice) {
        choice = c
        ghost = null
        inspected = null
    }

    /** The dragged building is over [hex]; it only lands on free tiles. */
    fun moveGhost(hex: Hex?) {
        if (choice == null) return
        ghost = hex?.takeIf(run::isFree)
    }

    fun tap(hex: Hex) {
        if (hex.length > run.config.radius) return
        if (choice != null) {
            // While placing, only free tiles react.
            if (run.isFree(hex)) {
                ghost = hex
                inspected = null
            }
        } else {
            inspected = if (inspected == hex) null else hex
        }
    }

    fun rotate() {
        facing = (facing + 1) % 6
    }

    fun confirm() {
        val c = choice ?: return
        val hex = ghost ?: return
        when (val r = Runs.place(run, levels, c, hex, facing)) {
            is PlaceResult.Rejected -> message = r.reason
            is PlaceResult.Ok -> {
                history = history + run
                run = r.state
                choice = null
                ghost = null
                inspected = hex
                persist()
            }
        }
    }

    fun cancel() {
        choice = null
        ghost = null
    }

    fun skipDay() {
        history = history + run
        val before = run
        run = Runs.skipDay(run, levels)
        message = if (run.lost > before.lost) "Day missed · a building was lost" else "Day missed · ${run.backlog?.type?.label} saved for later"
        cancel()
        persist()
    }

    fun undo() {
        history.lastOrNull()?.let {
            run = it
            history = history.dropLast(1)
            cancel()
            inspected = null
            persist()
        }
    }

    val canUndo get() = history.isNotEmpty()

    fun newRun(config: RunConfig) {
        run = Runs.start(config)
        history = emptyList()
        cancel()
        inspected = null
        persist()
    }

    fun setLevel(type: HexBuilding, level: Int) {
        levels = levels + (type to level)
        persist()
    }

    fun toggleHeatmap() {
        heatmap = !heatmap
        persist()
    }
}
