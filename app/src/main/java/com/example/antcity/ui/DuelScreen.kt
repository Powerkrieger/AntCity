package com.example.antcity.ui

import android.widget.Toast
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.powerizzle.powerlink.ui.HostPanel
import de.powerizzle.powerlink.ui.JoinPanel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
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
import com.example.antcity.core.duel.BotLevel
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

private fun BotLevel.label() = when (this) {
    BotLevel.EASY -> "Easy"
    BotLevel.NORMAL -> "Normal"
    BotLevel.HARD -> "Hard"
}

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
    DuelViewModel.Mode.BOT -> listOf("You", "${vm.level.label()} bot")
    DuelViewModel.Mode.PASS -> listOf("Player 1", "Player 2")
    DuelViewModel.Mode.ONLINE -> List(Duel.PLAYERS) { if (it == vm.viewer) "You" else vm.opponent ?: "Opponent" }
}

@Composable
private fun DuelMenu(onClose: () -> Unit, start: (DuelViewModel.Mode, BotLevel) -> Unit, nearby: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpriteIcon(DuelBuilding.TOWN_HALL.spriteKey, "🏛️", 120.dp)
        Text("Duel", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Button(onClick = nearby, Modifier.fillMaxWidth().height(64.dp)) { Text("Two phones, nearby", fontSize = 18.sp) }
        Button(onClick = { start(DuelViewModel.Mode.PASS, BotLevel.NORMAL) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Two players, one phone", fontSize = 18.sp) }
        Text("Against the bot", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotLevel.entries.forEach { level ->
                Button(onClick = { start(DuelViewModel.Mode.BOT, level) }, Modifier.weight(1f).height(64.dp)) { Text(level.label(), fontSize = 18.sp) }
            }
        }
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
    /** Shots fired at this tile this battle: a small mark with a tick per shot. */
    val fired: Int = 0,
)

/**
 * A hex board. While a building is placed but not confirmed ([ghost]), it can be dragged around:
 * [onGhostDrag] gets the tile under the finger, or null off the board, and null again when the drag ends.
 */
@Composable
private fun DuelBoard(
    radius: Int,
    cells: Map<Hex, Cell>,
    highlight: Hex?,
    invalid: Hex? = null,
    modifier: Modifier = Modifier,
    ghost: Hex? = null,
    onGhostDrag: ((Hex?) -> Unit)? = null,
    onTap: ((Hex) -> Unit)? = null,
    strike: DuelViewModel.Strike? = null,
    /** 0..1 through [strike]'s animation. */
    strikeProgress: Float = 1f,
) {
    val sprites = LocalSprites.current
    val tap by rememberUpdatedState(onTap)
    val currentGhost by rememberUpdatedState(ghost)
    val ghostDrag by rememberUpdatedState(onGhostDrag)
    Canvas(
        modifier.fillMaxWidth().aspectRatio(boardAspect(radius)).clipToBounds()
            .pointerInput(radius) {
                detectTapGestures { p ->
                    val h = HexGeometry(size.width.toFloat(), size.height.toFloat(), radius).hexAt(p)
                    if (h.length <= radius) tap?.invoke(h)
                }
            }
            .pointerInput(radius) {
                var moving = false
                fun hexAt(p: Offset) = HexGeometry(size.width.toFloat(), size.height.toFloat(), radius).hexAt(p).takeIf { it.length <= radius }
                fun end() {
                    if (moving) ghostDrag?.invoke(null)
                    moving = false
                }
                detectDragGestures(
                    onDragStart = { p -> moving = currentGhost != null && hexAt(p) == currentGhost },
                    onDrag = { change, _ ->
                        if (moving) {
                            change.consume()
                            ghostDrag?.invoke(hexAt(change.position))
                        }
                    },
                    onDragEnd = ::end,
                    onDragCancel = ::end,
                )
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
            // The shot being animated only gets its tick once it lands.
            val landing = strike?.kind == Extra.SHOT && strike.hex == h && strikeProgress < LANDED
            drawFiredMark(sprites[TileSprites.FIRED], center, g.size, if (landing) c.fired - 1 else c.fired)
            if (h == highlight || c.ghost) drawPath(g.outline(h, 0.97f), Color.White, style = Stroke(width = g.size * 0.08f))
        }
        if (strike != null && strikeProgress < 1f) drawStrike(sprites, strike, g.center(strike.hex), g.size, strikeProgress)
    }
}

/** When a shot hits the tile in its animation, from 0 to 1. */
private const val LANDED = 0.55f

/** A small target mark in the tile's corner and, left of it, a tick for each shot fired there. */
private fun DrawScope.drawFiredMark(sprite: ImageBitmap?, center: Offset, size: Float, count: Int) {
    if (count <= 0) return
    val at = center + Offset(size * 0.38f, -size * 0.42f)
    drawCircle(Color.Black.copy(alpha = 0.5f), size * 0.2f, at)
    sprite?.let { drawSprite(it, at, size * 0.4f) } ?: drawCircle(Color.White, size * 0.12f, at, style = Stroke(size * 0.05f))
    val gap = size * 0.12f
    for (i in 0 until count) {
        val top = Offset(at.x - size * 0.3f - gap * i, at.y - size * 0.1f)
        drawLine(Color.Black.copy(alpha = 0.6f), top, top + Offset(0f, size * 0.2f), size * 0.09f, StrokeCap.Round)
        drawLine(Color.White, top, top + Offset(0f, size * 0.2f), size * 0.05f, StrokeCap.Round)
    }
}

/** A scout sweeps over the tile and its neighbours; a shot drops onto the tile and bursts. */
private fun DrawScope.drawStrike(sprites: Sprites, strike: DuelViewModel.Strike, center: Offset, size: Float, t: Float) {
    when (strike.kind) {
        Extra.SCOUT -> {
            drawCircle(Color.White.copy(alpha = 0.5f * (1 - t)), size * (0.3f + 2f * t), center, style = Stroke(size * 0.12f))
            sprites[SCOUTS]?.let { drawSprite(it, center, size * (0.9f + 0.4f * t), 1 - t) }
        }
        Extra.SHOT -> if (t < LANDED) {
            val fall = t / LANDED
            sprites[SHOTS]?.let { drawSprite(it, center - Offset(0f, size * 1.2f * (1 - fall)), size * (1.4f - 0.5f * fall)) }
        } else {
            val burst = (t - LANDED) / (1 - LANDED)
            drawCircle(Color(0xFFFFB300).copy(alpha = 0.8f * (1 - burst)), size * (0.2f + 0.7f * burst), center)
        }
    }
}

private fun ownCells(board: Board): Map<Hex, Cell> = board.buildings.associate { b ->
    b.hex to Cell(building = b.type, overlay = if (b.damage > 0) TileSprites.CRACK else null)
}

/** A player's own town, with rubble where the opponent destroyed something. */
private fun townCells(s: DuelState, player: Int): Map<Hex, Cell> {
    val board = s.players[player].board
    return board.ruins.associateWith { Cell(overlay = TileSprites.HIT) } + ownCells(board)
}

private fun intelCell(i: Intel?, phase: Int): Cell {
    if (i == null) return Cell(fog = true)
    val look = when {
        // Fired at, result not known (yet): keep what scouting showed.
        i.shot == Shot.FIRED -> Cell(fog = !i.occupied, building = i.type, overlay = TileSprites.UNKNOWN.takeIf { i.occupied && i.type == null })
        i.shot == Shot.MISS -> Cell(overlay = TileSprites.MISS)
        i.shot == Shot.DESTROYED -> Cell(overlay = TileSprites.HIT)
        i.shot == Shot.HIT -> Cell(building = i.type, overlay = TileSprites.CRACK)
        i.type != null -> Cell(building = i.type)
        i.occupied -> Cell(overlay = TileSprites.UNKNOWN)
        else -> Cell()
    }
    // Shots from earlier battles aren't marked any more.
    return if (i.phase < phase) look.copy(stale = true) else look.copy(fired = i.fired)
}

// --- Building ---------------------------------------------------------------------------------

@Composable
private fun BuildView(s: DuelState, vm: DuelViewModel) {
    val me = s.players[vm.viewer]
    val offers = Duel.offers(s, vm.viewer)
    val type = vm.selected?.let { offers.getOrNull(it) }
    val ghost = vm.ghost
    val preview = if (type != null && ghost != null) me.board + Building(type, ghost) else me.board

    // Drag and drop. Positions are in root coordinates.
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var hover by remember { mutableStateOf<Hex?>(null) }
    var boardRect by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // A dragged card floats above the finger so the target tile stays visible.
    val lift = with(LocalDensity.current) { 72.dp.toPx() }

    fun hexAtRoot(p: Offset): Hex? {
        if (!boardRect.contains(p)) return null
        val h = HexGeometry(boardRect.width, boardRect.height, s.config.radius).hexAt(p - boardRect.topLeft)
        return h.takeIf { it.length <= s.config.radius }
    }

    fun dragCard(p: Offset) {
        dragPos = p
        hover = hexAtRoot(p - Offset(0f, lift))
        vm.moveGhost(hover)
    }

    fun endCardDrag() {
        dragPos = null
        hover = null
    }

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

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize()) {
        YieldPills(s.config.rules.production(preview), s.config.rules.production(me.board))
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            DuelBoard(
                s.config.radius, cells,
                highlight = null,
                invalid = vm.invalid ?: hover?.takeIf { !me.board.isFree(it) },
                modifier = Modifier.onGloballyPositioned { boardRect = it.boundsInRoot() },
                ghost = ghost,
                onGhostDrag = { h ->
                    hover = h
                    if (h != null && me.board.isFree(h)) vm.moveGhost(h)
                },
                onTap = vm::tapOwn,
            )
        }
        if (me.picked) {
            Panel { Waiting("${names(vm)[Duel.opponent(vm.viewer)]} is building…") }
            return@Column
        }
        Panel {
            // Fixed height, so the board doesn't move as the hint changes.
            Column(Modifier.fillMaxWidth().height(56.dp), verticalArrangement = Arrangement.Center) {
                when {
                    type == null -> Text("Pick a building", style = MaterialTheme.typography.titleMedium)
                    else -> {
                        Text(type.label(), style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                ghost != null -> type.blurb()
                                heat.values.any { it > 0f } -> "Tap a tile, greener is better"
                                else -> "Tap a tile"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Once placed, only confirm or take it back. The cards stay while one is dragged, or the drag would end.
            if (ghost != null && dragPos == null) {
                Row(Modifier.height(68.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::unplace, Modifier.weight(1f).fillMaxHeight()) { Text("✕", fontSize = 26.sp) }
                    Button(onClick = vm::confirm, Modifier.weight(1f).fillMaxHeight()) { Text("✓", fontSize = 26.sp) }
                }
            } else {
                Row(Modifier.height(68.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    offers.forEachIndexed { i, t ->
                        OfferCard(
                            t, vm.selected == i, Modifier.weight(1f),
                            onClick = { vm.select(i) },
                            onDragStart = { p -> vm.beginDrag(i); dragCard(p) },
                            onDrag = ::dragCard,
                            onDragEnd = ::endCardDrag,
                        )
                    }
                }
            }
        }
    }

    // The dragged building, while it isn't sitting on a free tile.
    dragPos?.takeIf { ghost == null && type != null }?.let { p ->
        val q = p - rootOrigin - Offset(0f, lift)
        val half = with(LocalDensity.current) { 32.dp.toPx() }
        SpriteIcon(type!!.spriteKey, type.emoji(), 64.dp, Modifier.offset { IntOffset((q.x - half).roundToInt(), (q.y - half).roundToInt()) })
    }
    }
}

@Composable
private fun OfferCard(
    type: DuelBuilding,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxHeight()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                var p = Offset.Zero
                detectDragGestures(
                    onDragStart = { p = origin + it; start(p) },
                    onDrag = { change, amount -> change.consume(); p += amount; move(p) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                )
            },
        border = when {
            selected -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            type == DuelBuilding.TOWN_HALL -> BorderStroke(2.dp, GOLD)
            else -> null
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
            SpriteIcon(type.spriteKey, type.emoji(), 56.dp)
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
    val strikeAnim = remember { Animatable(1f) }
    LaunchedEffect(vm.strike?.id) {
        if (vm.strike == null) return@LaunchedEffect
        strikeAnim.snapTo(0f)
        strikeAnim.animateTo(1f, tween(550))
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            DuelBoard(
                s.config.radius, cells,
                highlight = null,
                modifier = Modifier.border(3.dp, if (vm.showOwn) PLAYER_COLORS[vm.viewer] else PLAYER_COLORS[Duel.opponent(vm.viewer)], RoundedCornerShape(12.dp)),
                onTap = vm::tapEnemy,
                strike = vm.strike.takeIf { !vm.showOwn },
                strikeProgress = strikeAnim.value,
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
                // What each player did to the other's town.
                r.damage.indices.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(PLAYER_COLORS[i], CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(names[i], Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${r.damage[i]} damage dealt", fontWeight = FontWeight.Bold)
                            Text(if (r.destroyed[i] == 1) "1 building destroyed" else "${r.destroyed[i]} buildings destroyed", style = MaterialTheme.typography.bodySmall)
                        }
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
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Two phones", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        when (lobby) {
            DuelViewModel.Lobby.Choose -> {
                Text(
                    "One phone hosts, the other joins. Works over Wi-Fi, a hotspot, Bluetooth or Wi-Fi Direct.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LobbySection("Play against a friend") {
                    Button(onClick = vm::host, Modifier.weight(1f).height(56.dp)) { Text("Host") }
                    Button(onClick = vm::join, Modifier.weight(1f).height(56.dp)) { Text("Join") }
                }
                TextButton(onClick = vm::closeLobby) { Text("Back") }
            }
            is DuelViewModel.Lobby.Hosting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Waiting for the other phone to join…", style = MaterialTheme.typography.titleMedium)
                }
                HostPanel(vm.powerLink, lobby.host, Modifier.fillMaxWidth())
                TextButton(onClick = vm::backInLobby) { Text("Cancel") }
            }
            DuelViewModel.Lobby.Joining -> {
                Card(Modifier.fillMaxWidth()) {
                    JoinPanel(vm.powerLink, onJoined = { link, _ -> vm.joined(link) }, Modifier.padding(16.dp))
                }
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
