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
        assertEquals(1 + DuelRules().maxHouseBonus, DuelRules().yieldOf(board, board[o]!!).shots)
        val walled = Board(2, listOf(Building(DuelBuilding.HOUSE, o), Building(DuelBuilding.WALL, Hex(1, 0))))
        assertEquals(2, DuelRules().maxHp(walled, walled[o]!!))
    }

    @Test
    fun losingAWallCollapsesDamagedNeighbours() {
        val board = Board(2, listOf(Building(DuelBuilding.WALL, o), Building(DuelBuilding.HOUSE, Hex(1, 0), damage = 1), Building(DuelBuilding.HOUSE, Hex(-1, 0))))
        val (after, count) = Duel.destroy(DuelRules(), board, o)
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
        s = s.copy(players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(materials = DuelRules().extraActionCost * 8) else p })
        s = Duel.buy(s, 0, Extra.SCOUT).ok()
        repeat(7) { s = Duel.buy(s, 0, Extra.SHOT).ok() }

        while (s.battle!!.budgets[0].scouts > 0) s = Duel.scout(s, 0, o).ok()
        val intel = s.players[0].intel
        assertEquals(DuelBuilding.TOWN_HALL, intel[o]?.type)
        assertTrue(o.neighbors().all { intel[it]?.occupied == (it in o.neighbors().subList(1, 5)) })

        val hp = DuelRules().maxHp(s.players[1].board, s.players[1].board[o]!!)
        while (s.players[1].board.hasTownHall) s = Duel.shoot(s, 0, o).ok()
        // Shots are hidden: the shooter still only sees the scouted hall, marked as fired at.
        assertEquals(Intel(true, DuelBuilding.TOWN_HALL, Shot.FIRED, 0), s.players[0].intel[o])
        assertEquals(1, s.players[0].kills)
        assertEquals(hp, s.players[0].hits)
        assertEquals(setOf(o), s.players[1].board.ruins)

        // A miss doesn't show either.
        val empty = o.neighbors()[0]
        s = Duel.shoot(s, 0, empty).ok()
        assertEquals(Shot.FIRED, s.players[0].intel[empty]?.shot)
        assertEquals(hp, s.players[0].hits)

        s = Duel.endTurn(s, 0).ok()
        assertEquals(1, s.battle!!.attacker)
        s = Duel.endTurn(s, 1).ok()
        assertEquals(Stage.OVER, s.stage)
        assertEquals(Outcome.Win(0, byTownHall = true), s.outcome)
    }

    @Test
    fun blindShotsOnlyShowResultsOnScoutedTiles() {
        var s = toFirstBattle()
        s = s.copy(config = s.config.copy(rules = DuelRules(hiddenShots = false, blindShots = true)))
        s = s.copy(players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(materials = DuelRules().extraActionCost * 3) else p })
        s = Duel.buy(Duel.buy(Duel.buy(s, 0, Extra.SHOT).ok(), 0, Extra.SHOT).ok(), 0, Extra.SCOUT).ok()
        val ring = o.neighbors()

        // Unscouted: the hit lands, but the shooter only learns that they fired.
        s = Duel.shoot(s, 0, ring[1]).ok()
        assertEquals(Shot.FIRED, s.players[0].intel[ring[1]]?.shot)
        assertEquals(1, s.players[1].board[ring[1]]?.damage ?: 1)

        // Scouted: results show, including on a tile fired at blindly before.
        s = Duel.scout(s, 0, o).ok()
        s = Duel.shoot(s, 0, o).ok()
        assertEquals(Shot.HIT, s.players[0].intel[o]?.shot)
        s = Duel.shoot(s, 0, ring[0]).ok()
        assertEquals(Shot.MISS, s.players[0].intel[ring[0]]?.shot)
    }

    @Test
    fun salvoRevealsResultsOnlyWhenTheTurnEnds() {
        var s = toFirstBattle()
        s = s.copy(config = s.config.copy(rules = DuelRules(hiddenShots = false, salvo = true)))
        s = s.copy(players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(materials = DuelRules().extraActionCost * 2) else p })
        s = Duel.buy(Duel.buy(s, 0, Extra.SHOT).ok(), 0, Extra.SHOT).ok()
        val empty = o.neighbors()[0]

        s = Duel.shoot(s, 0, o).ok()
        s = Duel.shoot(s, 0, empty).ok()
        assertEquals(Shot.FIRED, s.players[0].intel[o]?.shot)
        assertEquals(Shot.FIRED, s.players[0].intel[empty]?.shot)

        s = Duel.endTurn(s, 0).ok()
        assertEquals(Shot.HIT, s.players[0].intel[o]?.shot)
        assertEquals(Shot.MISS, s.players[0].intel[empty]?.shot)
        assertTrue(s.players[0].pending.isEmpty())
    }

    @Test
    fun salvoWithBlindShotsRevealsOnlyScoutedTiles() {
        var s = toFirstBattle()
        s = s.copy(config = s.config.copy(rules = DuelRules(hiddenShots = false, salvo = true, blindShots = true)))
        s = s.copy(players = s.players.mapIndexed { i, p -> if (i == 0) p.copy(materials = DuelRules().extraActionCost * 2) else p })
        s = Duel.buy(Duel.buy(s, 0, Extra.SCOUT).ok(), 0, Extra.SHOT).ok()
        val far = Hex(2, -2)

        s = Duel.scout(s, 0, o).ok()
        s = Duel.shoot(s, 0, o).ok()
        s = Duel.shoot(s, 0, far).ok()
        s = Duel.endTurn(s, 0).ok()
        assertEquals(Shot.HIT, s.players[0].intel[o]?.shot)
        assertEquals(Shot.FIRED, s.players[0].intel[far]?.shot)
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
        val bots = listOf<() -> DuelPlayer>({ DuelBot }) + BotLevel.entries.map { it::create }
        for (a in bots) for (b in bots) repeat(10) { seed ->
            val players = listOf(a(), b())
            var s = Duel.start(DuelConfig(seed = seed.toLong()))
            var steps = 0
            while (s.stage != Stage.OVER) {
                val who = s.actor()!!
                s = players[who].play(s, who)
                assertTrue(++steps < 1000)
            }
            assertNotNull(s.outcome)
        }
    }
}
