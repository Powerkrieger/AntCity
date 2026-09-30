package com.example.antcity.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.antcity.GameViewModel
import com.example.antcity.core.BattleReport
import com.example.antcity.core.GameState
import com.example.antcity.core.TargetInfo
import com.example.antcity.core.UnitType
import java.text.DateFormat
import java.util.Date

@Composable
fun RaidScreen(vm: GameViewModel, s: GameState) {
    var target by remember { mutableStateOf<TargetInfo?>(null) }
    LaunchedEffect(Unit) { vm.loadTargets() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Cities", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { vm.loadTargets() }) { Text("Refresh") }
            }
            if (s.unitCount == 0) Text("You have no units. Build some in the Hangar.", style = MaterialTheme.typography.bodySmall)
        }
        if (vm.targets.isEmpty()) item { Text("No other cities yet.") }
        items(vm.targets, key = { it.playerId }) { t ->
            Card(Modifier.fillMaxWidth().clickable(enabled = !t.shielded && s.unitCount > 0) { target = t }) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("🏛️ ${t.name}", fontWeight = FontWeight.Bold)
                        Text(if (t.shielded) "🛡️ shielded" else "Town Hall ${t.townHallLevel}")
                    }
                    Text(
                        "🧱 walls ${t.defense.wallHp} hp · 🏰 other ${t.defense.otherHp} hp · 🏹 ${t.defense.damagePerRound}/round",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Loot up to:", style = MaterialTheme.typography.bodySmall)
                        CostRow(t.potentialLoot, null)
                    }
                }
            }
        }
        item { Text("Battle reports", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)) }
        if (vm.reports.isEmpty()) item { Text("No battles yet.") }
        items(vm.reports, key = { it.id }) { r ->
            val mine = r.attackerName == vm.game?.name
            Card(Modifier.fillMaxWidth().clickable { vm.lastReport = r }) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        if (mine) "⚔️ You raided ${r.defenderName}" else "🛡️ ${r.attackerName} raided you",
                        fontWeight = FontWeight.Bold,
                    )
                    Text("${resultLabel(r)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at))}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    target?.let { t ->
        AttackDialog(s, t, onDismiss = { target = null }) { army ->
            vm.attack(t, army)
            target = null
        }
    }
}

private fun resultLabel(r: BattleReport) = when {
    r.outcome.victory -> "Victory"
    r.outcome.wallsBreached && r.outcome.loot.total() > 0 -> "Walls breached"
    else -> "Repelled"
}

@Composable
private fun AttackDialog(s: GameState, t: TargetInfo, onDismiss: () -> Unit, onAttack: (Map<UnitType, Int>) -> Unit) {
    val army = remember { mutableStateMapOf<UnitType, Int>().apply { UnitType.entries.forEach { put(it, s.unitsOf(it)) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raid ${t.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UnitType.entries.filter { s.unitsOf(it) > 0 }.forEach { unit ->
                    val n = army[unit] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${unit.emoji} ${unit.label}", Modifier.weight(1f))
                        OutlinedButton(onClick = { army[unit] = (n - 1).coerceAtLeast(0) }) { Text("−") }
                        Text("$n/${s.unitsOf(unit)}")
                        OutlinedButton(onClick = { army[unit] = (n + 1).coerceAtMost(s.unitsOf(unit)) }) { Text("+") }
                    }
                }
                val damage = army.entries.sumOf { (u, n) -> u.attack * n }
                val carry = army.entries.sumOf { (u, n) -> u.carry * n }
                Text("💥 $damage damage/round · 🎒 carries $carry", style = MaterialTheme.typography.bodySmall)
                Text("Defense: ${t.defense.wallHp + t.defense.otherHp} hp, ${t.defense.damagePerRound} damage/round", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAttack(army.toMap()) }, enabled = army.values.sum() > 0) { Text("Attack!") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun BattleReportDialog(r: BattleReport, onDismiss: () -> Unit) {
    val o = r.outcome
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${r.attackerName} vs ${r.defenderName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(resultLabel(r), style = MaterialTheme.typography.titleMedium)
                Text("${o.rounds} rounds · ${o.structureHpDestroyed}/${o.structureHpTotal} fortification hp destroyed")
                Text("Sent: " + r.army.filterValues { it > 0 }.entries.joinToString { "${it.value}× ${it.key.emoji}" })
                val losses = o.attackerLosses.filterValues { it > 0 }
                Text("Lost: " + if (losses.isEmpty()) "nothing" else losses.entries.joinToString { "${it.value}× ${it.key.emoji}" })
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Loot:")
                    if (o.loot.total() == 0L) Text("none") else CostRow(o.loot, null)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
