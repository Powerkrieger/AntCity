package com.example.antcity.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.antcity.GameViewModel
import com.example.antcity.core.GameState

private enum class Tab(val label: String, val icon: String) {
    CITY("City", "🏙️"), ARMY("Hangar", "🛠️"), RAID("Raid", "⚔️"), SETTINGS("More", "⚙️")
}

@Composable
fun AntCityApp(vm: GameViewModel) {
    val city = vm.city
    var sandbox by rememberSaveable { mutableStateOf(false) }
    var duel by rememberSaveable { mutableStateOf(false) }
    if (duel) {
        DuelScreen(onClose = { duel = false })
    } else if (sandbox) {
        SandboxScreen(onClose = { sandbox = false })
    } else if (vm.game == null || city == null) {
        SetupScreen(vm, openSandbox = { sandbox = true }, openDuel = { duel = true })
    } else {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            topBar = { ResourceBar(city, vm) },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(t.icon, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Surface(Modifier.padding(padding).fillMaxSize()) {
                when (Tab.entries[tab]) {
                    Tab.CITY -> CityScreen(vm, city)
                    Tab.ARMY -> ArmyScreen(vm, city)
                    Tab.RAID -> RaidScreen(vm, city)
                    Tab.SETTINGS -> SettingsScreen(vm, openSandbox = { sandbox = true }, openDuel = { duel = true })
                }
            }
        }
    }

    vm.message?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.message = null },
            confirmButton = { TextButton(onClick = { vm.message = null }) { Text("OK") } },
            text = { Text(msg) },
        )
    }
    vm.lastReport?.let { report -> BattleReportDialog(report) { vm.lastReport = null } }
}

@Composable
private fun ResourceBar(s: GameState, vm: GameViewModel) {
    val stock = s.wholeStock
    val rate = s.productionPerHour()
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                stock.entries().zip(rate.entries()).forEach { (have, perHour) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${have.second} ${have.first}", fontWeight = FontWeight.Bold)
                        Text("+${perHour.first}/h", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("👥 ${s.population}/${s.housing}   📦 max ${s.storageCap}", style = MaterialTheme.typography.labelMedium)
                val pending = vm.game?.pending?.size ?: 0
                val status = when {
                    !vm.online -> "📴 offline ($pending queued)"
                    pending > 0 -> "⏳ syncing $pending"
                    else -> "✅ synced"
                }
                Text(status, style = MaterialTheme.typography.labelMedium)
            }
            if (s.shieldUntil > vm.now) {
                Text("🛡️ Shielded for ${formatDuration(s.shieldUntil - vm.now)}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: GameViewModel, openSandbox: () -> Unit, openDuel: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("https://") }
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("🏛️ AntCity", style = MaterialTheme.typography.headlineLarge)
        Text("Found your city. Your progress is stored and verified on your server.")
        OutlinedTextField(url, { url = it }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(name, { name = it }, label = { Text("City name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = { vm.register(url, name) },
            enabled = !vm.busy && name.trim().length >= 3 && url.length > 8,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Found city")
        }
        TextButton(onClick = openDuel, modifier = Modifier.fillMaxWidth()) { Text("⚔️ Duel (offline)") }
        TextButton(onClick = openSandbox, modifier = Modifier.fillMaxWidth()) { Text("🔷 Try the layout sandbox (offline)") }
    }
}

@Composable
private fun SettingsScreen(vm: GameViewModel, openSandbox: () -> Unit, openDuel: () -> Unit) {
    val g = vm.game ?: return
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(g.name, style = MaterialTheme.typography.headlineSmall)
        Text("Server: ${g.serverUrl}")
        Text("Server version: ${g.snapshot.version}")
        Text("Actions waiting for upload: ${g.pending.size}")
        Button(onClick = { vm.syncNowAsync() }) { Text("Sync now") }
        Button(onClick = openSandbox) { Text("🔷 Layout sandbox") }
        Button(onClick = openDuel) { Text("⚔️ Duel") }
        TextButton(onClick = { confirmLogout = true }) { Text("Log out on this device") }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Log out?") },
            text = { Text("Your city stays on the server, but this device forgets its key. Without the key you can't log back in.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; vm.logout() }) { Text("Log out") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}
