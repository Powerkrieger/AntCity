package com.example.antcity.lab

import com.example.antcity.core.duel.DuelBot
import com.example.antcity.core.duel.DuelConfig
import com.example.antcity.core.duel.EvolvedBot
import com.example.antcity.core.duel.Gene
import com.example.antcity.core.duel.Genome
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabTest {
    @Test
    fun evolvedBotsPlayLegalMatchesUnderEveryVariant() {
        for ((_, config) in VARIANTS) {
            repeat(3) { seed ->
                val stats = MatchStats()
                Arena.play(config, seed.toLong(), listOf(EvolvedBot(Genome(Gene.initial())), DuelBot), stats)
                // Both players build every round of every phase that was played.
                assertEquals((stats.endPhase + 1) * 5 * 2, stats.picked.sum())
                assertTrue(stats.battles >= 1)
            }
        }
    }

    @Test
    fun evolutionIsReproducible() {
        fun run() = Evolution(DuelConfig(), 6, 1, 2, Random(5)).let { e -> repeat(2) { e.step(it, 0.5) }; e.population.map { it.w.toList() } }
        assertEquals(run(), run())
    }
}
