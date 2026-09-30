package com.example.antcity.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.antcity.GameViewModel
import com.example.antcity.core.BUILDERS
import com.example.antcity.core.Building
import com.example.antcity.core.BuildingType
import com.example.antcity.core.GRID_SIZE
import com.example.antcity.core.GameState

@Composable
fun CityScreen(vm: GameViewModel, s: GameState) {
    var emptyTile by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(
            "Builders: ${s.activeConstructions}/$BUILDERS busy · Town Hall level ${s.townHallLevel}",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(4.dp),
        )
        Column(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF7FA650)),
        ) {
            for (y in 0 until GRID_SIZE) {
                Row(Modifier.weight(1f)) {
                    for (x in 0 until GRID_SIZE) {
                        val b = s.buildingAt(x, y)
                        Tile(b, s.time, Modifier.weight(1f).fillMaxSize()) {
                            if (b == null) emptyTile = x to y else selectedId = b.id
                        }
                    }
                }
            }
        }
        Text(
            "Tap an empty tile to build, or a building to upgrade it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }

    emptyTile?.let { (x, y) ->
        BuildDialog(s, onDismiss = { emptyTile = null }) { type ->
            vm.build(type, x, y)
            emptyTile = null
        }
    }
    selectedId?.let(s::building)?.let { b -> BuildingDialog(vm, s, b) { selectedId = null } }
}

@Composable
private fun Tile(b: Building?, now: Long, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.padding(1.dp).border(0.5.dp, Color(0x33000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (b != null) {
            Text(b.type.emoji, fontSize = 22.sp, modifier = Modifier.padding(bottom = 6.dp))
            if (b.level > 0) {
                Text(
                    "${b.level}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
                )
            }
            b.constructionEndsAt?.let { end ->
                val total = b.type.buildTimeMs(b.level + 1).toFloat()
                LinearProgressIndicator(
                    progress = { (1f - (end - now) / total).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun BuildDialog(s: GameState, onDismiss: () -> Unit, onBuild: (BuildingType) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Build") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BuildingType.entries.filter { it.buildable }.forEach { type ->
                    val count = s.countOf(type)
                    val max = type.maxCount(s.townHallLevel)
                    val cost = type.cost(1)
                    val possible = count < max && s.wholeStock.covers(cost) && s.activeConstructions < BUILDERS
                    Card(
                        Modifier.fillMaxWidth().clickable(enabled = possible) { onBuild(type) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${type.emoji} ${type.label}", fontWeight = FontWeight.Bold)
                                Text("$count/$max · ${formatDuration(type.buildTimeMs(1))}", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(type.description, style = MaterialTheme.typography.bodySmall)
                            CostRow(cost, s.wholeStock)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun BuildingDialog(vm: GameViewModel, s: GameState, b: Building, onDismiss: () -> Unit) {
    var confirmDemolish by remember { mutableStateOf(false) }
    val t = b.type
    val next = b.level + 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${t.emoji} ${t.label} · level ${b.level}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.description)
                stats(t, b.level, s, b).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                when {
                    b.constructionEndsAt != null ->
                        Text("🔨 Level $next ready in ${formatDuration(b.constructionEndsAt!! - s.time)}", fontWeight = FontWeight.Bold)
                    next > t.maxLevel -> Text("Maximum level reached.")
                    t != BuildingType.TOWN_HALL && next > s.townHallLevel -> Text("Upgrade the Town Hall to go higher.")
                    else -> {
                        Text("Upgrade to level $next (${formatDuration(t.buildTimeMs(next))}):", fontWeight = FontWeight.Bold)
                        CostRow(t.cost(next), s.wholeStock)
                    }
                }
            }
        },
        confirmButton = {
            val canUpgrade = b.constructionEndsAt == null && next <= t.maxLevel &&
                (t == BuildingType.TOWN_HALL || next <= s.townHallLevel)
            TextButton(onClick = { vm.upgrade(b.id); onDismiss() }, enabled = canUpgrade) { Text("Upgrade") }
        },
        dismissButton = {
            Row {
                if (t != BuildingType.TOWN_HALL) {
                    TextButton(onClick = { confirmDemolish = true }) { Text("Demolish", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
    if (confirmDemolish) {
        AlertDialog(
            onDismissRequest = { confirmDemolish = false },
            text = { Text("Demolish this ${t.label}? Resources are not refunded.") },
            confirmButton = { TextButton(onClick = { vm.demolish(b.id); confirmDemolish = false; onDismiss() }) { Text("Demolish") } },
            dismissButton = { TextButton(onClick = { confirmDemolish = false }) { Text("Cancel") } },
        )
    }
}

private fun stats(t: BuildingType, level: Int, s: GameState, b: Building): List<String> = buildList {
    if (t.workers > 0) {
        val staffed = b.id in s.staffedBuildingIds()
        add("👷 Needs ${t.workers} workers — ${if (staffed) "staffed" else "⚠️ not enough habitants"}")
    }
    val p = t.productionPerLevel * level.toLong()
    p.entries().filter { it.first > 0 }.forEach { add("Produces ${it.second} ${it.first}/h") }
    if (t.housingPerLevel > 0) add("🏠 Houses ${t.housingPerLevel * level} habitants")
    if (t.storagePerLevel > 0) add("📦 Stores +${t.storagePerLevel * level} of each resource")
    if (t.hpPerLevel > 0) add("❤️ ${t.hpPerLevel * level} hit points in battle")
    if (t.damagePerLevel > 0) add("🏹 ${t.damagePerLevel * level} damage per battle round")
    if (t.unitCapacityPerLevel > 0) add("🪖 Holds ${t.unitCapacityPerLevel * level} units")
}
