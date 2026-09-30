package com.example.antcity.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SimTest {
    private val t0 = 1_700_000_000_000L
    private var n = 0
    private fun id() = "a${n++}"

    private fun run(s: GameState, vararg actions: Action): GameState =
        actions.fold(s) { acc, a -> (Sim.step(acc, a).second as ApplyResult.Ok).state }

    private fun developedCity(): GameState {
        val g = GameState.genesis(t0)
        return run(
            g,
            Action.Build(id(), t0 + 1_000, BuildingType.HOUSE, 0, 0),
            Action.Build(id(), t0 + 2_000, BuildingType.FARM, 1, 0),
            Action.Build(id(), t0 + 40_000, BuildingType.LUMBER_MILL, 2, 0),
            Action.Build(id(), t0 + 60_000, BuildingType.HOUSE, 3, 0),
        )
    }

    @Test
    fun advancingIsPathIndependent() {
        val s = developedCity()
        val end = s.time + 3 * 3_600_000L
        val direct = Sim.advanceTo(s, end)
        var stepped = s
        var t = s.time
        while (t < end) {
            t = minOf(end, t + 7_777)
            stepped = Sim.advanceTo(stepped, t)
        }
        assertEquals(direct, stepped)
        assertEquals(direct.hash(), stepped.hash())
    }

    @Test
    fun populationGrowsToHousingAndWorkersProduce() {
        val s = Sim.advanceTo(developedCity(), t0 + 3_600_000L)
        assertEquals(s.housing, s.population)
        assertTrue(s.productionPerHour().food > 0)
        assertTrue(s.productionPerHour().wood > 0)
    }

    @Test
    fun cannotBuildWithoutResources() {
        val g = GameState.genesis(t0).copy(stock = Res.ZERO)
        val (_, r) = Sim.step(g, Action.Build(id(), t0, BuildingType.HOUSE, 0, 0))
        assertIs<ApplyResult.Rejected>(r)
    }

    @Test
    fun builderLimitIsEnforced() {
        val g = GameState.genesis(t0)
        val two = run(g, Action.Build(id(), t0, BuildingType.HOUSE, 0, 0), Action.Build(id(), t0, BuildingType.HOUSE, 1, 0))
        assertIs<ApplyResult.Rejected>(Sim.step(two, Action.Build(id(), t0, BuildingType.HOUSE, 2, 0)).second)
    }

    @Test
    fun actionsAreNeverAppliedInThePast() {
        val g = Sim.advanceTo(GameState.genesis(t0), t0 + 10_000)
        val (effective, _) = Sim.step(g, Action.Build(id(), t0 - 99_999, BuildingType.HOUSE, 0, 0))
        assertEquals(t0 + 10_000, effective.at)
    }

    @Test
    fun replayReproducesStateAndCatchesForgedEntries() {
        val g = GameState.genesis(t0)
        val log = listOf(
            Action.Build(id(), t0 + 1_000, BuildingType.HOUSE, 0, 0),
            Action.Build(id(), t0 + 1_500, BuildingType.WAREHOUSE, 1, 0),
        ).map { LogEntry.PlayerAction(it) }
        val live = log.fold(g) { s, e -> (Sim.applyEntry(s, e) as ApplyResult.Ok).state }
        assertEquals(live, (Sim.replay(g, log) as Sim.ReplayResult.Valid).state)

        // A tower right after genesis is unaffordable in stone+wood after the warehouse.
        val forged = log + LogEntry.PlayerAction(Action.Build(id(), t0 + 2_000, BuildingType.TOWER, 2, 0)) +
            LogEntry.PlayerAction(Action.Build(id(), t0 + 2_000, BuildingType.TOWER, 3, 0))
        val r = Sim.replay(g, forged)
        assertIs<Sim.ReplayResult.Invalid>(r)
    }

    @Test
    fun trainingCompletesUnitsSequentially() {
        val g = GameState.genesis(t0).copy(
            stock = Res(10_000, 10_000, 10_000, 10_000).toSub(),
            buildings = GameState.genesis(t0).buildings + Building(2, BuildingType.HANGAR, 0, 0, level = 1),
            nextBuildingId = 3,
        )
        val s = run(g, Action.Train(id(), t0, UnitType.INFANTRY, 3))
        assertEquals(2, Sim.advanceTo(s, t0 + 2 * UnitType.INFANTRY.trainMs).unitsOf(UnitType.INFANTRY))
        val done = Sim.advanceTo(s, t0 + 3 * UnitType.INFANTRY.trainMs)
        assertEquals(3, done.unitsOf(UnitType.INFANTRY))
        assertTrue(done.training.isEmpty())
    }

    @Test
    fun battleIsDeterministicAndSiegeBreaksWalls() {
        val defender = GameState.genesis(t0).copy(
            buildings = GameState.genesis(t0).buildings + Building(2, BuildingType.WALL, 0, 0, level = 2),
            stock = Res(1000, 1000, 1000, 1000).toSub(),
        )
        val weak = Battle.resolve(mapOf(UnitType.INFANTRY to 3), defender)
        assertEquals(weak, Battle.resolve(mapOf(UnitType.INFANTRY to 3), defender))
        assertTrue(!weak.wallsBreached)
        assertEquals(Res.ZERO, weak.loot)

        val strong = Battle.resolve(mapOf(UnitType.INFANTRY to 20, UnitType.RAM to 4), defender)
        assertTrue(strong.victory)
        assertTrue(strong.loot.total() in 1..(20 * UnitType.INFANTRY.carry))
    }
}
