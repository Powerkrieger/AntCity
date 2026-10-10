package com.example.antcity.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.antcity.SandboxViewModel
import com.example.antcity.core.hex.BuildingResult
import com.example.antcity.core.hex.BuildingRules
import com.example.antcity.core.hex.Choice
import com.example.antcity.core.hex.Counts
import com.example.antcity.core.hex.Hex
import com.example.antcity.core.hex.HexBuilding
import com.example.antcity.core.hex.Offer
import com.example.antcity.core.hex.Placed
import com.example.antcity.core.hex.Rarity
import com.example.antcity.core.hex.RunConfig
import com.example.antcity.core.hex.Stat
import com.example.antcity.core.hex.Stats
import com.example.antcity.core.hex.TownResult
import kotlin.math.roundToInt

private val UP = Color(0xFF2E7D32)
private val DOWN = Color(0xFFC62828)
private val GRASS = listOf(Color(0xFF9CCC65), Color(0xFF94C45E), Color(0xFFA3D16C))
private val FOUNDATION = Color(0xFFEADFC8)
private val GOLD = Color(0xFFFFB300)

private fun Rarity.color() = when (this) {
    Rarity.COMMON -> Color(0xFFB0B0B0)
    Rarity.UNCOMMON -> Color(0xFF66BB6A)
    Rarity.RARE -> Color(0xFF42A5F5)
    Rarity.EPIC -> Color(0xFFAB47BC)
}

/** One short line per building, shown on demand. */
private fun HexBuilding.blurb() = when (this) {
    HexBuilding.TOWN_HALL -> "Boosts its neighbours"
    HexBuilding.HOUSE -> "Workers for farms and barracks"
    HexBuilding.FARM -> "Loves open space"
    HexBuilding.WALL -> "Stronger in a long wall"
    HexBuilding.TOWER -> "Watches ahead, guards what's behind"
    HexBuilding.BARRACKS -> "Too noisy for houses"
    HexBuilding.MARKET -> "Likes variety nearby"
    HexBuilding.ARTILLERY -> "Needs barracks, scares farms"
    HexBuilding.GATE -> "Pairs with a gate facing it"
    HexBuilding.HANGAR -> "Takes its pilots from farms"
    HexBuilding.TEMPLE -> "Blesses its straight lines"
    HexBuilding.MONUMENT -> "Loves a symmetric town"
}

private fun stars(level: Int) = "★".repeat(level)

// --- Screen -----------------------------------------------------------------------------------

@Composable
fun SandboxScreen(onClose: () -> Unit, vm: SandboxViewModel = viewModel()) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<BuildingResult?>(null) }

    val run = vm.run
    val current = remember(run) { vm.score() }
    val preview = vm.ghost?.let(vm::withSelectedAt)
    val shown = remember(preview, current) { preview?.let { vm.score(it) } ?: current }
    val heat = remember(run, vm.choice, vm.facing, vm.heatmap) { vm.heat() }

    // Drag and drop. Positions are in root coordinates.
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var dragType by remember { mutableStateOf<HexBuilding?>(null) }
    var hover by remember { mutableStateOf<Hex?>(null) }
    var boardRect by remember { mutableStateOf(Rect.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    // A dragged card floats above the finger so the target tile stays visible.
    val lift = with(LocalDensity.current) { 72.dp.toPx() }

    fun hexAtRoot(p: Offset): Hex? {
        if (!boardRect.contains(p)) return null
        val h = HexGeometry(boardRect.width, boardRect.height, run.config.radius).hexAt(p - boardRect.topLeft)
        return h.takeIf { it.length <= run.config.radius }
    }

    fun dragCard(p: Offset) {
        dragPos = p
        hover = hexAtRoot(p - Offset(0f, lift))
        vm.moveGhost(hover)
    }

    fun endCardDrag() {
        dragPos = null
        hover = null
        if (vm.ghost == null) vm.cancel() // dropped somewhere it can't go
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.message = null
        }
    }

    // Which buildings glow: the ones a placement changes, or the ones affecting the tapped building.
    val glow: Map<Hex, Boolean> = remember(preview, current, shown, vm.inspected) {
        when {
            preview != null -> shown.buildings.mapNotNull { r ->
                val before = current.at(r.placed.hex) ?: return@mapNotNull null
                val delta = Stat.entries.sumOf { r.output[it] - before.output[it] }
                if (delta != 0) r.placed.hex to (delta > 0) else null
            }.toMap()
            vm.inspected != null -> current.at(vm.inspected!!)?.influences
                ?.mapNotNull { inf -> inf.from?.let { it to (inf.percent > 0) } }?.toMap() ?: emptyMap()
            else -> emptyMap()
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF7CB342)) {
     Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInRoot() }) {
      // The bottom panel floats over the map so the board never moves when the panel changes size.
      Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize().padding(bottom = 96.dp)) {
            // Top: back, week progress, stats.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("←", fontSize = 24.sp, color = Color.White) }
                Spacer(Modifier.weight(1f))
                DayDots(run.day, run.config.days)
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(48.dp))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Stat.entries.forEach { StatPill(it, shown.totals[it], current.totals[it]) }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                HexBoard(
                    radius = run.config.radius,
                    buildings = preview ?: run.buildings,
                    result = shown,
                    ghost = vm.ghost,
                    selected = vm.inspected,
                    glow = glow,
                    heat = heat,
                    invalid = hover?.takeIf { !run.isFree(it) },
                    onTap = vm::tap,
                    onPositioned = { boardRect = it },
                    onGhostDrag = { h ->
                        hover = h
                        if (h != null && run.isFree(h)) vm.moveGhost(h)
                    },
                    onGhostDragEnd = { hover = null },
                )
            }
        }

            // Bottom panel: whatever is selected, then the offers.
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val offer = vm.selectedOffer
                    val inspected = vm.inspected?.let { current.at(it) }
                    when {
                        offer != null -> PlacingPanel(vm, offer)
                        inspected != null -> BuildingPanel(inspected) { details = inspected }
                        run.complete -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Week complete", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { vm.newRun(run.config.copy(seed = System.currentTimeMillis())) }) { Text("New week") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(onClick = vm::undo, enabled = vm.canUndo) { Text("↶", fontSize = 20.sp) }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val cards = vm.offers.mapIndexed { i, o -> Triple(o, Choice.Offered(i) as Choice, false) } +
                                listOfNotNull(vm.run.backlog?.let { Triple(it, Choice.Backlog as Choice, true) })
                            cards.forEach { (o, c, saved) ->
                                OfferCard(
                                    o, vm.choice == c, Modifier.weight(1f), saved,
                                    onClick = { vm.select(c) },
                                    onDragStart = { p -> dragType = o.type; vm.beginDrag(c); dragCard(p) },
                                    onDrag = ::dragCard,
                                    onDragEnd = ::endCardDrag,
                                )
                            }
                        }
                        Menu(vm, onOpen = { dialog = it })
                    }
                }
            }
        }

      // The dragged building, while it isn't sitting on a valid tile.
      val dragged = dragType
      dragPos?.takeIf { vm.ghost == null && dragged != null }?.let { p ->
          val q = p - rootOrigin - Offset(0f, lift)
          val half = with(LocalDensity.current) { 32.dp.toPx() }
          SpriteIcon(
              dragged!!.spriteKey, dragged.emoji, 64.dp,
              Modifier.offset { IntOffset((q.x - half).roundToInt(), (q.y - half).roundToInt()) },
          )
      }
     }
    }

    when (dialog) {
        "buildings" -> CodexDialog { dialog = null }
        "levels" -> LevelsDialog(vm) { dialog = null }
        "settings" -> SettingsDialog(vm) { dialog = null }
    }
    details?.let { DetailsDialog(it) { details = null } }
}

@Composable
private fun Menu(vm: SandboxViewModel, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalIconButton(onClick = { open = true }) { Text("☰", fontSize = 20.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Buildings") }, onClick = { open = false; onOpen("buildings") })
            DropdownMenuItem(text = { Text("Levels") }, onClick = { open = false; onOpen("levels") })
            DropdownMenuItem(text = { Text("Skip day") }, enabled = !vm.run.finished, onClick = { open = false; vm.skipDay() })
            DropdownMenuItem(text = { Text("Settings") }, onClick = { open = false; onOpen("settings") })
        }
    }
}

@Composable
private fun DayDots(day: Int, days: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in 0 until days) {
            val m = Modifier.size(if (d == day) 12.dp else 9.dp)
            Box(
                when {
                    d < day -> m.background(Color.White, CircleShape)
                    d == day -> m.background(GOLD, CircleShape)
                    else -> m.border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                },
            )
        }
    }
}

@Composable
private fun StatPill(stat: Stat, value: Int, before: Int) {
    Row(
        Modifier.background(Color(0xCC1B1B1B), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpriteIcon(stat.spriteKey, stat.emoji, 20.dp)
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
private fun HexBoard(
    radius: Int,
    buildings: List<Placed>,
    result: TownResult,
    ghost: Hex?,
    selected: Hex?,
    glow: Map<Hex, Boolean>,
    heat: Map<Hex, SandboxViewModel.Heat>,
    invalid: Hex?,
    onTap: (Hex) -> Unit,
    onPositioned: (Rect) -> Unit,
    onGhostDrag: (Hex?) -> Unit,
    onGhostDragEnd: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val sprites = LocalSprites.current
    val currentGhost by rememberUpdatedState(ghost)
    val tap by rememberUpdatedState(onTap)
    val ghostDrag by rememberUpdatedState(onGhostDrag)
    val ghostDragEnd by rememberUpdatedState(onGhostDragEnd)
    val byHex = buildings.associateBy { it.hex }
    val maxGain = heat.values.maxOfOrNull { it.gain }?.coerceAtLeast(1) ?: 1
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(boardAspect(radius)).clipToBounds()
            .onGloballyPositioned { onPositioned(it.boundsInRoot()) }
            .pointerInput(radius) {
                detectTapGestures { p -> tap(HexGeometry(size.width.toFloat(), size.height.toFloat(), radius).hexAt(p)) }
            }
            .pointerInput(radius) {
                // The building being placed can be dragged around the map.
                var moving = false
                fun hexAt(p: Offset) = HexGeometry(size.width.toFloat(), size.height.toFloat(), radius).hexAt(p).takeIf { it.length <= radius }
                detectDragGestures(
                    onDragStart = { p -> moving = currentGhost != null && hexAt(p) == currentGhost },
                    onDrag = { change, _ ->
                        if (moving) {
                            change.consume()
                            ghostDrag(hexAt(change.position))
                        }
                    },
                    onDragEnd = { if (moving) ghostDragEnd(); moving = false },
                    onDragCancel = { if (moving) ghostDragEnd(); moving = false },
                )
            },
    ) {
        val g = HexGeometry(size.width, size.height, radius)
        for (h in Hex.disk(radius)) {
            val variant = Math.floorMod(h.q * 7 + h.r * 13, GRASS.size)
            val tile = sprites[TileSprites.GRASS[variant]]
            if (tile != null) drawSprite(tile, g.center(h), 2 * g.size * 0.97f) else drawPath(g.outline(h, 0.97f), GRASS[variant])
            heat[h]?.takeIf { it.gain > 0 }?.let { drawPath(g.outline(h, 0.97f), Color.White.copy(alpha = 0.45f * it.gain / maxGain)) }

            if (h == invalid) drawPath(g.outline(h, 0.97f), DOWN.copy(alpha = 0.6f))
            val b = byHex[h] ?: continue
            val r = result.at(h)
            glow[h]?.let { up -> drawPath(g.outline(h, 0.97f), (if (up) UP else DOWN).copy(alpha = 0.55f)) }
            val fade = if (h == ghost) 0.8f else 1f
            val foundation = sprites[TileSprites.FOUNDATION]
            if (foundation != null) drawSprite(foundation, g.center(h), 2 * g.size * 0.78f, fade)
            else drawPath(g.outline(h, 0.78f), FOUNDATION.copy(alpha = fade))
            if (r?.mirrored == true) drawPath(g.outline(h, 0.78f), GOLD, style = Stroke(width = g.size * 0.06f))
            if (b.type.hasFacing) drawFacing(g, b)
            if (h == ghost || h == selected) drawPath(g.outline(h, 0.97f), Color.White, style = Stroke(width = g.size * 0.08f))
            val sprite = sprites[b.type.spriteKey]
            if (sprite != null) drawSprite(sprite, g.center(h), g.size * 1.45f, fade)
            else drawEmoji(measurer, b.type.emoji, g.center(h), g.size * 0.85f, fade)
        }
    }
}

/** The side a building faces is drawn as one dark edge of its foundation. */
private fun DrawScope.drawFacing(g: HexGeometry, b: Placed) {
    val mid = -60.0 * b.facing
    drawLine(
        Color(0xFF4E342E), g.corner(b.hex, mid - 30, 0.78f), g.corner(b.hex, mid + 30, 0.78f),
        strokeWidth = g.size * 0.14f, cap = StrokeCap.Round,
    )
}

private fun DrawScope.drawEmoji(measurer: TextMeasurer, emoji: String, at: Offset, px: Float, alpha: Float) {
    val layout = measurer.measure(emoji, TextStyle(fontSize = px.toSp()))
    drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f), alpha = alpha)
}

@Composable
private fun OfferCard(
    offer: Offer,
    selected: Boolean,
    modifier: Modifier,
    saved: Boolean,
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
        modifier = modifier
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
            else -> offer.type.rarity?.let { BorderStroke(2.dp, it.color()) }
        },
        colors = CardDefaults.cardColors(containerColor = if (saved) Color(0xFFFFF3E0) else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SpriteIcon(offer.type.spriteKey, offer.type.emoji, 44.dp)
            Text(offer.type.label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun OutputLine(stats: Stats) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.entries().forEach { (s, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpriteIcon(s.spriteKey, s.emoji, 18.dp)
                Spacer(Modifier.width(4.dp))
                Text("$v", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PlacingPanel(vm: SandboxViewModel, offer: Offer) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${offer.type.label} ${stars(offer.level)}", style = MaterialTheme.typography.titleMedium)
            Text(offer.type.blurb(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (vm.ghost != null) {
            if (offer.type.hasFacing) {
                FilledTonalIconButton(onClick = vm::rotate, Modifier.size(48.dp)) { Text("↻", fontSize = 22.sp) }
                Spacer(Modifier.width(8.dp))
            }
            FilledTonalIconButton(onClick = vm::cancel, Modifier.size(48.dp)) { Text("✕", fontSize = 18.sp) }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = vm::confirm, Modifier.size(56.dp)) { Text("✓", fontSize = 26.sp, color = Color.White) }
        }
    }
}

@Composable
private fun BuildingPanel(r: BuildingResult, onInfo: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${r.placed.type.label} ${stars(r.placed.level)}", style = MaterialTheme.typography.titleMedium)
            OutputLine(r.output)
        }
        FilledTonalIconButton(onClick = onInfo) { Text("i", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun Counts.describe() = when (this) {
    is Counts.Of -> types.joinToString(" ") { it.emoji }
    Counts.Empty -> "Open tiles"
    Counts.AnyBuilding -> "Buildings in view"
    Counts.DistinctTypes -> "Different buildings"
}

/** The full explanation, only when asked for. */
@Composable
private fun DetailsDialog(r: BuildingResult, onDismiss: () -> Unit) {
    val b = r.placed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${b.type.emoji} ${b.type.label} ${stars(b.level)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(b.type.blurb())
                HorizontalDivider()
                DetailRow("Base", b.type.rules.base.entries().joinToString("  ") { (s, v) -> "${s.emoji} $v" })
                r.bonuses.forEach { p ->
                    DetailRow("${p.bonus.counts.describe()} ×${p.counted.size}", p.amount.entries().joinToString("  ") { (s, v) -> "${s.emoji} +$v" })
                }
                r.influences.forEach { inf ->
                    DetailRow(inf.source?.let { "${it.emoji} ${it.label}" } ?: "Symmetry", "${if (inf.percent > 0) "+" else ""}${inf.percent}%")
                }
                if (b.level > 1) DetailRow("Level", "×${BuildingRules.LEVEL_PERCENT[b.level - 1] / 100.0}")
                HorizontalDivider()
                DetailRow("Total", r.output.entries().joinToString("  ") { (s, v) -> "${s.emoji} $v" }, bold = true)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String, bold: Boolean = false) {
    Row {
        Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.Bold else null)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CodexDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buildings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HexBuilding.entries.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).background(FOUNDATION, CircleShape).border(2.dp, t.rarity?.color() ?: Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { SpriteIcon(t.spriteKey, t.emoji, 34.dp) }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(t.label, fontWeight = FontWeight.Bold)
                            Text(t.blurb(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Matching buildings on both sides of the Town Hall are stronger.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun Stepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f))
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > range.first) { Text("−") }
        Text("$value", Modifier.padding(horizontal = 4.dp), fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange(value + 1) }, enabled = value < range.last) { Text("+") }
    }
}

@Composable
private fun LevelsDialog(vm: SandboxViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Levels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HexBuilding.OFFERABLE.forEach { t ->
                    Stepper("${t.emoji} ${t.label}", vm.levels[t] ?: 1, 1..BuildingRules.MAX_LEVEL) { vm.setLevel(t, it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun SettingsDialog(vm: SandboxViewModel, onDismiss: () -> Unit) {
    val c = vm.run.config
    var radius by remember { mutableIntStateOf(c.radius) }
    var days by remember { mutableIntStateOf(c.days) }
    var picks by remember { mutableIntStateOf(c.picksPerDay) }
    var seed by remember { mutableStateOf(c.seed.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Stepper("Map size", radius, 2..6) { radius = it }
                Stepper("Days", days, 3..14) { days = it }
                Stepper("Buildings per day", picks, 1..3) { picks = it }
                OutlinedTextField(seed, { seed = it.filter(Char::isDigit).take(18) }, label = { Text("Seed") }, singleLine = true)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Placement hints", Modifier.weight(1f))
                    Switch(checked = vm.heatmap, onCheckedChange = { vm.toggleHeatmap() })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.newRun(RunConfig(radius, days, picks, seed.toLongOrNull() ?: 1))
                onDismiss()
            }) { Text("New week") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
