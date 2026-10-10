package com.example.antcity.core.duel

import com.example.antcity.core.hex.Hex
import com.example.antcity.core.hex.SplitMix

/** Something that chooses moves for one player. */
fun interface DuelPlayer {
    /** The next move for [player], who must be the one to act in [s]. */
    fun next(s: DuelState, player: Int): DuelMove
}

/** Whose move it is, or null when the match is over. */
fun DuelState.actor(): Int? = when (stage) {
    Stage.BUILD -> players.indexOfFirst { !it.picked }
    Stage.BATTLE -> battle!!.attacker
    Stage.OVER -> null
}

/** Lets [ai] move for [player] until it's someone else's turn. */
fun DuelPlayer.play(s: DuelState, player: Int): DuelState {
    var state = s
    while (state.actor() == player) {
        val r = next(state, player).apply(state, player)
        state = (r as? DuelResult.Ok)?.state ?: error("Bot made an illegal move: $r")
        // A build move hands over even if the other player has already picked.
        if (s.stage == Stage.BUILD) break
    }
    return state
}

/** A simple opponent: greedy placement, scouts the unknown, shoots what it has found. */
object DuelBot : DuelPlayer {

    private fun rng(s: DuelState, player: Int): SplitMix {
        val budget = s.battle?.budgets?.get(player)
        return SplitMix(s.config.seed * 31 + s.phase * 1_000 + s.round * 10 + player + (budget?.let { it.shots * 7 + it.scouts * 13 } ?: 0) * 100_000L)
    }

    override fun next(s: DuelState, player: Int): DuelMove =
        if (s.stage == Stage.BUILD) build(s, player) else battle(s, player)

    private fun build(s: DuelState, player: Int): DuelMove {
        val rng = rng(s, player)
        val board = s.players[player].board
        val offers = Duel.offers(s, player)
        val free = board.freeHexes
        val hall = offers.indexOf(DuelBuilding.TOWN_HALL)
        // Hides its Town Hall somewhere random, usually early so it can build around it.
        if (hall >= 0 && (offers.size == 1 || rng.nextInt(3) == 0)) return DuelMove.Place(hall, free[rng.nextInt(free.size)])
        var best = Triple(-1, free.first(), Int.MIN_VALUE)
        offers.forEachIndexed { i, type ->
            if (type == DuelBuilding.TOWN_HALL) return@forEachIndexed
            for (hex in free) {
                // A little noise so it doesn't always build the same town.
                val score = s.config.rules.gain(board, type, hex) * 4 + rng.nextInt(4)
                if (score > best.third) best = Triple(i, hex, score)
            }
        }
        return DuelMove.Place(best.first, best.second)
    }

    private fun battle(s: DuelState, player: Int): DuelMove {
        val rng = rng(s, player)
        val me = s.players[player]
        val budget = s.battle!!.budgets[player]
        if (me.materials >= s.config.rules.extraActionCost) return DuelMove.Buy(Extra.SHOT)

        val hexes = Hex.disk(s.config.radius)
        fun intel(h: Hex) = me.intel[h]?.takeIf { it.phase == s.phase }
        if (budget.scouts > 0) {
            // The tile that would reveal the most unknown tiles.
            return DuelMove.Scout(hexes.maxBy { h -> (listOf(h) + h.neighbors()).count { it.length <= s.config.radius && intel(it) == null } * 8 + rng.nextInt(8) })
        }
        if (budget.shots > 0) {
            val known = hexes.filter { intel(it)?.let { i -> i.occupied && i.shot != Shot.DESTROYED } == true }
            val target = known.firstOrNull { intel(it)?.type == DuelBuilding.TOWN_HALL }
                ?: known.firstOrNull()
                ?: hexes.filter { intel(it) == null }.let { if (it.isEmpty()) hexes else it }.let { it[rng.nextInt(it.size)] }
            return DuelMove.Shoot(target)
        }
        return DuelMove.EndTurn
    }
}
