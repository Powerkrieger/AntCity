package com.example.antcity.core

import kotlinx.serialization.Serializable
import java.security.MessageDigest

@Serializable
data class Building(
    val id: Int,
    val type: BuildingType,
    val x: Int,
    val y: Int,
    /** Completed level; 0 while the initial construction is running. */
    val level: Int,
    /** When non-null, construction to `level + 1` finishes at this time. */
    val constructionEndsAt: Long? = null,
) {
    val underConstruction get() = constructionEndsAt != null
}

@Serializable
data class TrainingJob(
    val unit: UnitType,
    val remaining: Int,
    /** Completion time of the next unit; only meaningful for the head of the queue. */
    val nextDoneAt: Long,
)

@Serializable
data class GameState(
    /** Simulation time (epoch millis, server clock). */
    val time: Long,
    /** Stock in [SUB]-units. */
    val stock: Res,
    val population: Int,
    /** Time from which the next habitant's [POP_GROWTH_MS] countdown runs. */
    val popAnchor: Long,
    val buildings: List<Building>,
    val nextBuildingId: Int,
    val units: Map<UnitType, Int>,
    val training: List<TrainingJob>,
    val shieldUntil: Long,
) {
    val townHallLevel get() = buildings.first { it.type == BuildingType.TOWN_HALL }.level.coerceAtLeast(1)

    val housing get() = buildings.sumOf { it.type.housingPerLevel * it.level }

    /** Storage cap per resource, in whole resources. */
    val storageCap get() = buildings.sumOf { it.type.storagePerLevel * it.level }

    val unitCapacity get() = buildings.sumOf { it.type.unitCapacityPerLevel * it.level }

    val unitCount get() = units.values.sum()

    val queuedUnits get() = training.sumOf { it.remaining }

    val maxHangarLevel get() = buildings.filter { it.type == BuildingType.HANGAR }.maxOfOrNull { it.level } ?: 0

    val activeConstructions get() = buildings.count { it.underConstruction }

    val wholeStock get() = stock.toWhole()

    fun unitsOf(type: UnitType) = units[type] ?: 0

    fun buildingAt(x: Int, y: Int) = buildings.firstOrNull { it.x == x && it.y == y }

    fun building(id: Int) = buildings.firstOrNull { it.id == id }

    fun countOf(type: BuildingType) = buildings.count { it.type == type }

    /**
     * Which production buildings are staffed. Habitants are assigned to buildings in id order;
     * a building only works if it gets its full crew.
     */
    fun staffedBuildingIds(): Set<Int> {
        var free = population
        val staffed = mutableSetOf<Int>()
        for (b in buildings.sortedBy { it.id }) {
            if (b.type.workers == 0 || b.level == 0) continue
            if (free >= b.type.workers) {
                free -= b.type.workers
                staffed += b.id
            }
        }
        return staffed
    }

    /** Production in whole resources per hour. */
    fun productionPerHour(): Res {
        val staffed = staffedBuildingIds()
        val fromBuildings = buildings
            .filter { it.id in staffed }
            .fold(Res.ZERO) { acc, b -> acc + b.type.productionPerLevel * b.level.toLong() }
        return fromBuildings + Res(gold = population * GOLD_PER_HABITANT_HOUR)
    }

    /** Stable hash of the state; client and server compare these to detect divergence. */
    fun hash(): String {
        val canonical = copy(units = UnitType.entries.associateWith { unitsOf(it) })
        val bytes = GameJson.encodeToString(serializer(), canonical).toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun genesis(createdAt: Long): GameState {
            val center = GRID_SIZE / 2
            return GameState(
                time = createdAt,
                stock = Res(food = 300, wood = 500, stone = 300, gold = 100).toSub(),
                population = 0,
                popAnchor = createdAt,
                buildings = listOf(Building(1, BuildingType.TOWN_HALL, center, center, level = 1)),
                nextBuildingId = 2,
                units = UnitType.entries.associateWith { 0 },
                training = emptyList(),
                shieldUntil = createdAt + NEW_PLAYER_SHIELD_MS,
            )
        }
    }
}
