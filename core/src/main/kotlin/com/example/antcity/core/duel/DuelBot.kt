package com.example.antcity.core.duel

import com.example.antcity.core.hex.Hex
import com.example.antcity.core.hex.SplitMix

/** A simple opponent: greedy placement, scouts the unknown, shoots what it has found. */
object DuelBot {

    private fun rng(s: DuelState, player: Int, salt: Int) =
        SplitMix(s.config.seed * 31 + s.phase * 1_000 + s.round * 10 + player + salt * 100_000L)

    /** Places one building for [player] this round. */
    fun build(s: DuelState, player: Int): DuelState {
        val rng = rng(s, player, 1)
        val board = s.players[player].board
        val offers = Duel.offers(s, player)
        val free = board.freeHexes
        val hall = offers.indexOf(DuelBuilding.TOWN_HALL)
        // Hides its Town Hall somewhere random, usually early so it can build around it.
        if (hall >= 0 && (offers.size == 1 || rng.nextInt(3) == 0)) {
            return (Duel.place(s, player, hall, free[rng.nextInt(free.size)]) as DuelResult.Ok).state
        }
        var best = Triple(-1, free.first(), Int.MIN_VALUE)
        offers.forEachIndexed { i, type ->
            if (type == DuelBuilding.TOWN_HALL) return@forEachIndexed
            for (hex in free) {
                // A little noise so it doesn't always build the same town.
                val score = DuelRules.gain(board, type, hex) * 4 + rng.nextInt(4)
                if (score > best.third) best = Triple(i, hex, score)
            }
        }
        return (Duel.place(s, player, best.first, best.second) as DuelResult.Ok).state
    }

    /** Plays [player]'s whole battle turn. */
    fun battle(s: DuelState, player: Int): DuelState {
        val rng = rng(s, player, 2)
        var state = s
        fun ok(r: DuelResult) = (r as DuelResult.Ok).state

        while (state.players[player].materials >= DuelRules.EXTRA_ACTION_COST) {
            state = ok(Duel.buy(state, player, Extra.SHOT))
        }
        val hexes = Hex.disk(s.config.radius)
        fun intel(h: Hex) = state.players[player].intel[h]?.takeIf { it.phase == state.phase }
        fun budget() = state.battle!!.budgets[player]

        while (budget().scouts > 0) {
            // The tile that would reveal the most unknown tiles.
            val target = hexes.maxBy { h -> (listOf(h) + h.neighbors()).count { it.length <= s.config.radius && intel(it) == null } * 8 + rng.nextInt(8) }
            state = ok(Duel.scout(state, player, target))
        }
        while (budget().shots > 0) {
            val known = hexes.filter { intel(it)?.let { i -> i.occupied && i.shot != Shot.DESTROYED } == true }
            val target = known.firstOrNull { intel(it)?.type == DuelBuilding.TOWN_HALL }
                ?: known.firstOrNull()
                ?: hexes.filter { intel(it) == null }.let { if (it.isEmpty()) hexes else it }.let { it[rng.nextInt(it.size)] }
            state = ok(Duel.shoot(state, player, target))
        }
        return ok(Duel.endTurn(state, player))
    }
}
