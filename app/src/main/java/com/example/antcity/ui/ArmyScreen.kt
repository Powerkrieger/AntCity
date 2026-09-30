package com.example.antcity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.antcity.GameViewModel
import com.example.antcity.core.GameState
import com.example.antcity.core.MAX_TRAIN_BATCH
import com.example.antcity.core.UnitType

@Composable
fun ArmyScreen(vm: GameViewModel, s: GameState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.maxHangarLevel == 0) {
            Text("Build a 🛠️ Hangar in your city to produce attack equipment.", style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        Text("Hangar capacity: ${s.unitCount + s.queuedUnits}/${s.unitCapacity}", style = MaterialTheme.typography.titleMedium)

        if (s.training.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("In production", fontWeight = FontWeight.Bold)
                    s.training.forEachIndexed { i, job ->
                        val eta = if (i == 0) " · next in ${formatDuration(job.nextDoneAt - s.time)}" else ""
                        Text("${job.unit.emoji} ${job.remaining}× ${job.unit.label}$eta")
                    }
                }
            }
        }

        UnitType.entries.forEach { unit -> UnitCard(vm, s, unit) }
    }
}

@Composable
private fun UnitCard(vm: GameViewModel, s: GameState, unit: UnitType) {
    var count by remember { mutableIntStateOf(1) }
    val unlocked = s.maxHangarLevel >= unit.hangarLevel
    val free = s.unitCapacity - s.unitCount - s.queuedUnits
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${unit.emoji} ${unit.label}", fontWeight = FontWeight.Bold)
                Text("Owned: ${s.unitsOf(unit)}")
            }
            Text(
                "❤️ ${unit.hp}  💥 ${unit.attack}/round vs buildings  🎒 carries ${unit.carry}  ⏱ ${formatDuration(unit.trainMs)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!unlocked) {
                Text("Requires a level ${unit.hangarLevel} Hangar", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            CostRow(unit.cost * count.toLong(), s.wholeStock)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { count = (count - 1).coerceAtLeast(1) }) { Text("−") }
                Text("$count", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { count = (count + 1).coerceAtMost(MAX_TRAIN_BATCH) }) { Text("+") }
                Button(
                    onClick = { vm.train(unit, count) },
                    enabled = count <= free && s.wholeStock.covers(unit.cost * count.toLong()),
                ) { Text("Build") }
            }
        }
    }
}
