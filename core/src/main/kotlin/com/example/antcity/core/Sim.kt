package com.example.antcity.core

sealed class ApplyResult {
    data class Ok(val state: GameState) : ApplyResult()
    data class Rejected(val reason: String) : ApplyResult()
}

/**
 * The deterministic game simulation. Given the same starting state and the same log,
 * it produces bit-identical results on every device and on the server.
 *
 * Rules for keeping it that way: integer math only, no wall clock, no randomness,
 * no iteration over unordered collections.
 */
object Sim {

    /** Time of the next scheduled state change, or null if nothing is scheduled. */
    fun nextEvent(s: GameState): Long? {
        val construction = s.buildings.mapNotNull { it.constructionEndsAt }.minOrNull()
        val training = s.training.firstOrNull()?.nextDoneAt
        val growth = if (s.population < s.housing) s.popAnchor + POP_GROWTH_MS else null
        return listOfNotNull(construction, training, growth).minOrNull()
    }

    /** Runs the simulation forward to time [t]. Never moves backwards. */
    fun advanceTo(start: GameState, t: Long): GameState {
        var s = start
        while (true) {
            if (t <= s.time) return s
            val next = nextEvent(s)
            if (next == null || next > t) return accrue(s, t)
            s = processEventsAt(accrue(s, maxOf(next, s.time)))
        }
    }

    /** Production between events; rates are constant in between. */
    private fun accrue(s: GameState, t: Long): GameState {
        val dt = t - s.time
        if (dt <= 0) return s.copy(time = maxOf(t, s.time))
        val rate = s.productionPerHour()
        val cap = s.storageCap * SUB
        // Stock above the cap (e.g. from loot) is kept but doesn't grow further.
        val stock = s.stock.zip(rate) { have, r -> if (have >= cap) have else minOf(have + r * dt, cap) }
        return s.copy(time = t, stock = stock)
    }

    private fun processEventsAt(s0: GameState): GameState {
        val now = s0.time
        var s = s0

        // 1. Finished constructions.
        if (s.buildings.any { it.constructionEndsAt != null && it.constructionEndsAt <= now }) {
            val oldHousing = s.housing
            s = s.copy(buildings = s.buildings.map {
                if (it.constructionEndsAt != null && it.constructionEndsAt <= now) it.copy(level = it.level + 1, constructionEndsAt = null) else it
            })
            if (s.population >= oldHousing && s.housing > oldHousing) s = s.copy(popAnchor = now)
        }

        // 2. Next trained unit.
        val head = s.training.firstOrNull()
        if (head != null && head.nextDoneAt <= now) {
            val units = s.units + (head.unit to s.unitsOf(head.unit) + 1)
            val rest = s.training.drop(1)
            val training = if (head.remaining > 1) {
                listOf(head.copy(remaining = head.remaining - 1, nextDoneAt = now + head.unit.trainMs)) + rest
            } else {
                rest.mapIndexed { i, job -> if (i == 0) job.copy(nextDoneAt = now + job.unit.trainMs) else job }
            }
            s = s.copy(units = units, training = training)
        }

        // 3. A new habitant moves in.
        if (s.population < s.housing && s.popAnchor + POP_GROWTH_MS <= now) {
            s = s.copy(population = s.population + 1, popAnchor = now)
        }
        return s
    }

    /**
     * Applies [action] at time `s.time` (callers advance first, see [step]).
     * This is the single source of truth for what a player is allowed to do.
     */
    fun apply(s: GameState, action: Action): ApplyResult = when (action) {
        is Action.Build -> build(s, action)
        is Action.Upgrade -> upgrade(s, action)
        is Action.Demolish -> demolish(s, action)
        is Action.Train -> train(s, action)
    }

    /** Advances to the action's time (never backwards) and applies it. */
    fun step(s: GameState, action: Action): Pair<Action, ApplyResult> {
        val effective = action.withAt(maxOf(action.at, s.time))
        return effective to apply(advanceTo(s, effective.at), effective)
    }

    private fun reject(reason: String) = ApplyResult.Rejected(reason)

    private fun pay(s: GameState, cost: Res): GameState? {
        val price = cost.toSub()
        return if (s.stock.covers(price)) s.copy(stock = s.stock - price) else null
    }

    private fun build(s: GameState, a: Action.Build): ApplyResult {
        val type = a.building
        if (!type.buildable) return reject("${type.label} cannot be built")
        if (a.x !in 0 until GRID_SIZE || a.y !in 0 until GRID_SIZE) return reject("Outside the city")
        if (s.buildingAt(a.x, a.y) != null) return reject("Tile is occupied")
        if (s.countOf(type) >= type.maxCount(s.townHallLevel)) return reject("Upgrade the Town Hall to build more ${type.label}s")
        if (s.activeConstructions >= BUILDERS) return reject("All builders are busy")
        val paid = pay(s, type.cost(1)) ?: return reject("Not enough resources")
        val b = Building(s.nextBuildingId, type, a.x, a.y, level = 0, constructionEndsAt = s.time + type.buildTimeMs(1))
        return ApplyResult.Ok(paid.copy(buildings = paid.buildings + b, nextBuildingId = paid.nextBuildingId + 1))
    }

    private fun upgrade(s: GameState, a: Action.Upgrade): ApplyResult {
        val b = s.building(a.buildingId) ?: return reject("No such building")
        if (b.underConstruction) return reject("Already under construction")
        val target = b.level + 1
        if (target > b.type.maxLevel) return reject("Maximum level reached")
        if (b.type != BuildingType.TOWN_HALL && target > s.townHallLevel) return reject("Upgrade the Town Hall first")
        if (s.activeConstructions >= BUILDERS) return reject("All builders are busy")
        val paid = pay(s, b.type.cost(target)) ?: return reject("Not enough resources")
        val upgraded = b.copy(constructionEndsAt = s.time + b.type.buildTimeMs(target))
        return ApplyResult.Ok(paid.copy(buildings = paid.buildings.map { if (it.id == b.id) upgraded else it }))
    }

    private fun demolish(s: GameState, a: Action.Demolish): ApplyResult {
        val b = s.building(a.buildingId) ?: return reject("No such building")
        if (b.type == BuildingType.TOWN_HALL) return reject("The Town Hall cannot be demolished")
        val after = s.copy(buildings = s.buildings.filter { it.id != b.id })
        return ApplyResult.Ok(after.copy(population = minOf(after.population, after.housing)))
    }

    private fun train(s: GameState, a: Action.Train): ApplyResult {
        if (a.count !in 1..MAX_TRAIN_BATCH) return reject("Invalid amount")
        if (s.maxHangarLevel < a.unit.hangarLevel) return reject("Requires a level ${a.unit.hangarLevel} Hangar")
        if (s.unitCount + s.queuedUnits + a.count > s.unitCapacity) return reject("Not enough Hangar capacity")
        val paid = pay(s, a.unit.cost * a.count.toLong()) ?: return reject("Not enough resources")
        val nextDoneAt = if (paid.training.isEmpty()) s.time + a.unit.trainMs else 0L
        return ApplyResult.Ok(paid.copy(training = paid.training + TrainingJob(a.unit, a.count, nextDoneAt)))
    }

    fun applyEvent(s: GameState, e: ServerEvent): ApplyResult = when (e) {
        is ServerEvent.Raided -> {
            val loot = e.loot.toSub()
            if (!s.stock.covers(loot)) reject("Raid took more than the city had")
            else ApplyResult.Ok(s.copy(stock = s.stock - loot, shieldUntil = maxOf(s.shieldUntil, e.shieldUntil)))
        }
        is ServerEvent.RaidReturned -> {
            if (e.lostUnits.any { (type, n) -> n < 0 || n > s.unitsOf(type) }) reject("Lost more units than were present")
            else ApplyResult.Ok(
                s.copy(
                    units = UnitType.entries.associateWith { s.unitsOf(it) - (e.lostUnits[it] ?: 0) },
                    stock = s.stock + e.loot.toSub(),
                    shieldUntil = minOf(s.shieldUntil, e.at),
                )
            )
        }
    }

    fun applyEntry(s: GameState, entry: LogEntry): ApplyResult {
        val advanced = advanceTo(s, entry.at)
        return when (entry) {
            is LogEntry.PlayerAction -> apply(advanced, entry.action)
            is LogEntry.Server -> applyEvent(advanced, entry.event)
        }
    }

    sealed class ReplayResult {
        data class Valid(val state: GameState) : ReplayResult()
        data class Invalid(val index: Int, val entry: LogEntry, val reason: String) : ReplayResult()
    }

    /** Full audit: rebuilds a city from its genesis state and complete log, checking every step. */
    fun replay(genesis: GameState, log: List<LogEntry>): ReplayResult {
        var s = genesis
        log.forEachIndexed { i, entry ->
            if (entry.at < s.time) return ReplayResult.Invalid(i, entry, "Entry is older than the previous one")
            when (val r = applyEntry(s, entry)) {
                is ApplyResult.Ok -> s = r.state
                is ApplyResult.Rejected -> return ReplayResult.Invalid(i, entry, r.reason)
            }
        }
        return ReplayResult.Valid(s)
    }
}
