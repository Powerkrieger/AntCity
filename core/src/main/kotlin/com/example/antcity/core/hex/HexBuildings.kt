package com.example.antcity.core.hex

import kotlinx.serialization.Serializable

enum class Rarity(val weight: Int) {
    COMMON(60),
    UNCOMMON(28),
    RARE(10),
    EPIC(2),
}

enum class Stat(val emoji: String) {
    ATTACK("⚔️"),
    DEFENSE("🛡️"),
    FOOD("🍞"),
}

@Serializable
data class Stats(val attack: Int = 0, val defense: Int = 0, val food: Int = 0) {
    operator fun plus(o: Stats) = Stats(attack + o.attack, defense + o.defense, food + o.food)
    operator fun minus(o: Stats) = Stats(attack - o.attack, defense - o.defense, food - o.food)
    operator fun times(k: Int) = Stats(attack * k, defense * k, food * k)
    operator fun get(stat: Stat) = when (stat) {
        Stat.ATTACK -> attack
        Stat.DEFENSE -> defense
        Stat.FOOD -> food
    }

    /** Integer percentage, rounded down. */
    fun percent(p: Int) = Stats(attack * p / 100, defense * p / 100, food * p / 100)

    /** Non-zero entries, for display. */
    fun entries() = Stat.entries.map { it to get(it) }.filter { it.second != 0 }

    companion object {
        val ZERO = Stats()
    }
}

@Serializable
enum class HexBuilding(
    val emoji: String,
    val label: String,
    /** Null for buildings that are never offered. */
    val rarity: Rarity?,
    /** The stat this building is mainly about (shown on the board and used for the heatmap). */
    val primary: Stat,
    /** Whether the player chooses which way it points. */
    val hasFacing: Boolean = false,
) {
    TOWN_HALL("🏛️", "Town Hall", null, Stat.DEFENSE),
    HOUSE("🏠", "House", Rarity.COMMON, Stat.FOOD),
    FARM("🌾", "Farm", Rarity.COMMON, Stat.FOOD),
    WALL("🧱", "Wall", Rarity.COMMON, Stat.DEFENSE),
    TOWER("🗼", "Tower", Rarity.UNCOMMON, Stat.DEFENSE, hasFacing = true),
    BARRACKS("🪖", "Barracks", Rarity.UNCOMMON, Stat.ATTACK),
    MARKET("🏪", "Market", Rarity.UNCOMMON, Stat.FOOD),
    ARTILLERY("🎯", "Artillery", Rarity.UNCOMMON, Stat.ATTACK, hasFacing = true),
    GATE("⛩️", "Gate", Rarity.RARE, Stat.DEFENSE, hasFacing = true),
    HANGAR("✈️", "Jet Hangar", Rarity.RARE, Stat.ATTACK),
    TEMPLE("🛕", "Temple", Rarity.RARE, Stat.FOOD),
    MONUMENT("🗿", "Monument", Rarity.EPIC, Stat.DEFENSE);

    val rules get() = BuildingRules.of.getValue(this)

    companion object {
        val OFFERABLE = entries.filter { it.rarity != null }
    }
}

@Serializable
data class Placed(
    val type: HexBuilding,
    val hex: Hex,
    val level: Int = 1,
    /** Index into [Hex.DIRECTIONS]; ignored for buildings without facing. */
    val facing: Int = 0,
    /** Day of the run it was placed on. */
    val day: Int = 0,
)
