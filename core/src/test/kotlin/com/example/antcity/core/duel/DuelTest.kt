package com.example.antcity.core.duel

import com.example.antcity.core.hex.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DuelTest {
    private val o = Hex.ORIGIN

    private fun DuelResult.ok() = assertIs<DuelResult.Ok>(this).state

    /** Both players place offer [offer] at the given hexes. */
    private fun DuelState.round(offer: Int, a: Hex, b: Hex) =
        Duel.place(Duel.place(this, 0, offer, a).ok(), 1, offer, b).ok()

    private fun hallIndex(s: DuelState, p: Int) = Duel.offers(s, p).indexOf(DuelBuilding.TOWN_HALL)

    @Test
    fun bothPlayersSeeTheSameOffersAndTheHallUntilPlaced() {
        val s = Duel.start(DuelConfig(seed = 7))
        assertEquals(Duel.offers(s, 0), Duel.offers(s, 1))
        assertEquals(DuelConfig().offers + 1, Duel.offers(s, 0).size)
        val after = Duel.place(s, 0, hallIndex(s, 0), o).ok()
        assertTrue(after.players[0].picked)
        assertEquals(0, after.round)
        val next = Duel.place(after, 1, 0, o).ok()
        assertEquals(1, next.round)
        assertTrue(DuelBuilding.TOWN_HALL !in Duel.offers(next, 0))
        assertTrue(DuelBuilding.TOWN_HALL in Duel.offers(next, 1))
    }

    @Test
    fun hallIsForcedInTheLastRoundOfThePhase() {
        var s = Duel.start(DuelConfig(seed = 3))
        val hexes = Hex.disk(2)
        for (r in 0 until s.config.roundsPerPhase - 1) s = s.round(0, hexes[r], hexes[r])
        assertEquals(listOf(DuelBuilding.TOWN_HALL), Duel.offers(s, 0))
        s = s.round(0, hexes[10], hexes[10])
        assertEquals(Stage.BATTLE, s.stage)
        assertTrue(s.players.all { it.board.hasTownHall })
    }

    @Test
    fun cannotPlaceTwiceOrOnTakenTiles() {
        val s = Duel.start(DuelConfig())
        val placed = Duel.place(s, 0, 0, o).ok()
        assertIs<DuelResult.Rejected>(Duel.place(placed, 0, 0, Hex(1, 0)))
        assertIs<DuelResult.Rejected>(Duel.place(s, 0, 0, Hex(3, 0)))
    }

    @Test
    fun housesBoostNeighboursAndWallsAddHp() {
        val board = Board(2, listOf(Building(DuelBuilding.BARRACKS, o), Building(DuelBuilding.HOUSE, Hex(1, 0)), Building(DuelBuilding.HOUSE, Hex(-1, 0)), Building(DuelBuilding.HOUSE, Hex(0, 1))))
        assertEquals(1 + DuelRules.MAX_HOUSE_BONUS, DuelRules.yieldOf(board, board[o]!!).shots)
        val walled = Board(2, listOf(Building(DuelBuilding.HOUSE, o), Building(DuelBuilding.WALL, Hex(1, 0))))
        assertEquals(2, DuelRules.maxHp(walled, walled[o]!!))
    }

    @Test
    fun losingAWallCollapsesDamagedNeighbours() {
        val board = Board(2, listOf(Building(DuelBuilding.WALL, o), Building(DuelBuilding.HOUSE, Hex(1, 0), damage = 1), Building(DuelBuilding.HOUSE, Hex(-1, 0))))
        val (after, count) = Duel.destroy(board, o)
        assertEquals(2, count)
        assertNull(after[Hex(1, 0)])
        assertNotNull(after[Hex(-1, 0)])
    }

    /** Both players put their Town Hall at the centre and fill the rest with the first offer. */
    private fun toFirstBattle(): DuelState {
        var s = Duel.start(DuelConfig(seed = 11))
        s = Duel.place(s, 0, hallIndex(s, 0), o).ok()
        s = Duel.place(s, 1, hallIndex(s, 1), o).ok()
        val ring = o.neighbors()
        while (s.stage == Stage.BUILD) s = s.round(0, ring[s.round], ring[s.round])
        return s
    }

    @Test
    fun battleScoutsShootsAndDestroysTheHall() {
        var s = toFirstBattle()
        assertEquals(Stage.BATTLE, s.stage)
        assertEquals(0, s.battle!!.attacker)
        assertIs<DuelResult.Rejected>(Duel.shoot(s, 1, o))

        // Materials for a scout and enough shots to bring the hall down.
        s = s.copy(players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(materials = DuelRules.EXTRA_ACTION_COST * 7) else p })
        s = Duel.buy(s, 0, Extra.SCOUT).ok()
        repeat(6) { s = Duel.buy(s, 0, Extra.SHOT).ok() }

        while (s.battle!!.budgets[0].scouts > 0) s = Duel.scout(s, 0, o).ok()
        val intel = s.players[0].intel
        assertEquals(DuelBuilding.TOWN_HALL, intel[o]?.type)
        assertTrue(o.neighbors().all { intel[it]?.occupied == (it in o.neighbors().subList(1, 5)) })

        while (s.players[1].board.hasTownHall) s = Duel.shoot(s, 0, o).ok()
        assertEquals(Shot.DESTROYED, s.players[0].intel[o]?.shot)
        assertEquals(1, s.players[0].kills)

        s = Duel.endTurn(s, 0).ok()
        assertEquals(1, s.battle!!.attacker)
        s = Duel.endTurn(s, 1).ok()
        assertEquals(Stage.OVER, s.stage)
        assertEquals(Outcome.Win(0, byTownHall = true), s.outcome)
    }

    @Test
    fun quietBattleMovesToTheNextPhaseWithTheOtherPlayerFirst() {
        var s = toFirstBattle()
        s = Duel.endTurn(s, 0).ok()
        s = Duel.endTurn(s, 1).ok()
        assertEquals(Stage.BUILD, s.stage)
        assertEquals(1, s.phase)
        assertEquals(0, s.round)
        val edge = Hex.disk(2).filter { it.length == 2 }
        while (s.stage == Stage.BUILD) s = s.round(0, edge[s.round], edge[s.round])
        assertEquals(1, s.battle!!.attacker)
    }

    @Test
    fun botsFinishMatches() {
        repeat(30) { seed ->
            var s = Duel.start(DuelConfig(seed = seed.toLong()))
            var steps = 0
            while (s.stage != Stage.OVER) {
                s = when (s.stage) {
                    Stage.BUILD -> DuelBot.build(s, s.players.indexOfFirst { !it.picked })
                    Stage.BATTLE -> DuelBot.battle(s, s.battle!!.attacker)
                    Stage.OVER -> s
                }
                assertTrue(++steps < 1000)
            }
            assertNotNull(s.outcome)
        }
    }
}
