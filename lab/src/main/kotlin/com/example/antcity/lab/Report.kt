package com.example.antcity.lab

import com.example.antcity.core.duel.DuelBuilding
import java.io.File

/** Writes a Markdown comparison of variants, plus the best genome of each. */
class Report(private val dir: File) {

    private fun pct(x: Double) = "%.0f%%".format(x * 100)

    fun write(results: List<VariantResult>) {
        dir.mkdirs()
        val types = DuelBuilding.OFFERABLE
        val md = buildString {
            appendLine("# Duel balance lab")
            appendLine()
            appendLine("Bots evolved by self-play per rule set; statistics are from the 8 best bots playing each other.")
            appendLine("Balance: lower is better (spread of when matches end, seat bias, unused or must-pick buildings, draws).")
            appendLine()
            appendLine("| Variant | Balance | Ends in battle 1/2/3 | Town Hall win | Draw | Seat 1 wins | Shots | Scouts | Hit rate | Hall on edge (random) | Hall round | vs simple bot |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|")
            for (r in results.sortedBy { it.balance }) {
                val s = r.summary
                appendLine(
                    "| ${r.name} | ${"%.2f".format(r.balance)} | ${pct(s.endedIn(0))} / ${pct(s.endedIn(1))} / ${pct(s.endedIn(2))} | " +
                        "${pct(s.byHall)} | ${pct(s.draws)} | ${pct(s.seat0)} | ${"%.1f".format(s.shotsPerBattle)} | ${"%.1f".format(s.scoutsPerBattle)} | " +
                        "${pct(s.hitRate)} | ${pct(s.hallOnEdge)} (${pct(s.edgeIfRandom)}) | ${"%.1f".format(s.avgHallRound + 1)} | ${pct(r.vsSimple)} |",
                )
            }
            appendLine()
            appendLine("## How often each building is taken when offered")
            appendLine()
            appendLine("| Variant | " + types.joinToString(" | ") { it.name.lowercase() } + " |")
            appendLine("|---|" + types.joinToString("") { "---|" })
            for (r in results.sortedBy { it.balance }) {
                appendLine("| ${r.name} | " + types.joinToString(" | ") { pct(r.summary.pickRate(it)) } + " |")
            }
            appendLine()
            appendLine("## Rules and best bot per variant")
            for (r in results) {
                appendLine()
                appendLine("### ${r.name} (${r.seconds}s)")
                appendLine()
                appendLine("Board radius ${r.config.radius}, rules: `${r.config.rules}`")
                appendLine()
                appendLine("Best bot: `${r.best}`")
            }
        }
        File(dir, "report.md").writeText(md)
        File(dir, "genomes.tsv").writeText(
            "variant\t" + Gene.entries.joinToString("\t") + "\n" +
                results.joinToString("") { r -> r.name + "\t" + r.best.w.joinToString("\t") { "%.3f".format(it) } + "\n" },
        )
        log("report: ${File(dir, "report.md").absolutePath}")
    }
}
