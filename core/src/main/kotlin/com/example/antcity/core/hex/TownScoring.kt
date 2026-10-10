package com.example.antcity.core.hex

/** One applied self bonus, with the tiles it counted (for drawing). */
data class BonusPart(val bonus: SelfBonus, val counted: List<Hex>) {
    val amount get() = bonus.per * counted.size
}

/** A percentage change from another building ([from]) or from symmetry ([from] == null). */
data class Influence(val from: Hex?, val source: HexBuilding?, val percent: Int)

data class BuildingResult(
    val placed: Placed,
    val bonuses: List<BonusPart>,
    /** Base + bonuses, before percentages and level. */
    val raw: Stats,
    val influences: List<Influence>,
    val mirrored: Boolean,
    val output: Stats,
)

data class TownResult(
    val buildings: List<BuildingResult>,
    /** Index into [Mirrors.ALL] of the best mirror line, or null if nothing is mirrored. */
    val mirrorAxis: Int?,
    val mirroredCount: Int,
    val totals: Stats,
) {
    fun at(hex: Hex) = buildings.firstOrNull { it.placed.hex == hex }
}

object TownScoring {

    fun score(radius: Int, buildings: List<Placed>): TownResult {
        val byHex = buildings.associateBy { it.hex }
        val (axis, mirrored) = bestMirror(buildings, byHex)

        val results = buildings.map { b ->
            val rules = b.type.rules
            val bonuses = rules.bonuses.map { BonusPart(it, counted(b, it, byHex, radius)) }.filter { it.counted.isNotEmpty() }
            val raw = bonuses.fold(rules.base) { acc, p -> acc + p.amount }

            val influences = buildList {
                for (source in buildings) {
                    if (source === b) continue
                    for (aura in source.type.rules.auras) {
                        if (aura.targets != null && b.type !in aura.targets) continue
                        if (!contains(source, aura.area, b.hex, b, mirrored)) continue
                        val percent = if (aura.falloff) {
                            val d = source.hex.distanceTo(b.hex)
                            aura.percent * (aura.area.range - d + 1) / aura.area.range
                        } else aura.percent
                        add(Influence(source.hex, source.type, percent))
                    }
                }
                if (b.hex in mirrored) add(Influence(null, null, BuildingRules.MIRROR_PERCENT))
            }
            val multiplier = (100 + influences.sumOf { it.percent }).coerceAtLeast(0)
            val levelPercent = BuildingRules.LEVEL_PERCENT[(b.level - 1).coerceIn(0, BuildingRules.LEVEL_PERCENT.lastIndex)]
            BuildingResult(b, bonuses, raw, influences, b.hex in mirrored, raw.percent(multiplier).percent(levelPercent))
        }
        return TownResult(results, axis, mirrored.size, results.fold(Stats.ZERO) { acc, r -> acc + r.output })
    }

    /** Whether [hex] (holding [target], if any) lies in [area] of building [from]. */
    fun contains(from: Placed, area: Area, hex: Hex, target: Placed?, mirrored: Set<Hex>): Boolean {
        val d = from.hex.distanceTo(hex)
        if (d == 0) return false
        return when (area.shape) {
            Shape.ADJACENT -> d == 1
            Shape.NEAR -> d <= area.range
            Shape.FRONT -> d <= area.range && Hex.inCone(from.hex, from.facing, hex, HalfAngle.DEG_60)
            Shape.BEHIND -> d <= area.range && Hex.inCone(from.hex, (from.facing + 3) % 6, hex, HalfAngle.DEG_60)
            Shape.LINES -> d <= area.range && hex.isAlignedWith(from.hex)
            Shape.FACING -> d <= area.range && target != null && target.type.hasFacing &&
                Hex.inCone(from.hex, from.facing, hex, HalfAngle.DEG_30) &&
                Hex.inCone(hex, target.facing, from.hex, HalfAngle.DEG_30)
            Shape.MIRRORED -> hex in mirrored
        }
    }

    private fun counted(b: Placed, bonus: SelfBonus, byHex: Map<Hex, Placed>, radius: Int): List<Hex> {
        val area = bonus.area
        val range = if (area.shape == Shape.ADJACENT) 1 else area.range
        val candidates = Hex.disk(range).map { b.hex + it }.filter { it.length <= radius }
        val inArea = candidates.filter { contains(b, area, it, byHex[it], emptySet()) }
        return when (val c = bonus.counts) {
            Counts.Empty -> inArea.filter { it !in byHex }
            Counts.AnyBuilding -> inArea.filter { it in byHex }
            is Counts.Of -> inArea.filter { byHex[it]?.type in c.types }
            Counts.DistinctTypes -> inArea.mapNotNull { byHex[it] }
                .filter { it.type != b.type }
                .distinctBy { it.type }
                .map { it.hex }
        }
    }

    /**
     * The mirror line with the most buildings whose mirror image is a building of the same type.
     * Buildings lying on the line don't count: they're their own image.
     */
    private fun bestMirror(buildings: List<Placed>, byHex: Map<Hex, Placed>): Pair<Int?, Set<Hex>> {
        var bestAxis: Int? = null
        var best = emptySet<Hex>()
        Mirrors.ALL.forEachIndexed { axis, mirror ->
            val matched = buildings.filter { b ->
                val image = mirror(b.hex)
                image != b.hex && byHex[image]?.type == b.type
            }.map { it.hex }.toSet()
            if (matched.size > best.size) {
                best = matched
                bestAxis = axis
            }
        }
        return bestAxis to best
    }
}
