package com.example.antcity.core

import kotlinx.serialization.Serializable

@Serializable
data class BattleOutcome(
    val rounds: Int,
    /** Every fortification (walls, towers, town hall) was destroyed. */
    val victory: Boolean,
    val wallsBreached: Boolean,
    val structureHpDestroyed: Long,
    val structureHpTotal: Long,
    val attackerLosses: Map<UnitType, Int>,
    /** Whole resources taken from the defender. */
    val loot: Res,
)

/** Summary of a city's fortifications as seen by an attacker. */
@Serializable
data class DefenseSummary(val wallHp: Long, val otherHp: Long, val damagePerRound: Long)

object Battle {
    const val LOOT_PERCENT_VICTORY = 30L
    const val LOOT_PERCENT_BREACH = 10L

    private class Structure(val type: BuildingType, var hp: Long, val damage: Long)

    private fun structures(defender: GameState): List<Structure> =
        defender.buildings
            .filter { it.level > 0 && it.type.hpPerLevel > 0 }
            // Walls absorb hits first, then towers, then the town hall.
            .sortedWith(compareBy({ it.type != BuildingType.WALL }, { it.type == BuildingType.TOWN_HALL }, { it.id }))
            .map { Structure(it.type, it.type.hpPerLevel * it.level, it.type.damagePerLevel * it.level) }

    fun defenseOf(defender: GameState): DefenseSummary {
        val s = structures(defender)
        return DefenseSummary(
            wallHp = s.filter { it.type == BuildingType.WALL }.sumOf { it.hp },
            otherHp = s.filter { it.type != BuildingType.WALL }.sumOf { it.hp },
            damagePerRound = s.sumOf { it.damage },
        )
    }

    /** Deterministic battle. [army] must already be validated against the attacker's units. */
    fun resolve(army: Map<UnitType, Int>, defender: GameState): BattleOutcome {
        val alive = UnitType.entries.associateWith { army[it] ?: 0 }.toMutableMap()
        val structures = structures(defender)
        val totalHp = structures.sumOf { it.hp }
        var target = 0
        var woundDamage = 0L // damage carried over onto the front unit
        var rounds = 0

        while (rounds < MAX_BATTLE_ROUNDS && alive.values.sum() > 0 && target < structures.size) {
            rounds++
            // Attackers strike the current structure; overflow moves on to the next one.
            var dmg = alive.entries.sumOf { (type, n) -> type.attack * n }
            while (dmg > 0 && target < structures.size) {
                val s = structures[target]
                val hit = minOf(dmg, s.hp)
                s.hp -= hit
                dmg -= hit
                if (s.hp == 0L) target++
            }
            if (target >= structures.size) break

            // Surviving structures strike back, front-line units first.
            woundDamage += structures.drop(target).sumOf { it.damage }
            for (type in UnitType.entries) {
                val n = alive.getValue(type)
                if (n == 0) continue
                val killed = minOf(n.toLong(), woundDamage / type.hp).toInt()
                alive[type] = n - killed
                woundDamage -= killed * type.hp
                if (alive.getValue(type) > 0) break
            }
            if (alive.values.sum() == 0) woundDamage = 0
        }

        val victory = target >= structures.size
        val wallsBreached = structures.none { it.type == BuildingType.WALL && it.hp > 0 }
        val survivors = alive.values.sum()
        val percent = when {
            survivors == 0 -> 0L
            victory -> LOOT_PERCENT_VICTORY
            wallsBreached -> LOOT_PERCENT_BREACH
            else -> 0L
        }
        val carry = alive.entries.sumOf { (type, n) -> type.carry * n }
        val available = defender.wholeStock.map { it.coerceAtLeast(0) }
        var loot = available.map { it * percent / 100 }
        val lootTotal = loot.total()
        if (lootTotal > carry) loot = loot.map { it * carry / lootTotal }

        return BattleOutcome(
            rounds = rounds,
            victory = victory,
            wallsBreached = wallsBreached,
            structureHpDestroyed = totalHp - structures.sumOf { it.hp },
            structureHpTotal = totalHp,
            attackerLosses = UnitType.entries.associateWith { (army[it] ?: 0) - alive.getValue(it) },
            loot = loot,
        )
    }
}
