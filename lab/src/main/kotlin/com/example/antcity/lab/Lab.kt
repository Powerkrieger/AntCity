package com.example.antcity.lab

import com.example.antcity.core.duel.DuelBuilding
import com.example.antcity.core.duel.DuelRules
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.abs
import kotlin.system.exitProcess

/** Rule sets to compare. Each changes one or two things from the current game. */
val VARIANTS: Map<String, DuelRules> = linkedMapOf(
    "base" to DuelRules(),
    "hall-hp-3" to DuelRules(hallHp = 3),
    "hall-hp-5" to DuelRules(hallHp = 5),
    "hall-no-shot" to DuelRules(hallShots = 0, hallMaterials = 2),
    "barracks-needs-house" to DuelRules(barracksShots = 0, maxHouseBonus = 2),
    "artillery-needs-crew" to DuelRules(artilleryShots = 0),
    "tower-2-scouts" to DuelRules(towerScouts = 2),
    "wall-hp-3" to DuelRules(wallHp = 3),
    "quarry-2" to DuelRules(quarryMaterials = 2),
    "extra-cost-3" to DuelRules(extraActionCost = 3),
)

private fun usage(): Nothing {
    System.err.println(
        """
        usage: lab <command> [options]
          evolve <variant>   evolve bots for one rule set and report
          sweep              evolve and report every variant
          variants           list the rule sets
        options: --gens N (60) --pop N (32) --seeds N (2, per pair and generation)
                 --threads N (16) --eval-seeds N (20) --out DIR (lab-results/<time>) --seed N (1)
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
        evalSeeds = int("eval-seeds", 20),
        seed = opts["seed"]?.toLong() ?: 1,
    )
    val out = File(opts["out"] ?: "lab-results/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))

    when (args[0]) {
        "variants" -> VARIANTS.forEach { (k, v) -> println("$k: $v") }
        "evolve" -> {
            val name = positional.firstOrNull() ?: "base"
            val rules = VARIANTS[name] ?: usage()
            Report(out).write(listOf(runVariant(name, rules, settings)))
        }
        "sweep" -> {
            val results = mutableListOf<VariantResult>()
            for ((name, rules) in VARIANTS) {
                results += runVariant(name, rules, settings)
                Report(out).write(results) // keep partial results if the run is stopped
            }
        }
        else -> usage()
    }
}

data class Settings(val gens: Int, val pop: Int, val seeds: Int, val threads: Int, val evalSeeds: Int, val seed: Long)

class VariantResult(val name: String, val rules: DuelRules, val summary: Summary, val best: Genome, val vsSimple: Double, val seconds: Long) {
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

fun runVariant(name: String, rules: DuelRules, st: Settings): VariantResult {
    val started = System.currentTimeMillis()
    log("== $name: $rules")
    val evo = Evolution(rules, st.pop, st.seeds, st.threads, Random(st.seed))
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
                MatchStats().also { Arena.play(rules, 5_000_000L + k, listOf(EvolvedBot(top[i]), EvolvedBot(top[j])), it) }
            }
        }
    }
    val summary = Summary(Arena.parallel(st.threads, tasks))
    val vsSimple = evo.vsSimpleBot(top.first(), 2_000)
    val seconds = (System.currentTimeMillis() - started) / 1000
    log("  done in ${seconds}s: best vs simple bot ${"%.0f".format(vsSimple * 100)}%")
    return VariantResult(name, rules, summary, top.first(), vsSimple, seconds)
}

fun log(msg: String) {
    println("[${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] $msg")
    System.out.flush()
}
