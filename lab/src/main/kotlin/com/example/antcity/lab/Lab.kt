package com.example.antcity.lab

import com.example.antcity.core.duel.DuelBuilding
import com.example.antcity.core.duel.DuelConfig
import com.example.antcity.core.duel.DuelRules
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.abs
import kotlin.system.exitProcess

/** Game setups to compare. Each changes one or two things from the current game. */
val VARIANTS: Map<String, DuelConfig> = linkedMapOf(
    "base" to DuelConfig(),
    "hall-hp-3" to DuelConfig(rules = DuelRules(hallHp = 3)),
    "hall-hp-5" to DuelConfig(rules = DuelRules(hallHp = 5)),
    "hall-no-shot" to DuelConfig(rules = DuelRules(hallShots = 0, hallMaterials = 2)),
    "barracks-needs-house" to DuelConfig(rules = DuelRules(barracksShots = 0, maxHouseBonus = 2)),
    "artillery-needs-crew" to DuelConfig(rules = DuelRules(artilleryShots = 0)),
    "tower-2-scouts" to DuelConfig(rules = DuelRules(towerScouts = 2)),
    "wall-hp-3" to DuelConfig(rules = DuelRules(wallHp = 3)),
    "quarry-2" to DuelConfig(rules = DuelRules(quarryMaterials = 2)),
    "extra-cost-3" to DuelConfig(rules = DuelRules(extraActionCost = 3)),
    // Sweep 1 showed blind shooting beats scouting on the crowded 19-tile board; these spread towns out.
    "radius-3" to DuelConfig(radius = 3),
    "radius-3-hall-5" to DuelConfig(radius = 3, rules = DuelRules(hallHp = 5)),
    "fewer-shots" to DuelConfig(rules = DuelRules(hallShots = 0, hallMaterials = 2, artilleryShots = 0)),
    "radius-3-fewer-shots" to DuelConfig(radius = 3, rules = DuelRules(hallShots = 0, hallMaterials = 2, artilleryShots = 0)),
    "radius-3-tower-2" to DuelConfig(radius = 3, rules = DuelRules(towerScouts = 2)),
    "radius-3-fewer-shots-tower-2" to DuelConfig(radius = 3, rules = DuelRules(hallShots = 0, hallMaterials = 2, artilleryShots = 0, towerScouts = 2)),
    // Shots show their results right away, as before hidden shots.
    "shown-shots" to DuelConfig(rules = DuelRules(hiddenShots = false)),
    "radius-3-shown-shots" to DuelConfig(radius = 3, rules = DuelRules(hiddenShots = false)),
    // Shots only show results on scouted tiles, so scouting is how you aim.
    "blind-shots" to DuelConfig(rules = DuelRules(hiddenShots = false, blindShots = true)),
    "blind-shots-tower-2" to DuelConfig(rules = DuelRules(hiddenShots = false, blindShots = true, towerScouts = 2)),
    "radius-3-blind-shots" to DuelConfig(radius = 3, rules = DuelRules(hiddenShots = false, blindShots = true)),
    "radius-3-blind-shots-tower-2" to DuelConfig(radius = 3, rules = DuelRules(hiddenShots = false, blindShots = true, towerScouts = 2)),
    // Results only after the whole volley, so shooting can't be adjusted mid-turn.
    "salvo" to DuelConfig(rules = DuelRules(hiddenShots = false, salvo = true)),
    "salvo-blind" to DuelConfig(rules = DuelRules(hiddenShots = false, salvo = true, blindShots = true)),
    "radius-3-salvo" to DuelConfig(radius = 3, rules = DuelRules(hiddenShots = false, salvo = true)),
    "radius-3-salvo-blind" to DuelConfig(radius = 3, rules = DuelRules(hiddenShots = false, salvo = true, blindShots = true)),
)

private fun usage(): Nothing {
    System.err.println(
        """
        usage: lab <command> [options]
          evolve <variant>   evolve bots for one rule set and report
          sweep              evolve and report every variant (or --only a,b,c)
          variants           list the rule sets
        options: --gens N (60) --pop N (32) --seeds N (2, per pair and generation)
                 --threads N (16) --eval-seeds N (200) --out DIR (lab-results/<time>) --seed N (1)
        """.trimIndent(),
    )
    exitProcess(2)
}

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val opts = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()
    val rest = args.drop(1).iterator()
    for (a in rest) {
        if (a.startsWith("--")) opts[a.removePrefix("--")] = if (rest.hasNext()) rest.next() else usage() else positional += a
    }
    fun int(name: String, default: Int) = opts[name]?.toInt() ?: default
    val settings = Settings(
        gens = int("gens", 60),
        pop = int("pop", 32),
        seeds = int("seeds", 2),
        threads = int("threads", 16),
        evalSeeds = int("eval-seeds", 200),
        seed = opts["seed"]?.toLong() ?: 1,
    )
    val out = File(opts["out"] ?: "lab-results/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))

    when (args[0]) {
        "variants" -> VARIANTS.forEach { (k, v) -> println("$k: $v") }
        "evolve" -> {
            val name = positional.firstOrNull() ?: "base"
            val config = VARIANTS[name] ?: usage()
            Report(out).write(listOf(runVariant(name, config, settings)))
        }
        "sweep" -> {
            val results = mutableListOf<VariantResult>()
            val only = opts["only"]?.split(',')?.map { it.trim() }
            only?.forEach { if (it !in VARIANTS) usage() }
            for ((name, config) in VARIANTS) {
                if (only != null && name !in only) continue
                results += runVariant(name, config, settings)
                Report(out).write(results) // keep partial results if the run is stopped
            }
        }
        else -> usage()
    }
}

data class Settings(val gens: Int, val pop: Int, val seeds: Int, val threads: Int, val evalSeeds: Int, val seed: Long)

class VariantResult(val name: String, val config: DuelConfig, val summary: Summary, val best: Genome, val vsSimple: Double, val seconds: Long) {
    /**
     * A rough "how healthy is this rule set" number, lower is better: matches should end across all
     * three battles, neither seat should be favoured, no building should be useless or a must-pick,
     * and draws should be rare.
     */
    val balance: Double get() {
        val s = summary
        val phases = abs(s.endedIn(0) - 0.3) + abs(s.endedIn(1) - 0.4) + abs(s.endedIn(2) - 0.3)
        val seat = 2 * abs(s.seat0 - 0.5)
        val rates = DuelBuilding.OFFERABLE.map { s.pickRate(it) }
        val usage = (0.15 - rates.min()).coerceAtLeast(0.0) * 4 + (rates.max() - 0.7).coerceAtLeast(0.0) * 2
        return phases + seat + usage + s.draws
    }
}

fun runVariant(name: String, config: DuelConfig, st: Settings): VariantResult {
    val started = System.currentTimeMillis()
    log("== $name: radius ${config.radius}, ${config.rules}")
    val evo = Evolution(config, st.pop, st.seeds, st.threads, Random(st.seed))
    var last: List<Evolution.Scored> = emptyList()
    for (gen in 0 until st.gens) {
        // Mutation shrinks over time: explore first, then refine.
        val sigma = 0.6 * (1 - gen.toDouble() / st.gens) + 0.1
        last = evo.step(gen, sigma)
        if (gen % 10 == 0 || gen == st.gens - 1) {
            log("  gen $gen: best ${"%.3f".format(last.first().fitness)}, median ${"%.3f".format(last[last.size / 2].fitness)}")
        }
    }
    // Judge the rules by how the strongest bots play them against each other.
    val top = last.take(8).map { it.genome }
    val tasks = buildList {
        for (i in top.indices) for (j in top.indices) if (i != j) for (k in 0 until st.evalSeeds) {
            add {
                MatchStats().also { Arena.play(config, 5_000_000L + k, listOf(EvolvedBot(top[i]), EvolvedBot(top[j])), it) }
            }
        }
    }
    val summary = Summary(Arena.parallel(st.threads, tasks))
    val vsSimple = evo.vsSimpleBot(top.first(), 2_000)
    val seconds = (System.currentTimeMillis() - started) / 1000
    log("  done in ${seconds}s: best vs simple bot ${"%.0f".format(vsSimple * 100)}%")
    return VariantResult(name, config, summary, top.first(), vsSimple, seconds)
}

fun log(msg: String) {
    println("[${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] $msg")
    System.out.flush()
}
