package com.example.antcity

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.antcity.core.duel.Duel
import com.example.antcity.core.duel.DuelBot
import com.example.antcity.core.duel.DuelConfig
import com.example.antcity.core.duel.DuelResult
import com.example.antcity.core.duel.DuelState
import com.example.antcity.core.duel.Extra
import com.example.antcity.core.duel.Stage
import com.example.antcity.core.hex.Hex

/**
 * A duel on one phone: pass-and-play between two people, or against [DuelBot] as player 2.
 * In pass-and-play the screen is [covered] whenever the phone has to change hands.
 */
class DuelViewModel : ViewModel() {

    /** Buildings each player lost in the battle that just ended. */
    data class Report(val lost: List<Int>)

    var state by mutableStateOf<DuelState?>(null)
        private set
    var vsBot by mutableStateOf(true)
        private set
    /** The player whose screen is showing. */
    var viewer by mutableIntStateOf(0)
        private set
    var covered by mutableStateOf(false)
        private set
    var report by mutableStateOf<Report?>(null)

    // Building
    var selected by mutableStateOf<Int?>(null)
        private set
    var ghost by mutableStateOf<Hex?>(null)
        private set
    /** A taken tile the player tapped. */
    var invalid by mutableStateOf<Hex?>(null)
        private set

    // Battle
    var mode by mutableStateOf(Extra.SHOT)
    var showOwn by mutableStateOf(false)
    /** The tile acted on last, highlighted on the board. */
    var last by mutableStateOf<Hex?>(null)
        private set

    var message by mutableStateOf<String?>(null)

    fun start(vsBot: Boolean) {
        this.vsBot = vsBot
        viewer = 0
        covered = !vsBot
        report = null
        stage = Stage.BUILD
        clearSelection()
        state = Duel.start(DuelConfig(seed = System.currentTimeMillis()))
    }

    fun quit() {
        state = null
    }

    fun uncover() {
        covered = false
    }

    private fun clearSelection() {
        selected = null
        ghost = null
        invalid = null
        last = null
        showOwn = false
    }

    fun select(offer: Int) {
        selected = if (selected == offer) null else offer
        ghost = null
        invalid = null
    }

    fun tapOwn(hex: Hex) {
        val s = state ?: return
        if (selected == null || s.stage != Stage.BUILD) return
        val free = s.players[viewer].board.isFree(hex)
        ghost = hex.takeIf { free }
        invalid = hex.takeIf { !free }
    }

    fun confirm() {
        val s = state ?: return
        val offer = selected ?: return
        val hex = ghost ?: return
        apply(Duel.place(s, viewer, offer, hex))
    }

    fun tapEnemy(hex: Hex) {
        val s = state ?: return
        if (s.stage != Stage.BATTLE || showOwn) return
        val budget = s.battle!!.budgets[viewer]
        val r = when {
            mode == Extra.SCOUT && budget.scouts > 0 -> Duel.scout(s, viewer, hex)
            mode == Extra.SHOT && budget.shots > 0 -> Duel.shoot(s, viewer, hex)
            else -> return
        }
        last = hex
        apply(r, keepTurn = true)
    }

    fun buy(extra: Extra) {
        val s = state ?: return
        mode = extra
        apply(Duel.buy(s, viewer, extra), keepTurn = true)
    }

    fun endTurn() {
        val s = state ?: return
        apply(Duel.endTurn(s, viewer))
    }

    /** Whose move it is, or null when the match is over. */
    private fun actor(s: DuelState): Int? = when (s.stage) {
        Stage.BUILD -> s.players.indexOfFirst { !it.picked }
        Stage.BATTLE -> s.battle!!.attacker
        Stage.OVER -> null
    }

    private fun apply(r: DuelResult, keepTurn: Boolean = false) {
        when (r) {
            is DuelResult.Rejected -> message = r.reason
            is DuelResult.Ok -> {
                if (keepTurn) state = r.state else advance(r.state)
            }
        }
    }

    /** Lets the bot move until it's a person's turn again, then hands the phone over if needed. */
    private fun advance(next: DuelState) {
        var s = next
        track(s)
        while (vsBot && actor(s) == 1) {
            s = if (s.stage == Stage.BUILD) DuelBot.build(s, 1) else DuelBot.battle(s, 1)
            track(s)
        }
        state = s
        clearSelection()
        val who = actor(s)
        if (who != null && who != viewer) {
            viewer = who
            covered = !vsBot
        }
        if (s.stage == Stage.OVER) covered = false
    }

    private var stage = Stage.BUILD
    private var killsAtBattleStart = List(Duel.PLAYERS) { 0 }

    /** Notices battles starting and ending, for the [report]. */
    private fun track(s: DuelState) {
        if (s.stage == Stage.BATTLE && stage != Stage.BATTLE) killsAtBattleStart = s.players.map { it.kills }
        if (s.stage != Stage.BATTLE && stage == Stage.BATTLE) {
            report = Report(s.players.indices.map { i ->
                val enemy = Duel.opponent(i)
                s.players[enemy].kills - killsAtBattleStart[enemy]
            })
        }
        stage = s.stage
    }
}
