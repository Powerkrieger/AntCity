package com.example.antcity.core.hex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexTest {
    private val o = Hex.ORIGIN

    @Test
    fun diskSizes() {
        assertEquals(1, Hex.disk(0).size)
        assertEquals(37, Hex.disk(3).size)
        assertTrue(Hex.disk(3).all { it.length <= 3 })
    }

    @Test
    fun wideConeCoversThreeNeighborsNarrowConeOne() {
        for (f in 0..5) {
            assertEquals(3, o.neighbors().count { Hex.inCone(o, f, it, HalfAngle.DEG_60) })
            assertEquals(listOf(Hex.DIRECTIONS[f]), o.neighbors().filter { Hex.inCone(o, f, it, HalfAngle.DEG_30) })
        }
        // (2,-1) is 30° off east: inside the ±30° boundary.
        assertTrue(Hex.inCone(o, 0, Hex(2, -1), HalfAngle.DEG_30))
        assertFalse(Hex.inCone(o, 0, Hex(-1, 0), HalfAngle.DEG_60))
    }

    @Test
    fun mirrorsAreInvolutionsWithAFixedLine() {
        Mirrors.ALL.forEachIndexed { i, m ->
            Hex.disk(3).forEach { assertEquals(it, m(m(it))) }
            val p = Mirrors.pointOnAxis(i)
            assertEquals(p, m(p))
        }
    }

    private fun score(vararg b: Placed) = TownScoring.score(3, b.toList())

    @Test
    fun mirroredPairsGetBonus() {
        val s = score(Placed(HexBuilding.BARRACKS, Hex(1, 0)), Placed(HexBuilding.BARRACKS, Hex(-1, 0)))
        assertEquals(2, s.mirroredCount)
        // Barracks base 4 attack, +25% for being mirrored.
        assertEquals(5, s.at(Hex(1, 0))!!.output.attack)
    }

    @Test
    fun farmsBehindATowerDoBetterThanInFrontOfIt() {
        val tower = Placed(HexBuilding.TOWER, o, facing = 0) // looking east
        val behind = score(tower, Placed(HexBuilding.FARM, Hex(-1, 0))).at(Hex(-1, 0))!!
        val inFront = score(tower, Placed(HexBuilding.FARM, Hex(1, 0))).at(Hex(1, 0))!!
        assertEquals(behind.raw, inFront.raw)
        assertEquals(listOf(30), behind.influences.map { it.percent })
        assertTrue(behind.output.food > inFront.output.food)
    }

    @Test
    fun hangarDrainsFarmsMoreWhenCloser() {
        val hangar = Placed(HexBuilding.HANGAR, o)
        val near = score(hangar, Placed(HexBuilding.FARM, Hex(1, 0))).at(Hex(1, 0))!!
        val mid = score(hangar, Placed(HexBuilding.FARM, Hex(2, 0))).at(Hex(2, 0))!!
        val far = score(hangar, Placed(HexBuilding.FARM, Hex(3, 0))).at(Hex(3, 0))!!
        assertEquals(listOf(-40), near.influences.map { it.percent })
        assertEquals(listOf(-20), mid.influences.map { it.percent })
        assertTrue(far.influences.isEmpty())
    }

    @Test
    fun gatesFacingEachOtherScore() {
        val a = Placed(HexBuilding.GATE, Hex(-2, 0), facing = 0)
        val b = Placed(HexBuilding.GATE, Hex(2, 0), facing = 3)
        assertEquals(3 + 4, score(a, b).at(a.hex)!!.raw.defense)
        assertEquals(3, score(a, b.copy(facing = 1)).at(a.hex)!!.raw.defense)
    }

    @Test
    fun farmCountsEmptyNeighboursInsideTheGrid() {
        val center = score(Placed(HexBuilding.FARM, o)).at(o)!!
        assertEquals(3 + 6, center.raw.food)
        val corner = score(Placed(HexBuilding.FARM, Hex(3, 0))).at(Hex(3, 0))!!
        assertEquals(3 + 3, corner.raw.food)
    }

    @Test
    fun levelsMultiplyOutput() {
        val l1 = score(Placed(HexBuilding.HANGAR, o, level = 1)).totals.attack
        val l3 = score(Placed(HexBuilding.HANGAR, o, level = 3)).totals.attack
        assertEquals(2 * l1, l3)
    }

    @Test
    fun offersAreDeterministicAndDistinct() {
        val c = RunConfig(seed = 42)
        val a = Runs.offers(c, 3, 1, emptyMap())
        assertEquals(a, Runs.offers(c, 3, 1, emptyMap()))
        assertEquals(3, a.map { it.type }.toSet().size)
        assertTrue(a.none { it.type == HexBuilding.TOWN_HALL })
    }

    @Test
    fun missedDaysFillBacklogOnce() {
        val c = RunConfig(picksPerDay = 2, seed = 7)
        var s = Runs.start(c)
        s = Runs.skipDay(s, emptyMap())
        assertEquals(1, s.day)
        assertEquals(Runs.offers(c, 0, 0, emptyMap()).first(), s.backlog)
        assertEquals(1, s.lost) // second missed pick of the day didn't fit

        val r = Runs.place(s, emptyMap(), Choice.Backlog, Hex(1, 0), 0)
        assertIs<PlaceResult.Ok>(r)
        assertNull(r.state.backlog)
        assertEquals(1, r.state.day) // placing the backlog doesn't use up today's pick
    }

    @Test
    fun placingAdvancesPicksAndDays() {
        var s = Runs.start(RunConfig(days = 1, picksPerDay = 2))
        s = (Runs.place(s, emptyMap(), Choice.Offered(0), Hex(1, 0), 0) as PlaceResult.Ok).state
        assertEquals(1, s.pick)
        s = (Runs.place(s, emptyMap(), Choice.Offered(2), Hex(2, 0), 0) as PlaceResult.Ok).state
        assertTrue(s.complete)
        assertIs<PlaceResult.Rejected>(Runs.place(s, emptyMap(), Choice.Offered(0), Hex(3, 0), 0))
        assertIs<PlaceResult.Rejected>(Runs.place(Runs.start(RunConfig()), emptyMap(), Choice.Offered(0), Hex.ORIGIN, 0))
    }
}
