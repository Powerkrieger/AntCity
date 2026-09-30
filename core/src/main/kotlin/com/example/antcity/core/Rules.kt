package com.example.antcity.core

import kotlinx.serialization.Serializable

/** Side length of the square city grid. */
const val GRID_SIZE = 9

/** How many constructions/upgrades may run at the same time. */
const val BUILDERS = 2

/**
 * Resources are stored in sub-units so that production is exact integer math:
 * a building producing `r` resources per hour yields exactly `r * dtMillis` sub-units.
 * This makes the simulation path-independent (advancing 10×1s == advancing 1×10s),
 * which the server relies on when replaying a client's actions.
 */
const val SUB = 3_600_000L

/** A new habitant moves in every [POP_GROWTH_MS] while there is free housing. */
const val POP_GROWTH_MS = 15_000L

/** Tax income per habitant per hour. */
const val GOLD_PER_HABITANT_HOUR = 3L

const val NEW_PLAYER_SHIELD_MS = 24 * 3_600_000L
const val RAIDED_SHIELD_MS = 2 * 3_600_000L

const val MAX_TRAIN_BATCH = 50
const val MAX_BATTLE_ROUNDS = 15

/** A bundle of the four resources. Depending on context these are whole resources or [SUB]-units. */
@Serializable
data class Res(val food: Long = 0, val wood: Long = 0, val stone: Long = 0, val gold: Long = 0) {
    operator fun plus(o: Res) = zip(o) { a, b -> a + b }
    operator fun minus(o: Res) = zip(o) { a, b -> a - b }
    operator fun times(k: Long) = map { it * k }
    fun covers(o: Res) = food >= o.food && wood >= o.wood && stone >= o.stone && gold >= o.gold
    fun total() = food + wood + stone + gold
    inline fun map(f: (Long) -> Long) = Res(f(food), f(wood), f(stone), f(gold))
    inline fun zip(o: Res, f: (Long, Long) -> Long) =
        Res(f(food, o.food), f(wood, o.wood), f(stone, o.stone), f(gold, o.gold))

    fun toSub() = this * SUB
    fun toWhole() = map { it / SUB }

    companion object {
        val ZERO = Res()
    }
}

@Serializable
enum class BuildingType(
    val label: String,
    val emoji: String,
    val description: String,
    val maxLevel: Int,
    val baseCost: Res,
    val baseBuildSeconds: Long,
    /** Habitants needed to operate a production building. */
    val workers: Int = 0,
    val productionPerLevel: Res = Res.ZERO,
    val housingPerLevel: Int = 0,
    val storagePerLevel: Long = 0,
    /** Hit points in battle (fortifications absorb damage). */
    val hpPerLevel: Long = 0,
    /** Damage dealt to attackers per battle round. */
    val damagePerLevel: Long = 0,
    val unitCapacityPerLevel: Int = 0,
    val buildable: Boolean = true,
    private val maxCountBase: Int = 1,
    private val maxCountPerTownHallLevel: Int = 0,
) {
    TOWN_HALL(
        "Town Hall", "🏛️", "Heart of the city. Its level caps all other buildings.",
        maxLevel = 5, baseCost = Res(wood = 300, stone = 300, gold = 100), baseBuildSeconds = 60,
        housingPerLevel = 5, storagePerLevel = 1000, hpPerLevel = 500, damagePerLevel = 10,
        buildable = false,
    ),
    HOUSE(
        "House", "🏠", "Housing for habitants. Habitants work and pay taxes.",
        maxLevel = 5, baseCost = Res(wood = 40, stone = 10), baseBuildSeconds = 15,
        housingPerLevel = 4, maxCountBase = 3, maxCountPerTownHallLevel = 2,
    ),
    FARM(
        "Farm", "🌾", "Collects food.",
        maxLevel = 5, baseCost = Res(wood = 50), baseBuildSeconds = 20,
        workers = 2, productionPerLevel = Res(food = 120), maxCountBase = 2, maxCountPerTownHallLevel = 1,
    ),
    LUMBER_MILL(
        "Lumber Mill", "🪵", "Collects wood.",
        maxLevel = 5, baseCost = Res(food = 30, wood = 40), baseBuildSeconds = 20,
        workers = 2, productionPerLevel = Res(wood = 100), maxCountBase = 2, maxCountPerTownHallLevel = 1,
    ),
    QUARRY(
        "Quarry", "⛏️", "Collects stone.",
        maxLevel = 5, baseCost = Res(food = 20, wood = 80), baseBuildSeconds = 30,
        workers = 3, productionPerLevel = Res(stone = 80), maxCountBase = 1, maxCountPerTownHallLevel = 1,
    ),
    WAREHOUSE(
        "Warehouse", "📦", "Increases storage capacity.",
        maxLevel = 5, baseCost = Res(wood = 100, stone = 50), baseBuildSeconds = 40,
        storagePerLevel = 1500, maxCountBase = 1, maxCountPerTownHallLevel = 1,
    ),
    WALL(
        "Wall", "🧱", "Fortification. Attackers must break it before looting much.",
        maxLevel = 5, baseCost = Res(stone = 60), baseBuildSeconds = 30,
        hpPerLevel = 400, maxCountBase = 2, maxCountPerTownHallLevel = 2,
    ),
    TOWER(
        "Tower", "🗼", "Fortification. Shoots at attackers every round.",
        maxLevel = 5, baseCost = Res(wood = 80, stone = 120, gold = 20), baseBuildSeconds = 60,
        hpPerLevel = 250, damagePerLevel = 25, maxCountBase = 1, maxCountPerTownHallLevel = 1,
    ),
    HANGAR(
        "Hangar", "🛠️", "Builds attack equipment. Higher levels unlock siege weapons.",
        maxLevel = 5, baseCost = Res(wood = 150, stone = 100, gold = 50), baseBuildSeconds = 60,
        unitCapacityPerLevel = 8, maxCountBase = 1, maxCountPerTownHallLevel = 0,
    );

    /** Cost to reach [level] (new construction is level 1). */
    fun cost(level: Int): Res = baseCost * (level.toLong() * level)

    fun buildTimeMs(level: Int): Long = baseBuildSeconds * 1000 * level * level

    fun maxCount(townHallLevel: Int): Int = maxCountBase + maxCountPerTownHallLevel * (townHallLevel - 1)
}

@Serializable
enum class UnitType(
    val label: String,
    val emoji: String,
    val hp: Long,
    /** Damage dealt to structures per battle round. */
    val attack: Long,
    /** Resources this unit carries home after a successful raid. */
    val carry: Long,
    val cost: Res,
    val trainSeconds: Long,
    val hangarLevel: Int,
) {
    // Declaration order is also the order in which units take hits (front line first).
    INFANTRY("Infantry", "🪖", hp = 60, attack = 12, carry = 25, cost = Res(food = 40, gold = 10), trainSeconds = 8, hangarLevel = 1),
    RAM("Battering Ram", "🐏", hp = 250, attack = 45, carry = 0, cost = Res(wood = 120, gold = 30), trainSeconds = 30, hangarLevel = 2),
    CATAPULT("Catapult", "☄️", hp = 90, attack = 70, carry = 0, cost = Res(wood = 150, stone = 80, gold = 60), trainSeconds = 45, hangarLevel = 3);

    val trainMs get() = trainSeconds * 1000
}
