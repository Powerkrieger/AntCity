package com.example.antcity.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.example.antcity.net.BluetoothTransport
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.antcity.DuelViewModel
import com.example.antcity.core.duel.Board
import com.example.antcity.core.duel.Building
import com.example.antcity.core.duel.Duel
import com.example.antcity.core.duel.DuelBuilding
import com.example.antcity.core.duel.DuelRules
import com.example.antcity.core.duel.DuelState
import com.example.antcity.core.duel.Extra
import com.example.antcity.core.duel.Intel
import com.example.antcity.core.duel.Outcome
import com.example.antcity.core.duel.Shot
import com.example.antcity.core.duel.Stage
import com.example.antcity.core.duel.Yield
import com.example.antcity.core.hex.Hex

private val PLAYER_COLORS = listOf(Color(0xFF1E88E5), Color(0xFFE53935))
private val FIELD = Color(0xFF7CB342)
private val BATTLEFIELD = Color(0xFF546E7A)
private val UP = Color(0xFF2E7D32)
private val DOWN = Color(0xFFC62828)
private val GOLD = Color(0xFFFFB300)

private const val SHOTS = "icon.attack"
private const val SCOUTS = "icon.recon"
private const val MATERIALS = "icon.materials"

private fun DuelBuilding.label() = when (this) {
    DuelBuilding.TOWN_HALL -> "Town Hall"
    DuelBuilding.HOUSE -> "House"
    DuelBuilding.BARRACKS -> "Barracks"
    DuelBuilding.ARTILLERY -> "Artillery"
    DuelBuilding.TOWER -> "Watchtower"
    DuelBuilding.QUARRY -> "Quarry"
    DuelBuilding.WALL -> "Wall"
}

/** One short line, shown only for the selected building. */
private fun DuelBuilding.blurb() = when (this) {
    DuelBuilding.TOWN_HALL -> "Hide it well: if it falls, you lose"
    DuelBuilding.HOUSE -> "Workers for barracks and quarries next to it"
    DuelBuilding.BARRACKS -> "A shot, two with a house next to it"
    DuelBuilding.ARTILLERY -> "A shot, more with barracks next to it"
    DuelBuilding.TOWER -> "A scout to find enemy buildings"
    DuelBuilding.QUARRY -> "Materials, more with a house next to it"
    DuelBuilding.WALL -> "Tough, and toughens its neighbours"
}

private fun DuelBuilding.emoji() = when (this) {
    DuelBuilding.TOWN_HALL -> "🏛️"
    DuelBuilding.HOUSE -> "🏠"
    DuelBuilding.BARRACKS -> "🪖"
    DuelBuilding.ARTILLERY -> "🎯"
    DuelBuilding.TOWER -> "🗼"
    DuelBuilding.QUARRY -> "⛏️"
    DuelBuilding.WALL -> "🧱"
}

@Composable
fun DuelScreen(onClose: () -> Unit, vm: DuelViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }
    val s = vm.state
    val lobby = vm.lobby
    BackHandler {
        when {
            lobby != null -> vm.closeLobby()
            s == null -> onClose()
            else -> vm.quit()
        }
    }
    when {
        lobby != null -> LobbyScreen(vm, lobby)
        s == null -> DuelMenu(onClose, vm::start, vm::openLobby)
        vm.covered -> HandOver(vm.viewer) { vm.uncover() }
        else -> Surface(Modifier.fillMaxSize(), color = if (s.stage == Stage.BATTLE && !vm.showOwn) BATTLEFIELD else FIELD) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                TopBar(s, vm)
                when (s.stage) {
                    Stage.BUILD -> BuildView(s, vm)
                    Stage.BATTLE -> BattleView(s, vm)
                    Stage.OVER -> OverView(s, vm)
                }
            }
        }
    }
    vm.report?.let { r -> ReportDialog(r, names(vm)) { vm.report = null } }
    vm.notice?.let { text ->
        AlertDialog(
            onDismissRequest = { vm.notice = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { vm.notice = null }) { Text("OK") } },
        )
    }
}

/** How each player is called on this device. */
private fun names(vm: DuelViewModel): List<String> = when (vm.mode) {
    DuelViewModel.Mode.BOT -> listOf("You", "The bot")
    DuelViewModel.Mode.PASS -> listOf("Player 1", "Player 2")
    DuelViewModel.Mode.ONLINE -> List(Duel.PLAYERS) { if (it == vm.viewer) "You" else vm.opponent ?: "Opponent" }
}

@Composable
private fun DuelMenu(onClose: () -> Unit, start: (DuelViewModel.Mode) -> Unit, nearby: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpriteIcon(DuelBuilding.TOWN_HALL.spriteKey, "🏛️", 120.dp)
        Text("Duel", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Button(onClick = nearby, Modifier.fillMaxWidth().height(64.dp)) { Text("Two phones, nearby", fontSize = 18.sp) }
        Button(onClick = { start(DuelViewModel.Mode.PASS) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Two players, one phone", fontSize = 18.sp) }
        Button(onClick = { start(DuelViewModel.Mode.BOT) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Against the bot", fontSize = 18.sp) }
        TextButton(onClick = onClose) { Text("Back") }
    }
}

@Composable
private fun HandOver(player: Int, onReady: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(PLAYER_COLORS[player]).clickable(onClick = onReady),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Player ${player + 1}", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            Text("Tap when only you can see the screen", color = Color.White.copy(alpha = 0.85f))
        }
    }
}

// --- Top bar ----------------------------------------------------------------------------------

@Composable
private fun TopBar(s: DuelState, vm: DuelViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = vm::quit) { Text("←", fontSize = 24.sp, color = Color.White) }
        Spacer(Modifier.weight(1f))
        Progress(s)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(36.dp).background(PLAYER_COLORS[vm.viewer], CircleShape).border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(if (vm.mode == DuelViewModel.Mode.PASS) "${vm.viewer + 1}" else "★", color = Color.White, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
    }
}

/** A dot per build round and a sword per battle; the current one is gold. */
@Composable
private fun Progress(s: DuelState) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (p in 0 until s.config.phases) {
            for (r in 0 until s.config.roundsPerPhase) {
                val done = p < s.phase || (p == s.phase && (r < s.round || s.stage != Stage.BUILD))
                val now = p == s.phase && r == s.round && s.stage == Stage.BUILD
                val m = Modifier.size(if (now) 10.dp else 7.dp)
                Box(
                    when {
                        now -> m.background(GOLD, CircleShape)
                        done -> m.background(Color.White, CircleShape)
                        else -> m.border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    },
                )
            }
            val fighting = p == s.phase && s.stage == Stage.BATTLE
            SpriteIcon(SHOTS, "⚔️", if (fighting) 22.dp else 16.dp, Modifier.alpha(if (p < s.phase || fighting) 1f else 0.5f))
        }
    }
}

@Composable
private fun Pill(icon: String, fallback: String, value: Int, before: Int = value) {
    Row(
        Modifier.background(Color(0xCC1B1B1B), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpriteIcon(icon, fallback, 20.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "$value",
            color = when {
                value > before -> Color(0xFF9CFF9C)
                value < before -> Color(0xFFFF9C9C)
                else -> Color.White
            },
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
    }
}

@Composable
private fun YieldPills(y: Yield, before: Yield = y) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Pill(SHOTS, "⚔️", y.shots, before.shots)
        Pill(SCOUTS, "👁️", y.scouts, before.scouts)
        Pill(MATERIALS, "⛏️", y.materials, before.materials)
    }
}

// --- Board ------------------------------------------------------------------------------------

/** How one tile looks. Building and overlay are sprite keys. */
private data class Cell(
    val fog: Boolean = false,
    val building: DuelBuilding? = null,
    val overlay: String? = null,
    /** Old information, from an earlier battle. */
    val stale: Boolean = false,
    /** 0..1, how good this tile is for the selected building; null if it isn't a choice. */
    val heat: Float? = null,
    val ghost: Boolean = false,
)

@Composable
private fun DuelBoard(radius: Int, cells: Map<Hex, Cell>, highlight: Hex?, invalid: Hex? = null, modifier: Modifier = Modifier, onTap: ((Hex) -> Unit)? = null) {
    val sprites = LocalSprites.current
    val tap by rememberUpdatedState(onTap)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(boardAspect(radius)).clipToBounds()
            .pointerInput(radius) {
                detectTapGestures { p ->
                    val h = HexGeometry(size.width.toFloat(), size.height.toFloat(), radius).hexAt(p)
                    if (h.length <= radius) tap?.invoke(h)
                }
            },
    ) {
        val g = HexGeometry(size.width, size.height, radius)
        for (h in Hex.disk(radius)) {
            val c = cells[h] ?: Cell()
            val center = g.center(h)
            val tile = 2 * g.size * 0.97f
            val ground = if (c.fog) TileSprites.FOG else TileSprites.GRASS[Math.floorMod(h.q * 7 + h.r * 13, TileSprites.GRASS.size)]
            sprites[ground]?.let { drawSprite(it, center, tile) } ?: drawPath(g.outline(h, 0.97f), if (c.fog) Color.DarkGray else FIELD)
            c.heat?.let { drawPath(g.outline(h, 0.97f), UP.copy(alpha = 0.15f + 0.5f * it)) }
            if (h == invalid) drawPath(g.outline(h, 0.97f), DOWN.copy(alpha = 0.6f))
            val fade = if (c.ghost) 0.75f else 1f
            c.building?.let { b ->
                sprites[TileSprites.FOUNDATION]?.let { drawSprite(it, center, 2 * g.size * 0.78f, fade) }
                sprites[b.spriteKey]?.let { drawSprite(it, center, g.size * 1.45f, fade) }
                    ?: drawPath(g.outline(h, 0.5f), Color.Magenta.copy(alpha = fade))
            }
            c.overlay?.let { key -> sprites[key]?.let { drawSprite(it, center, g.size * 1.3f) } }
            if (c.stale) drawPath(g.outline(h, 0.97f), Color.Black.copy(alpha = 0.3f))
            if (h == highlight || c.ghost) drawPath(g.outline(h, 0.97f), Color.White, style = Stroke(width = g.size * 0.08f))
        }
    }
}

private fun ownCells(board: Board): Map<Hex, Cell> = board.buildings.associate { b ->
    b.hex to Cell(building = b.type, overlay = if (b.damage > 0) TileSprites.CRACK else null)
}

/** A player's own town, with rubble where the opponent destroyed something. */
private fun townCells(s: DuelState, player: Int): Map<Hex, Cell> {
    val rubble = s.players[Duel.opponent(player)].intel
        .filter { (h, i) -> i.shot == Shot.DESTROYED && s.players[player].board[h] == null }
        .mapValues { Cell(overlay = TileSprites.HIT) }
    return rubble + ownCells(s.players[player].board)
}

private fun intelCell(i: Intel?, phase: Int): Cell {
    if (i == null) return Cell(fog = true)
    val stale = i.phase < phase
    return when {
        // Fired at, result not known (yet): keep what scouting showed.
        i.shot == Shot.FIRED -> Cell(fog = !i.occupied, building = i.type, overlay = TileSprites.FIRED, stale = stale)
        i.shot == Shot.MISS -> Cell(overlay = TileSprites.MISS, stale = stale)
        i.shot == Shot.DESTROYED -> Cell(overlay = TileSprites.HIT, stale = stale)
        i.shot == Shot.HIT -> Cell(building = i.type, overlay = TileSprites.CRACK, stale = stale)
        i.type != null -> Cell(building = i.type, stale = stale)
        i.occupied -> Cell(overlay = TileSprites.UNKNOWN, stale = stale)
        else -> Cell(stale = stale)
    }
}

// --- Building ---------------------------------------------------------------------------------

@Composable
private fun BuildView(s: DuelState, vm: DuelViewModel) {
    val me = s.players[vm.viewer]
    val offers = Duel.offers(s, vm.viewer)
    val type = vm.selected?.let { offers.getOrNull(it) }
    val ghost = vm.ghost
    val preview = if (type != null && ghost != null) me.board + Building(type, ghost) else me.board

    val heat = remember(me.board, type) {
        if (type == null) emptyMap()
        else {
            // Scaled from the worst free tile to the best, so equal tiles all get the same light tint.
            val gains = me.board.freeHexes.associateWith { s.config.rules.gain(me.board, type, it) }
            val min = gains.values.minOrNull() ?: 0
            val range = (gains.values.maxOrNull() ?: 0) - min
            gains.mapValues { if (range > 0) (it.value - min).toFloat() / range else 0f }
        }
    }
    val cells = ownCells(preview).toMutableMap()
    heat.forEach { (h, v) -> if (h !in cells) cells[h] = Cell(heat = v) }
    if (type != null && ghost != null) cells[ghost] = Cell(building = type, ghost = true)

    Column(Modifier.fillMaxSize()) {
        YieldPills(s.config.rules.production(preview), s.config.rules.production(me.board))
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            DuelBoard(s.config.radius, cells, highlight = null, invalid = vm.invalid, onTap = vm::tapOwn)
        }
        if (me.picked) {
            Panel { Waiting("${names(vm)[Duel.opponent(vm.viewer)]} is building…") }
            return@Column
        }
        Panel {
            // Fixed height, so the board doesn't move when a building is selected.
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterStart) {
              if (type != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(type.label(), style = MaterialTheme.typography.titleMedium)
                        Text(type.blurb(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ghost != null) {
                        FilledIconButton(onClick = vm::confirm, Modifier.size(56.dp)) { Text("✓", fontSize = 26.sp, color = Color.White) }
                    }
                }
              }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                offers.forEachIndexed { i, t ->
                    Card(
                        onClick = { vm.select(i) },
                        modifier = Modifier.weight(1f),
                        border = when {
                            vm.selected == i -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
                            t == DuelBuilding.TOWN_HALL -> BorderStroke(2.dp, GOLD)
                            else -> null
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(Modifier.fillMaxWidth().padding(6.dp), contentAlignment = Alignment.Center) {
                            SpriteIcon(t.spriteKey, t.emoji(), 56.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

// --- Battle -----------------------------------------------------------------------------------

@Composable
private fun BattleView(s: DuelState, vm: DuelViewModel) {
    val me = s.players[vm.viewer]
    val enemy = s.players[Duel.opponent(vm.viewer)]
    val battle = s.battle!!
    val budget = battle.budgets[vm.viewer]
    if (battle.attacker != vm.viewer) {
        // Only online: watch your own town while the opponent attacks it.
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                DuelBoard(s.config.radius, townCells(s, vm.viewer), highlight = null, modifier = Modifier.border(3.dp, PLAYER_COLORS[vm.viewer], RoundedCornerShape(12.dp)))
            }
            Panel { Waiting("${names(vm)[Duel.opponent(vm.viewer)]} is attacking…") }
        }
        return
    }
    val cells = if (vm.showOwn) townCells(s, vm.viewer) else Hex.disk(s.config.radius).associateWith { intelCell(me.intel[it], s.phase) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            DuelBoard(
                s.config.radius, cells,
                highlight = vm.last.takeIf { !vm.showOwn },
                modifier = Modifier.border(3.dp, if (vm.showOwn) PLAYER_COLORS[vm.viewer] else PLAYER_COLORS[Duel.opponent(vm.viewer)], RoundedCornerShape(12.dp)),
                onTap = vm::tapEnemy,
            )
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton(SCOUTS, "👁️", budget.scouts, vm.action == Extra.SCOUT && !vm.showOwn, Modifier.weight(1f)) { vm.action = Extra.SCOUT; vm.showOwn = false }
                ModeButton(SHOTS, "⚔️", budget.shots, vm.action == Extra.SHOT && !vm.showOwn, Modifier.weight(1f)) { vm.action = Extra.SHOT; vm.showOwn = false }
                FilledTonalIconButton(
                    onClick = { vm.showOwn = !vm.showOwn },
                    Modifier.size(56.dp),
                    colors = if (vm.showOwn) IconButtonDefaults.filledTonalIconButtonColors(containerColor = PLAYER_COLORS[vm.viewer]) else IconButtonDefaults.filledTonalIconButtonColors(),
                ) { SpriteIcon(DuelBuilding.TOWN_HALL.spriteKey, "🏛️", 36.dp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Materials buy one more of the selected action.
                val canBuy = me.materials >= s.config.rules.extraActionCost && !vm.showOwn
                OutlinedButton(onClick = { vm.buy(vm.action) }, enabled = canBuy, modifier = Modifier.weight(1f).height(52.dp)) {
                    SpriteIcon(MATERIALS, "⛏️", 22.dp)
                    Text(" ${me.materials}  →  +1 ", fontWeight = FontWeight.Bold)
                    SpriteIcon(if (vm.action == Extra.SCOUT) SCOUTS else SHOTS, "+", 22.dp)
                }
                Button(
                    onClick = vm::endTurn,
                    Modifier.height(52.dp),
                    colors = if (budget.shots + budget.scouts == 0) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors(),
                ) { Text("Done", fontWeight = FontWeight.Bold) }
            }
            if (enemy.board.buildings.isEmpty()) Text("Nothing left to hit", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModeButton(icon: String, fallback: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.fillMaxSize().alpha(if (count > 0) 1f else 0.4f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            SpriteIcon(icon, fallback, 32.dp)
            Spacer(Modifier.width(8.dp))
            Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// --- End --------------------------------------------------------------------------------------

@Composable
private fun OverView(s: DuelState, vm: DuelViewModel) {
    val outcome = s.outcome
    val names = names(vm)
    val title = when (outcome) {
        is Outcome.Win -> if (names[outcome.player] == "You") "You win!" else "${names[outcome.player]} wins!"
        else -> "Draw"
    }
    Column(Modifier.fillMaxSize().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (outcome is Outcome.Win) {
            Text(if (outcome.byTownHall) "Town Hall destroyed" else "More buildings destroyed", color = Color.White)
        }
        // Both towns, fully revealed.
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            s.players.indices.forEach { i ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(names[i], color = Color.White, fontWeight = FontWeight.Bold)
                    DuelBoard(s.config.radius, townCells(s, i), highlight = null, modifier = Modifier.border(3.dp, PLAYER_COLORS[i], RoundedCornerShape(12.dp)))
                }
            }
        }
        val online = vm.mode == DuelViewModel.Mode.ONLINE
        when {
            !online || (vm.isHost && vm.connected) ->
                Button(onClick = vm::playAgain, Modifier.fillMaxWidth().height(56.dp)) { Text("Play again", fontSize = 18.sp) }
            vm.connected -> Text("The host can start another match", color = Color.White)
        }
        TextButton(onClick = vm::quit) { Text("Menu", color = Color.White) }
    }
}

@Composable
private fun ReportDialog(r: DuelViewModel.Report, names: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battle over") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.lost.forEachIndexed { i, n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(PLAYER_COLORS[i], CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(names[i], Modifier.weight(1f))
                        SpriteIcon(TileSprites.HIT, "🔥", 22.dp)
                        Text(" $n", fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun Waiting(text: String) {
    Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

// --- Lobby ------------------------------------------------------------------------------------

@Composable
private fun LobbyScreen(vm: DuelViewModel, lobby: DuelViewModel.Lobby) {
    val bt = vm.bluetooth
    // What to do once Bluetooth is allowed and on.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun runPending() {
        pending?.invoke()
        pending = null
    }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (bt.enabled) runPending() else {
            vm.message = "Bluetooth is off"
            pending = null
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        when {
            !granted.values.all { it } -> {
                vm.message = "Bluetooth permission is needed"
                pending = null
            }
            bt.enabled -> runPending()
            else -> enable.launch(BluetoothTransport.enableIntent())
        }
    }
    // Hosting goes on whether or not the player allows being discoverable: paired phones still connect.
    val discoverable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    fun withBluetooth(then: () -> Unit) {
        if (!bt.available) {
            vm.message = "This device has no Bluetooth"
            return
        }
        pending = then
        when {
            !bt.hasPermissions -> permissions.launch(BluetoothTransport.permissions.toTypedArray())
            !bt.enabled -> enable.launch(BluetoothTransport.enableIntent())
            else -> runPending()
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Two phones", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when (lobby) {
            DuelViewModel.Lobby.Choose -> {
                Text("One phone hosts, the other joins.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LobbySection("Bluetooth") {
                    Button(
                        onClick = {
                            withBluetooth {
                                discoverable.launch(BluetoothTransport.discoverableIntent())
                                vm.host(DuelViewModel.Transport.BLUETOOTH)
                            }
                        },
                        Modifier.weight(1f).height(56.dp),
                    ) { Text("Host") }
                    Button(onClick = { withBluetooth(vm::browse) }, Modifier.weight(1f).height(56.dp)) { Text("Join") }
                }
                LobbySection("Same Wi-Fi") {
                    OutlinedButton(onClick = { vm.host(DuelViewModel.Transport.WIFI) }, Modifier.weight(1f).height(56.dp)) { Text("Host") }
                    OutlinedButton(onClick = vm::enterAddress, Modifier.weight(1f).height(56.dp)) { Text("Join") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::closeLobby) { Text("Back") }
            }
            is DuelViewModel.Lobby.Hosting -> {
                Spacer(Modifier.height(32.dp))
                CircularProgressIndicator(Modifier.size(56.dp))
                Text("Waiting for the other phone…", style = MaterialTheme.typography.titleMedium)
                val hint = when (lobby.transport) {
                    DuelViewModel.Transport.BLUETOOTH -> "On the other phone, tap Join under Bluetooth and pick"
                    DuelViewModel.Transport.WIFI -> "On the other phone, tap Join under Same Wi-Fi and enter"
                }
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
                lobby.addresses.ifEmpty { listOf("(no network)") }.forEach { Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::backInLobby) { Text("Cancel") }
            }
            is DuelViewModel.Lobby.Browsing -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Pick the host. Searching for more…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lobby.peers.sortedByDescending { it.paired }.forEach { peer ->
                        Card(onClick = { vm.join(peer) }, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(peer.name, fontWeight = FontWeight.Bold)
                                Text(if (peer.paired) "Paired" else peer.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = vm::backInLobby) { Text("Cancel") }
            }
            DuelViewModel.Lobby.EnterAddress -> {
                var address by rememberSaveable { mutableStateOf("") }
                Text("Enter the address shown on the host", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    address, { address = it.filter { c -> c.isDigit() || c == '.' } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.joinWifi(address) }, enabled = address.count { it == '.' } == 3, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Join") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::backInLobby) { Text("Cancel") }
            }
            is DuelViewModel.Lobby.Connecting -> {
                Spacer(Modifier.height(32.dp))
                CircularProgressIndicator(Modifier.size(56.dp))
                Text("Connecting to ${lobby.name}…", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::backInLobby) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun LobbySection(title: String, buttons: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
    }
}
