package com.example.antcity.core.duel

import com.example.antcity.core.hex.Hex
import com.example.antcity.core.hex.SplitMix

/**
 * 1v1 duel: each player builds on their own hidden hex grid, then both attack the other's grid
 * Battleship-style. Destroy the enemy Town Hall to win.
 *
 * A match is [DuelConfig.phases] phases of [DuelConfig.roundsPerPhase] build rounds, each phase
 * ending in a battle. Everything is integer math and seeded randomness, so two devices running the
 * same moves reach the same state.
 */
enum class DuelBuilding(val offered: Boolean = true) {
    /** Lose it and you lose. Gives a shot and some materials. */
    TOWN_HALL(offered = false),
    /** Workers: boost adjacent barracks and quarries. */
    HOUSE,
    /** A shot, two with a house next to it to recruit from. */
    BARRACKS,
    /** A shot, plus one per barracks next to it (crews). */
    ARTILLERY,
    /** One scout. */
    TOWER,
    /** Materials, more with houses next to it. */
    QUARRY,
    /** Sturdy, and makes its neighbours sturdier. */
    WALL;

    companion object {
        val OFFERABLE = entries.filter { it.offered }
    }
}

/** What a town produces for one battle. */
data class Yield(val shots: Int = 0, val scouts: Int = 0, val materials: Int = 0) {
    operator fun plus(o: Yield) = Yield(shots + o.shots, scouts + o.scouts, materials + o.materials)
}

data class Building(val type: DuelBuilding, val hex: Hex, val damage: Int = 0)

data class Board(val radius: Int, val buildings: List<Building> = emptyList()) {
    operator fun get(hex: Hex) = buildings.firstOrNull { it.hex == hex }
    fun inBounds(hex: Hex) = hex.length <= radius
    fun isFree(hex: Hex) = inBounds(hex) && get(hex) == null
    val freeHexes get() = Hex.disk(radius).filter { get(it) == null }
    val hasTownHall get() = buildings.any { it.type == DuelBuilding.TOWN_HALL }

    operator fun plus(b: Building) = copy(buildings = buildings + b)
    fun without(hex: Hex) = copy(buildings = buildings.filter { it.hex != hex })
    fun replace(b: Building) = copy(buildings = buildings.map { if (it.hex == b.hex) b else it })
}

/** All tuning lives here. The defaults are the current game; the lab tries variants. */
data class DuelRules(
    val hallHp: Int = 4,
    val wallHp: Int = 2,
    /** Hit points of every other building. */
    val buildingHp: Int = 1,
    val hallShots: Int = 1,
    val hallMaterials: Int = 1,
    val barracksShots: Int = 1,
    val artilleryShots: Int = 1,
    val towerScouts: Int = 1,
    val quarryMaterials: Int = 1,
    /** Houses next to a barracks or quarry that each add one, at most. */
    val maxHouseBonus: Int = 1,
    /** Barracks next to an artillery that each add a shot, at most. */
    val maxCrews: Int = 2,
    /** Walls next to a building that each add a hit point, at most. */
    val maxWallBonus: Int = 2,
    /** Materials for one extra shot or scout. */
    val extraActionCost: Int = 2,
    /**
     * Shots only show their result on tiles scouted this battle; elsewhere the shooter just knows
     * they fired there. Stops carpet-firing from doubling as scouting.
     */
    val blindShots: Boolean = false,
    /**
     * Shot results are only revealed once the attacker ends their turn, so a turn is one planned
     * volley instead of firing, looking and adjusting. With [blindShots], unscouted tiles still
     * stay unknown afterwards.
     */
    val salvo: Boolean = false,
) {
    fun hp(type: DuelBuilding) = when (type) {
        DuelBuilding.TOWN_HALL -> hallHp
        DuelBuilding.WALL -> wallHp
        else -> buildingHp
    }

    private fun Board.adjacent(hex: Hex, type: DuelBuilding) = hex.neighbors().count { get(it)?.type == type }

    fun yieldOf(board: Board, b: Building): Yield {
        val houses = minOf(board.adjacent(b.hex, DuelBuilding.HOUSE), maxHouseBonus)
        return when (b.type) {
            DuelBuilding.TOWN_HALL -> Yield(shots = hallShots, materials = hallMaterials)
            DuelBuilding.BARRACKS -> Yield(shots = barracksShots + houses)
            DuelBuilding.ARTILLERY -> Yield(shots = artilleryShots + minOf(board.adjacent(b.hex, DuelBuilding.BARRACKS), maxCrews))
            DuelBuilding.TOWER -> Yield(scouts = towerScouts)
            DuelBuilding.QUARRY -> Yield(materials = quarryMaterials + houses)
            DuelBuilding.HOUSE, DuelBuilding.WALL -> Yield()
        }
    }

    fun production(board: Board) = board.buildings.fold(Yield()) { acc, b -> acc + yieldOf(board, b) }

    fun maxHp(board: Board, b: Building) = hp(b.type) + minOf(board.adjacent(b.hex, DuelBuilding.WALL), maxWallBonus)

    /**
     * One number for "how good is this town", used for placement hints and by the simple bot. A
     * scout is worth 3, a shot 2, a material or an extra hit point from walls 1.
     */
    fun value(board: Board): Int {
        val y = production(board)
        return 2 * y.shots + 3 * y.scouts + y.materials + board.buildings.sumOf { maxHp(board, it) - hp(it.type) }
    }

    /** How much placing [type] at [hex] would add to [value]. */
    fun gain(board: Board, type: DuelBuilding, hex: Hex) = value(board + Building(type, hex)) - value(board)
}

data class DuelConfig(
    val radius: Int = 2,
    val phases: Int = 3,
    val roundsPerPhase: Int = 5,
    /** Buildings offered each round; 1 means everyone just places the building they're given. */
    val offers: Int = 3,
    val seed: Long = 1,
    val rules: DuelRules = DuelRules(),
)

enum class Shot {
    MISS, HIT, DESTROYED,
    /** Fired at an unscouted tile with [DuelRules.blindShots]: the result isn't known. */
    FIRED,
}

/**
 * What a player knows about one enemy tile, from the phase it was learned in. Scouting the tile
 * itself shows its building; its neighbours only show whether something is there.
 */
data class Intel(val occupied: Boolean, val type: DuelBuilding? = null, val shot: Shot? = null, val phase: Int)

data class Player(
    val board: Board,
    val materials: Int = 0,
    /** What this player knows about the opponent's board. */
    val intel: Map<Hex, Intel> = emptyMap(),
    /** Enemy buildings this player has destroyed. */
    val kills: Int = 0,
    /** Has picked in the current build round. */
    val picked: Boolean = false,
    /** Enemy tiles this player scouted in the current battle. */
    val scouted: Set<Hex> = emptySet(),
    /** With [DuelRules.salvo]: results of this turn's shots, revealed when the turn ends. */
    val pending: Map<Hex, Intel> = emptyMap(),
)

enum class Stage { BUILD, BATTLE, OVER }

data class Budget(val shots: Int, val scouts: Int)

/** One battle: each player gets one turn with the budget their town produced at its start. */
data class Battle(val order: List<Int>, val turn: Int, val budgets: List<Budget>) {
    val attacker get() = order[turn]
}

sealed class Outcome {
    data class Win(val player: Int, val byTownHall: Boolean) : Outcome()
    data object Draw : Outcome()
}

data class DuelState(
    val config: DuelConfig,
    val players: List<Player>,
    val phase: Int = 0,
    val round: Int = 0,
    val stage: Stage = Stage.BUILD,
    val battle: Battle? = null,
    val outcome: Outcome? = null,
) {
    val isLastPhase get() = phase == config.phases - 1
}

sealed class DuelResult {
    data class Ok(val state: DuelState) : DuelResult()
    data class Rejected(val reason: String) : DuelResult()
}

/** Extra actions bought with materials. */
enum class Extra { SHOT, SCOUT }

object Duel {
    const val PLAYERS = 2

    fun start(config: DuelConfig) = DuelState(config, List(PLAYERS) { Player(Board(config.radius)) })

    fun opponent(player: Int) = 1 - player

    /**
     * The buildings [player] can choose from this round. Both players get the same offers. Until a
     * player has a Town Hall it is offered too, and in the last round of the first phase it's the
     * only choice.
     */
    fun offers(s: DuelState, player: Int): List<DuelBuilding> {
        if (s.stage != Stage.BUILD) return emptyList()
        val needsHall = !s.players[player].board.hasTownHall
        if (needsHall && s.phase == 0 && s.round == s.config.roundsPerPhase - 1) return listOf(DuelBuilding.TOWN_HALL)
        val shared = sharedOffers(s.config, s.phase, s.round)
        return if (needsHall) shared + DuelBuilding.TOWN_HALL else shared
    }

    private fun sharedOffers(config: DuelConfig, phase: Int, round: Int): List<DuelBuilding> {
        val rng = SplitMix(config.seed xor (phase.toLong() shl 40) xor (round.toLong() shl 20))
        val pool = DuelBuilding.OFFERABLE.toMutableList()
        return List(minOf(config.offers, pool.size)) { pool.removeAt(rng.nextInt(pool.size)) }
    }

    /** [player] places offer number [offer] at [hex]. The round ends once both have placed. */
    fun place(s: DuelState, player: Int, offer: Int, hex: Hex): DuelResult {
        if (s.stage != Stage.BUILD) return DuelResult.Rejected("Not building now")
        val me = s.players[player]
        if (me.picked) return DuelResult.Rejected("Already placed this round")
        val type = offers(s, player).getOrNull(offer) ?: return DuelResult.Rejected("No such offer")
        if (!me.board.isFree(hex)) return DuelResult.Rejected("That tile isn't free")
        val placed = s.withPlayer(player, me.copy(board = me.board + Building(type, hex), picked = true))
        return DuelResult.Ok(if (placed.players.all { it.picked }) endRound(placed) else placed)
    }

    private fun endRound(s: DuelState): DuelState {
        val next = s.copy(players = s.players.map { it.copy(picked = false) })
        return if (s.round + 1 < s.config.roundsPerPhase) next.copy(round = s.round + 1) else startBattle(next)
    }

    private fun startBattle(s: DuelState): DuelState {
        val yields = s.players.map { s.config.rules.production(it.board) }
        // Who shoots first alternates between phases. Budgets are fixed now, so order doesn't change them.
        val order = if (s.phase % 2 == 0) listOf(0, 1) else listOf(1, 0)
        return s.copy(
            stage = Stage.BATTLE,
            players = s.players.mapIndexed { i, p -> p.copy(materials = p.materials + yields[i].materials, scouted = emptySet(), pending = emptyMap()) },
            battle = Battle(order, 0, yields.map { Budget(it.shots, it.scouts) }),
        )
    }

    private fun attackerCheck(s: DuelState, player: Int): String? = when {
        s.stage != Stage.BATTLE -> "Not fighting now"
        s.battle!!.attacker != player -> "Not your turn"
        else -> null
    }

    /** Reveals [hex] and whether its neighbours are built on. */
    fun scout(s: DuelState, player: Int, hex: Hex): DuelResult {
        attackerCheck(s, player)?.let { return DuelResult.Rejected(it) }
        val battle = s.battle!!
        val budget = battle.budgets[player]
        if (budget.scouts <= 0) return DuelResult.Rejected("No scouts left")
        val enemy = s.players[opponent(player)].board
        if (!enemy.inBounds(hex)) return DuelResult.Rejected("Off the map")
        val me = s.players[player]
        val seen = (listOf(hex) + hex.neighbors()).filter(enemy::inBounds).associateWith { h ->
            val b = enemy[h]
            Intel(occupied = b != null, type = if (h == hex) b?.type else null, phase = s.phase)
        }
        return DuelResult.Ok(
            s.withPlayer(player, me.copy(intel = me.intel + seen, scouted = me.scouted + seen.keys))
                .withBudget(player, budget.copy(scouts = budget.scouts - 1)),
        )
    }

    /** One hit on [hex]. A building whose hit points run out is destroyed. */
    fun shoot(s: DuelState, player: Int, hex: Hex): DuelResult {
        attackerCheck(s, player)?.let { return DuelResult.Rejected(it) }
        val budget = s.battle!!.budgets[player]
        if (budget.shots <= 0) return DuelResult.Rejected("No shots left")
        val target = opponent(player)
        val enemy = s.players[target].board
        if (!enemy.inBounds(hex)) return DuelResult.Rejected("Off the map")
        val me = s.players[player]
        val b = enemy[hex]
        val (board, seen, kills) = when {
            b == null -> Triple(enemy, Intel(false, shot = Shot.MISS, phase = s.phase), 0)
            b.damage + 1 >= s.config.rules.maxHp(enemy, b) -> {
                val (after, destroyed) = destroy(s.config.rules, enemy, hex)
                Triple(after, Intel(true, b.type, Shot.DESTROYED, s.phase), destroyed)
            }
            else -> Triple(enemy.replace(b.copy(damage = b.damage + 1)), Intel(true, b.type, Shot.HIT, s.phase), 0)
        }
        val rules = s.config.rules
        // What the shooter learns, ever: nothing on unscouted tiles with blind shots.
        val revealed = if (!rules.blindShots || hex in me.scouted) seen else null
        // A "fired here" marker keeps whatever was known about the tile.
        val fired = (me.intel[hex]?.takeIf { it.phase == s.phase } ?: Intel(false, phase = s.phase)).copy(shot = Shot.FIRED)
        val after = when {
            rules.salvo -> me.copy(intel = me.intel + (hex to fired), pending = revealed?.let { me.pending + (hex to it) } ?: me.pending)
            else -> me.copy(intel = me.intel + (hex to (revealed ?: fired)))
        }
        return DuelResult.Ok(
            s.withPlayer(target, s.players[target].copy(board = board))
                .withPlayer(player, after.copy(kills = me.kills + kills))
                .withBudget(player, budget.copy(shots = budget.shots - 1)),
        )
    }

    /**
     * Removes the building at [hex]. Losing a wall can drop a damaged neighbour's hit points to its
     * damage, which collapses it too. Returns the board and how many buildings fell.
     */
    internal fun destroy(rules: DuelRules, board: Board, hex: Hex): Pair<Board, Int> {
        var after = board.without(hex)
        var count = 1
        if (board[hex]?.type == DuelBuilding.WALL) {
            for (n in hex.neighbors()) {
                val b = after[n] ?: continue
                if (b.damage >= rules.maxHp(after, b)) {
                    val (next, more) = destroy(rules, after, n)
                    after = next
                    count += more
                }
            }
        }
        return after to count
    }

    /** Spends materials on one more shot or scout this battle. */
    fun buy(s: DuelState, player: Int, extra: Extra): DuelResult {
        attackerCheck(s, player)?.let { return DuelResult.Rejected(it) }
        val me = s.players[player]
        val cost = s.config.rules.extraActionCost
        if (me.materials < cost) return DuelResult.Rejected("Not enough materials")
        val budget = s.battle!!.budgets[player]
        val more = when (extra) {
            Extra.SHOT -> budget.copy(shots = budget.shots + 1)
            Extra.SCOUT -> budget.copy(scouts = budget.scouts + 1)
        }
        return DuelResult.Ok(s.withPlayer(player, me.copy(materials = me.materials - cost)).withBudget(player, more))
    }

    /** Ends [player]'s battle turn; unused shots and scouts are lost, materials are kept. */
    fun endTurn(s: DuelState, player: Int): DuelResult {
        attackerCheck(s, player)?.let { return DuelResult.Rejected(it) }
        val me = s.players[player]
        // The volley's results come in now.
        val s = s.withPlayer(player, me.copy(intel = me.intel + me.pending, pending = emptyMap()))
        val battle = s.battle!!
        if (battle.turn + 1 < battle.order.size) return DuelResult.Ok(s.copy(battle = battle.copy(turn = battle.turn + 1)))
        return DuelResult.Ok(endBattle(s))
    }

    private fun endBattle(s: DuelState): DuelState {
        val alive = s.players.map { it.board.hasTownHall }
        val kills = s.players.map { it.kills }
        // Both halls falling, or surviving the last battle, is decided by buildings destroyed.
        val byKills = if (kills[0] == kills[1]) Outcome.Draw else Outcome.Win(if (kills[0] > kills[1]) 0 else 1, byTownHall = false)
        val outcome = when {
            alive.none { it } -> byKills
            alive.any { !it } -> Outcome.Win(alive.indexOf(true), byTownHall = true)
            s.isLastPhase -> byKills
            else -> null
        }
        return if (outcome != null) {
            s.copy(stage = Stage.OVER, battle = null, outcome = outcome)
        } else {
            s.copy(stage = Stage.BUILD, battle = null, phase = s.phase + 1, round = 0)
        }
    }

    private fun DuelState.withPlayer(i: Int, p: Player) = copy(players = players.mapIndexed { j, old -> if (j == i) p else old })

    private fun DuelState.withBudget(i: Int, b: Budget) =
        copy(battle = battle!!.copy(budgets = battle.budgets.mapIndexed { j, old -> if (j == i) b else old }))
}
