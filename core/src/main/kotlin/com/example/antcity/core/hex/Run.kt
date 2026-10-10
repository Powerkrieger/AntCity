package com.example.antcity.core.hex

import kotlinx.serialization.Serializable

/** Deterministic, platform-independent random numbers (SplitMix64). */
class SplitMix(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int = ((nextLong() ushr 1) % bound).toInt()
}

@Serializable
data class Offer(val type: HexBuilding, val level: Int)

@Serializable
data class RunConfig(
    val radius: Int = 3,
    val days: Int = 7,
    val picksPerDay: Int = 2,
    /** In a real season this comes from the server, so everyone gets the same offers. */
    val seed: Long = 1,
)

@Serializable
data class RunState(
    val config: RunConfig,
    val day: Int = 0,
    /** Which of today's picks is next. */
    val pick: Int = 0,
    val buildings: List<Placed>,
    /** A building assigned for a missed pick; at most one can wait. */
    val backlog: Offer? = null,
    /** Buildings lost because the backlog was already full. */
    val lost: Int = 0,
) {
    val finished get() = day >= config.days
    val complete get() = finished && backlog == null
    fun isFree(hex: Hex) = hex.length <= config.radius && buildings.none { it.hex == hex }
}

sealed class Choice {
    data class Offered(val index: Int) : Choice()
    data object Backlog : Choice()
}

sealed class PlaceResult {
    data class Ok(val state: RunState) : PlaceResult()
    data class Rejected(val reason: String) : PlaceResult()
}

object Runs {
    const val OFFERS_PER_PICK = 3

    fun start(config: RunConfig) = RunState(config, buildings = listOf(Placed(HexBuilding.TOWN_HALL, Hex.ORIGIN)))

    /**
     * The offers for one pick. Depends only on the seed, day and pick, so every player of a season
     * sees the same choice; [levels] are the player's unlocked building levels.
     */
    fun offers(config: RunConfig, day: Int, pick: Int, levels: Map<HexBuilding, Int>): List<Offer> {
        val rng = SplitMix(config.seed xor (day.toLong() shl 32) xor (pick.toLong() shl 16))
        val types = mutableListOf<HexBuilding>()
        while (types.size < OFFERS_PER_PICK) {
            val t = draw(rng)
            if (t !in types) types += t
        }
        return types.map { Offer(it, levels[it] ?: 1) }
    }

    fun currentOffers(s: RunState, levels: Map<HexBuilding, Int>): List<Offer> =
        if (s.finished) emptyList() else offers(s.config, s.day, s.pick, levels)

    private fun draw(rng: SplitMix): HexBuilding {
        var roll = rng.nextInt(Rarity.entries.sumOf { it.weight })
        val rarity = Rarity.entries.first { roll -= it.weight; roll < 0 }
        val pool = HexBuilding.OFFERABLE.filter { it.rarity == rarity }
        return pool[rng.nextInt(pool.size)]
    }

    fun place(s: RunState, levels: Map<HexBuilding, Int>, choice: Choice, hex: Hex, facing: Int): PlaceResult {
        if (!s.isFree(hex)) return PlaceResult.Rejected("That tile isn't free")
        if (facing !in Hex.DIRECTIONS.indices) return PlaceResult.Rejected("Invalid facing")
        return when (choice) {
            Choice.Backlog -> {
                val offer = s.backlog ?: return PlaceResult.Rejected("No building waiting")
                PlaceResult.Ok(s.copy(buildings = s.buildings + placed(offer, hex, facing, s.day), backlog = null))
            }
            is Choice.Offered -> {
                if (s.finished) return PlaceResult.Rejected("The week is over")
                val offer = currentOffers(s, levels).getOrNull(choice.index) ?: return PlaceResult.Rejected("No such offer")
                PlaceResult.Ok(advance(s.copy(buildings = s.buildings + placed(offer, hex, facing, s.day))))
            }
        }
    }

    /**
     * The player missed the rest of today. Each missed pick is assigned (no choice) to the backlog;
     * if the backlog is already full, that building is lost.
     */
    fun skipDay(s: RunState, levels: Map<HexBuilding, Int>): RunState {
        if (s.finished) return s
        var state = s
        for (p in s.pick until s.config.picksPerDay) {
            val assigned = offers(s.config, s.day, p, levels).first()
            state = if (state.backlog == null) state.copy(backlog = assigned) else state.copy(lost = state.lost + 1)
        }
        return state.copy(day = s.day + 1, pick = 0)
    }

    private fun placed(offer: Offer, hex: Hex, facing: Int, day: Int) =
        Placed(offer.type, hex, offer.level, if (offer.type.hasFacing) facing else 0, day)

    private fun advance(s: RunState) =
        if (s.pick + 1 < s.config.picksPerDay) s.copy(pick = s.pick + 1) else s.copy(day = s.day + 1, pick = 0)
}
