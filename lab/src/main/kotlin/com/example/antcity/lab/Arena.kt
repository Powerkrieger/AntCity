package com.example.antcity.lab

import com.example.antcity.core.duel.Duel
import com.example.antcity.core.duel.DuelBuilding
import com.example.antcity.core.duel.DuelConfig
import com.example.antcity.core.duel.DuelMove
import com.example.antcity.core.duel.DuelPlayer
import com.example.antcity.core.duel.DuelResult
import com.example.antcity.core.duel.DuelRules
import com.example.antcity.core.duel.DuelState
import com.example.antcity.core.duel.Outcome
import com.example.antcity.core.duel.Stage
import com.example.antcity.core.duel.actor
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** What happened in one match, for statistics. */
class MatchStats {
    var winner = -1 // -1 draw
    var byHall = false
    /** Phase (0-based) of the battle that ended the match. */
    var endPhase = 0
    val offered = IntArray(DuelBuilding.entries.size)
    val picked = IntArray(DuelBuilding.entries.size)
    /** Town Hall distance from the centre, per player. */
    val hallRing = IntArray(Duel.PLAYERS)
    /** Build round (0-based) the Town Hall was placed in, per player. */
    val hallRound = IntArray(Duel.PLAYERS)
    var shots = 0
    var scouts = 0
    var battles = 0
    var hits = 0
    var shotsFired = 0
}

object Arena {

    /** Plays one match; [stats] is filled in if given. Returns the winner, or -1 for a draw. */
    fun play(rules: DuelRules, seed: Long, players: List<DuelPlayer>, stats: MatchStats? = null): Int {
        var s = Duel.start(DuelConfig(seed = seed, rules = rules))
        var guard = 0
        while (s.stage != Stage.OVER) {
            val who = s.actor()!!
            if (stats != null && s.stage == Stage.BUILD) {
                Duel.offers(s, who).forEach { stats.offered[it.ordinal]++ }
            }
            val move = players[who].next(s, who)
            val before = s
            s = when (val r = move.apply(s, who)) {
                is DuelResult.Ok -> r.state
                is DuelResult.Rejected -> error("Illegal move $move by player $who: ${r.reason}")
            }
            if (stats != null) record(stats, before, s, who, move)
            check(++guard < 5_000) { "Match didn't end" }
        }
        val o = s.outcome
        stats?.apply {
            endPhase = s.phase
            if (o is Outcome.Win) {
                winner = o.player
                byHall = o.byTownHall
            }
        }
        return (o as? Outcome.Win)?.player ?: -1
    }

    private fun record(st: MatchStats, before: DuelState, after: DuelState, who: Int, move: DuelMove) {
        when (move) {
            is DuelMove.Place -> {
                val type = Duel.offers(before, who)[move.offer]
                st.picked[type.ordinal]++
                if (type == DuelBuilding.TOWN_HALL) {
                    st.hallRing[who] = move.hex.length
                    st.hallRound[who] = before.phase * before.config.roundsPerPhase + before.round
                }
            }
            is DuelMove.Shoot -> {
                st.shotsFired++
                if (before.players[Duel.opponent(who)].board[move.hex] != null) st.hits++
            }
            else -> {}
        }
        if (before.stage != Stage.BATTLE && after.stage == Stage.BATTLE) {
            after.battle!!.budgets.forEach {
                st.shots += it.shots
                st.scouts += it.scouts
            }
            st.battles++
        }
    }

    /** Runs [tasks] on [threads] threads, keeping their order. */
    fun <T> parallel(threads: Int, tasks: List<() -> T>): List<T> {
        val pool = Executors.newFixedThreadPool(threads)
        try {
            return pool.invokeAll(tasks.map { Callable { it() } }).map { it.get() }
        } finally {
            pool.shutdown()
        }
    }
}

/** Totals over many matches. */
class Summary(val matches: List<MatchStats>) {
    val n = matches.size
    fun share(pred: (MatchStats) -> Boolean) = matches.count(pred).toDouble() / n

    val draws get() = share { it.winner == -1 }
    val seat0 get() = matches.count { it.winner == 0 }.toDouble() / matches.count { it.winner != -1 }.coerceAtLeast(1)
    val byHall get() = share { it.winner != -1 && it.byHall }
    fun endedIn(phase: Int) = share { it.endPhase == phase }

    /** How often a type was taken when it was offered. */
    fun pickRate(t: DuelBuilding): Double {
        val offered = matches.sumOf { it.offered[t.ordinal] }
        return if (offered == 0) 0.0 else matches.sumOf { it.picked[t.ordinal] }.toDouble() / offered
    }

    val shotsPerBattle get() = matches.sumOf { it.shots }.toDouble() / matches.sumOf { it.battles * 2 }.coerceAtLeast(1)
    val scoutsPerBattle get() = matches.sumOf { it.scouts }.toDouble() / matches.sumOf { it.battles * 2 }.coerceAtLeast(1)
    val hitRate get() = matches.sumOf { it.hits }.toDouble() / matches.sumOf { it.shotsFired }.coerceAtLeast(1)
    fun hallRingShare(ring: Int) = matches.sumOf { m -> m.hallRing.count { it == ring } }.toDouble() / (2 * n)
    val avgHallRound get() = matches.sumOf { it.hallRound.sum() }.toDouble() / (2 * n)
}
