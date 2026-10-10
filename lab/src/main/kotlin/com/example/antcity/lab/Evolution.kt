package com.example.antcity.lab

import com.example.antcity.core.duel.DuelBot
import com.example.antcity.core.duel.DuelRules
import java.util.Random

/**
 * Evolves [EvolvedBot]s by self-play: every generation plays a round robin (both seats, shared
 * seeds), the best quarter survives unchanged, and the rest are replaced by mutated crossovers of
 * the better half.
 */
class Evolution(
    private val rules: DuelRules,
    private val populationSize: Int,
    private val seedsPerPair: Int,
    private val threads: Int,
    private val random: Random,
) {
    class Scored(val genome: Genome, val fitness: Double)

    var population: List<Genome> = List(populationSize) { i ->
        // Keep one plain starting genome; spread the rest around it.
        if (i == 0) Genome(Gene.initial()) else mutate(Genome(Gene.initial()), sigma = 1.0, rate = 1.0)
    }
        private set

    fun step(generation: Int, sigma: Double): List<Scored> {
        val scored = roundRobin(population, seeds(generation))
        val sorted = scored.sortedByDescending { it.fitness }
        val elite = sorted.take(populationSize / 4).map { it.genome }
        val parents = sorted.take(populationSize / 2).map { it.genome }
        val children = List(populationSize - elite.size) {
            mutate(crossover(parents[random.nextInt(parents.size)], parents[random.nextInt(parents.size)]), sigma, rate = 0.3)
        }
        population = elite + children
        return sorted
    }

    private fun seeds(generation: Int) = List(seedsPerPair) { generation * 1_000L + it }

    /** Each genome's score per match (1 win, ½ draw) against all others, in both seats. */
    private fun roundRobin(genomes: List<Genome>, seeds: List<Long>): List<Scored> {
        val tasks = buildList {
            for (i in genomes.indices) for (j in genomes.indices) if (i != j) for (seed in seeds) {
                add { Triple(i, j, Arena.play(rules, seed, listOf(EvolvedBot(genomes[i]), EvolvedBot(genomes[j])))) }
            }
        }
        val points = DoubleArray(genomes.size)
        val games = IntArray(genomes.size)
        for ((i, j, winner) in Arena.parallel(threads, tasks)) {
            games[i]++
            games[j]++
            when (winner) {
                0 -> points[i] += 1.0
                1 -> points[j] += 1.0
                else -> { points[i] += 0.5; points[j] += 0.5 }
            }
        }
        return genomes.indices.map { Scored(genomes[it], points[it] / games[it]) }
    }

    /** Win rate (draws count half) of [g] against the simple [DuelBot], over [games] games in both seats. */
    fun vsSimpleBot(g: Genome, games: Int): Double {
        val tasks = List(games) { k ->
            {
                val seat = k % 2
                val players = if (seat == 0) listOf(EvolvedBot(g), DuelBot) else listOf(DuelBot, EvolvedBot(g))
                when (Arena.play(rules, 1_000_000L + k, players)) {
                    seat -> 1.0
                    -1 -> 0.5
                    else -> 0.0
                }
            }
        }
        return Arena.parallel(threads, tasks).average()
    }

    private fun crossover(a: Genome, b: Genome) = Genome(DoubleArray(Gene.SIZE) { if (random.nextBoolean()) a.w[it] else b.w[it] })

    private fun mutate(g: Genome, sigma: Double, rate: Double) =
        Genome(DoubleArray(Gene.SIZE) { if (random.nextDouble() < rate) g.w[it] + random.nextGaussian() * sigma else g.w[it] })
}
