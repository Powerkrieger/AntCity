package com.example.antcity.core.hex

import com.example.antcity.core.hex.HexBuilding.ARTILLERY
import com.example.antcity.core.hex.HexBuilding.BARRACKS
import com.example.antcity.core.hex.HexBuilding.FARM
import com.example.antcity.core.hex.HexBuilding.GATE
import com.example.antcity.core.hex.HexBuilding.HANGAR
import com.example.antcity.core.hex.HexBuilding.HOUSE
import com.example.antcity.core.hex.HexBuilding.MARKET
import com.example.antcity.core.hex.HexBuilding.MONUMENT
import com.example.antcity.core.hex.HexBuilding.TEMPLE
import com.example.antcity.core.hex.HexBuilding.TOWER
import com.example.antcity.core.hex.HexBuilding.TOWN_HALL
import com.example.antcity.core.hex.HexBuilding.WALL

/** Where an effect applies, relative to the building that has it. */
enum class Shape(val icon: String) {
    /** The six neighbors. */
    ADJACENT("⬡"),
    /** Anything within range. */
    NEAR("◎"),
    /** Within range, in the 120° cone the building faces. */
    FRONT("▲"),
    /** Within range, in the 120° cone behind the building. */
    BEHIND("▼"),
    /** Within range, on one of the six straight lines. */
    LINES("✳"),
    /** Within range, facing each other (±30° both ways). */
    FACING("⇄"),
    /** Buildings that are mirrored in the town's symmetry. */
    MIRRORED("🪞"),
}

data class Area(val shape: Shape, val range: Int = 1)

sealed class Counts {
    data class Of(val types: Set<HexBuilding>) : Counts()
    /** Empty tiles inside the grid. */
    data object Empty : Counts()
    data object AnyBuilding : Counts()
    /** Distinct building types other than its own. */
    data object DistinctTypes : Counts()
}

/** Flat output added to the building itself: [per] for each counted thing in [area]. */
data class SelfBonus(val counts: Counts, val area: Area, val per: Stats)

/**
 * A percentage change to the output of other buildings ([targets], or all if null) in [area].
 * With [falloff], the effect shrinks linearly with distance (full at 1, 1/range at range).
 */
data class Aura(val targets: Set<HexBuilding>?, val area: Area, val percent: Int, val falloff: Boolean = false)

data class BuildingRules(
    val base: Stats,
    val bonuses: List<SelfBonus> = emptyList(),
    val auras: List<Aura> = emptyList(),
) {
    companion object {
        /** Strength multiplier per building level, in percent. */
        val LEVEL_PERCENT = listOf(100, 150, 200)
        const val MAX_LEVEL = 3

        /** Output bonus for a building whose mirror image is the same type. */
        const val MIRROR_PERCENT = 25

        private fun atk(n: Int) = Stats(attack = n)
        private fun def(n: Int) = Stats(defense = n)
        private fun food(n: Int) = Stats(food = n)
        private fun of(vararg t: HexBuilding) = Counts.Of(t.toSet())
        private fun set(vararg t: HexBuilding) = t.toSet()

        /** All tuning lives here. */
        val of: Map<HexBuilding, BuildingRules> = mapOf(
            TOWN_HALL to BuildingRules(
                base = Stats(attack = 1, defense = 3, food = 2),
                auras = listOf(Aura(null, Area(Shape.ADJACENT), 10)),
            ),
            // Workers and recruits.
            HOUSE to BuildingRules(
                base = food(1),
                auras = listOf(Aura(set(FARM, BARRACKS), Area(Shape.ADJACENT), 25)),
            ),
            FARM to BuildingRules(
                base = food(3),
                bonuses = listOf(SelfBonus(Counts.Empty, Area(Shape.ADJACENT), food(1))),
            ),
            WALL to BuildingRules(
                base = def(2),
                bonuses = listOf(SelfBonus(of(WALL), Area(Shape.ADJACENT), def(1))),
            ),
            // Covers what it looks at; protects the fields behind it.
            TOWER to BuildingRules(
                base = Stats(attack = 1, defense = 3),
                bonuses = listOf(SelfBonus(Counts.AnyBuilding, Area(Shape.FRONT, 3), def(1))),
                auras = listOf(Aura(set(FARM, HOUSE), Area(Shape.BEHIND, 2), 30)),
            ),
            // Noisy neighbours.
            BARRACKS to BuildingRules(
                base = Stats(attack = 4, defense = 1),
                auras = listOf(Aura(set(HOUSE), Area(Shape.ADJACENT), -20)),
            ),
            MARKET to BuildingRules(
                base = food(2),
                bonuses = listOf(SelfBonus(Counts.DistinctTypes, Area(Shape.NEAR, 2), food(1))),
            ),
            // Needs crews from nearby barracks; shakes the fields in front of it.
            ARTILLERY to BuildingRules(
                base = atk(3),
                bonuses = listOf(SelfBonus(of(BARRACKS), Area(Shape.NEAR, 2), atk(2))),
                auras = listOf(Aura(set(FARM), Area(Shape.FRONT, 2), -20)),
            ),
            GATE to BuildingRules(
                base = def(3),
                bonuses = listOf(SelfBonus(of(GATE, TOWER), Area(Shape.FACING, 4), def(4))),
            ),
            // Farmers become pilots: the closer the farm, the worse.
            HANGAR to BuildingRules(
                base = atk(8),
                auras = listOf(Aura(set(FARM), Area(Shape.NEAR, 2), -40, falloff = true)),
            ),
            TEMPLE to BuildingRules(
                base = Stats(defense = 1, food = 1),
                auras = listOf(Aura(null, Area(Shape.LINES, 3), 15)),
            ),
            MONUMENT to BuildingRules(
                base = Stats(attack = 2, defense = 2, food = 2),
                auras = listOf(Aura(null, Area(Shape.MIRRORED), 25)),
            ),
        )
    }
}
