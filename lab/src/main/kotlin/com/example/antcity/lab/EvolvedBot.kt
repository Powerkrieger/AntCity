package com.example.antcity.lab

import com.example.antcity.core.duel.Building
import com.example.antcity.core.duel.Duel
import com.example.antcity.core.duel.DuelBuilding
import com.example.antcity.core.duel.DuelMove
import com.example.antcity.core.duel.DuelPlayer
import com.example.antcity.core.duel.DuelState
import com.example.antcity.core.duel.Extra
import com.example.antcity.core.duel.Intel
import com.example.antcity.core.duel.Shot
import com.example.antcity.core.duel.Stage
import com.example.antcity.core.hex.Hex
import com.example.antcity.core.hex.SplitMix

/**
 * The weights of an [EvolvedBot]. Each gene has a starting value that plays roughly like the
 * simple bot, so evolution starts from something sensible.
 */
enum class Gene(val init: Double) {
    // Building: how much one more of each counts.
    SHOT(2.0), SCOUT(3.0), MATERIAL(1.0), HP(1.0),
    /** Extra hit points for the own Town Hall. */
    HALL_HP(1.0),
    /** Per own building next to the tile. */
    CLUSTER(0.0),
    /** Distance from the centre, 0 (centre) to 1 (edge). */
    RING(0.0),
    /** Next to the own Town Hall. */
    NEAR_HALL(0.0),
    // A flat liking per building type.
    BIAS_HOUSE(0.0), BIAS_BARRACKS(0.0), BIAS_ARTILLERY(0.0), BIAS_TOWER(0.0), BIAS_QUARRY(0.0), BIAS_WALL(0.0),

    // Town Hall: placed in the first round >= HALL_ROUND (rounded), on the best tile by these.
    HALL_ROUND(1.0), HALL_RING(0.0), HALL_CROWD(0.0),

    // Battle. Materials buy scouts while the enemy hall is unknown if BUY_SCOUT > 0, else shots.
    BUY_SCOUT(-1.0),
    /** If > 0, materials are kept for the last battle. */
    SAVE(-1.0),
    // Where to scout.
    SCOUT_UNKNOWN(1.0), SCOUT_RING(0.0), SCOUT_STALE(0.0),
    // Where to shoot (a known enemy hall always comes first).
    SHOT_KNOWN(5.0), SHOT_DAMAGED(1.0), SHOT_UNKNOWN(0.0), SHOT_RING(0.0), SHOT_NEAR_KNOWN(0.0), SHOT_STALE(0.0),
    /** Firing again at a tile already fired at blindly (result unknown). */
    SHOT_REFIRE(-1.0);

    companion object {
        val SIZE = entries.size
        fun initial() = DoubleArray(SIZE) { entries[it].init }
    }
}

class Genome(val w: DoubleArray) {
    operator fun get(g: Gene) = w[g.ordinal]
    override fun toString() = Gene.entries.joinToString(", ") { "${it.name}=${"%.2f".format(w[it.ordinal])}" }
}

/** A bot whose every choice is a weighted sum of what a player can see. */
class EvolvedBot(val genome: Genome) : DuelPlayer {

    private fun rng(s: DuelState, player: Int): SplitMix {
        val b = s.battle?.budgets?.get(player)
        return SplitMix(s.config.seed * 977 + s.phase * 131 + s.round * 17 + player + ((b?.shots ?: 0) * 7 + (b?.scouts ?: 0) * 3) * 1_000_003L)
    }

    override fun next(s: DuelState, player: Int): DuelMove =
        if (s.stage == Stage.BUILD) build(s, player) else battle(s, player)

    private fun ring(s: DuelState, h: Hex) = h.length.toDouble() / s.config.radius

    private fun build(s: DuelState, player: Int): DuelMove {
        val g = genome
        val rules = s.config.rules
        val board = s.players[player].board
        val offers = Duel.offers(s, player)
        val free = board.freeHexes
        val rng = rng(s, player)
        fun noise() = rng.nextInt(1000) / 1e6
        fun own(h: Hex) = h.neighbors().count { board[it] != null }

        val hall = offers.indexOf(DuelBuilding.TOWN_HALL)
        if (hall >= 0 && (offers.size == 1 || s.round >= g[Gene.HALL_ROUND].toInt())) {
            val at = free.maxBy { g[Gene.HALL_RING] * ring(s, it) + g[Gene.HALL_CROWD] * own(it) + noise() }
            return DuelMove.Place(hall, at)
        }

        val before = rules.production(board)
        val hpBefore = board.buildings.sumOf { rules.maxHp(board, it) }
        val hallHex = board.buildings.firstOrNull { it.type == DuelBuilding.TOWN_HALL }?.hex
        val hallHpBefore = hallHex?.let { rules.maxHp(board, board[it]!!) } ?: 0
        var best: DuelMove.Place? = null
        var bestScore = Double.NEGATIVE_INFINITY
        offers.forEachIndexed { i, type ->
            if (type == DuelBuilding.TOWN_HALL) return@forEachIndexed
            val bias = when (type) {
                DuelBuilding.HOUSE -> g[Gene.BIAS_HOUSE]
                DuelBuilding.BARRACKS -> g[Gene.BIAS_BARRACKS]
                DuelBuilding.ARTILLERY -> g[Gene.BIAS_ARTILLERY]
                DuelBuilding.TOWER -> g[Gene.BIAS_TOWER]
                DuelBuilding.QUARRY -> g[Gene.BIAS_QUARRY]
                DuelBuilding.WALL -> g[Gene.BIAS_WALL]
                DuelBuilding.TOWN_HALL -> 0.0
            }
            for (h in free) {
                val after = board + Building(type, h)
                val y = rules.production(after)
                // Extra hit points beyond the buildings' own, i.e. what walls add.
                val hpGain = after.buildings.sumOf { rules.maxHp(after, it) } - hpBefore - rules.hp(type)
                val hallGain = hallHex?.let { rules.maxHp(after, after[it]!!) - hallHpBefore } ?: 0
                val score = bias +
                    g[Gene.SHOT] * (y.shots - before.shots) +
                    g[Gene.SCOUT] * (y.scouts - before.scouts) +
                    g[Gene.MATERIAL] * (y.materials - before.materials) +
                    g[Gene.HP] * hpGain +
                    g[Gene.HALL_HP] * hallGain +
                    g[Gene.CLUSTER] * own(h) +
                    g[Gene.RING] * ring(s, h) +
                    g[Gene.NEAR_HALL] * (if (hallHex != null && hallHex.distanceTo(h) == 1) 1 else 0) +
                    noise()
                if (score > bestScore) {
                    bestScore = score
                    best = DuelMove.Place(i, h)
                }
            }
        }
        return best!!
    }

    private fun battle(s: DuelState, player: Int): DuelMove {
        val g = genome
        val me = s.players[player]
        val budget = s.battle!!.budgets[player]
        val hexes = Hex.disk(s.config.radius)
        val rng = rng(s, player)
        fun noise() = rng.nextInt(1000) / 1e6
        fun fresh(h: Hex): Intel? = me.intel[h]?.takeIf { it.phase == s.phase }
        fun stale(h: Hex): Intel? = me.intel[h]?.takeIf { it.phase < s.phase }
        fun alive(i: Intel?) = i != null && i.occupied && i.shot != Shot.DESTROYED
        // Blindly fired at: still unknown.
        fun unknown(h: Hex) = fresh(h).let { it == null || it.shot == Shot.FIRED }
        val hallKnown = hexes.firstOrNull { fresh(it)?.type == DuelBuilding.TOWN_HALL && alive(fresh(it)) }

        val canBuy = me.materials >= s.config.rules.extraActionCost
        val saving = g[Gene.SAVE] > 0 && !s.isLastPhase
        if (canBuy && !saving) {
            return DuelMove.Buy(if (g[Gene.BUY_SCOUT] > 0 && hallKnown == null) Extra.SCOUT else Extra.SHOT)
        }

        if (budget.scouts > 0 && hallKnown == null) {
            val target = hexes.maxBy { h ->
                val area = (listOf(h) + h.neighbors()).filter { it.length <= s.config.radius }
                g[Gene.SCOUT_UNKNOWN] * area.count(::unknown) +
                    g[Gene.SCOUT_RING] * ring(s, h) +
                    g[Gene.SCOUT_STALE] * area.count { stale(it)?.occupied == true } +
                    noise()
            }
            return DuelMove.Scout(target)
        }
        if (budget.shots > 0) {
            if (hallKnown != null) return DuelMove.Shoot(hallKnown)
            val target = hexes.maxBy { h ->
                val f = fresh(h)
                when {
                    f != null && f.shot != Shot.FIRED && !alive(f) -> Double.NEGATIVE_INFINITY // known empty, missed or destroyed
                    f != null && f.shot != Shot.FIRED -> g[Gene.SHOT_KNOWN] + (if (f.shot == Shot.HIT) g[Gene.SHOT_DAMAGED] else 0.0)
                    // Fired at already (result unknown), but scouting showed a building there.
                    f != null && f.occupied -> g[Gene.SHOT_KNOWN] + g[Gene.SHOT_REFIRE]
                    else -> g[Gene.SHOT_UNKNOWN] +
                        (if (f?.shot == Shot.FIRED) g[Gene.SHOT_REFIRE] else 0.0) +
                        g[Gene.SHOT_RING] * ring(s, h) +
                        g[Gene.SHOT_NEAR_KNOWN] * h.neighbors().count { alive(fresh(it)) } +
                        g[Gene.SHOT_STALE] * (if (stale(h)?.occupied == true) 1 else 0)
                } + noise()
            }
            return DuelMove.Shoot(target)
        }
        return DuelMove.EndTurn
    }
}
