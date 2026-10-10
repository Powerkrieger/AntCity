package com.example.antcity.core.duel

import com.example.antcity.core.hex.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class DuelNetTest {

    /** Sends [m] over the "wire": encoded to a line of JSON and decoded again. */
    private fun wire(m: NetMessage) = NetMessage.decode(m.encode())

    @Test
    fun messagesRoundTrip() {
        val messages = listOf(
            NetMessage.Hello(NetMessage.PROTOCOL, "Ant"),
            NetMessage.Start(42),
            NetMessage.Move(3, DuelMove.Place(1, Hex(1, -1)), 99),
            NetMessage.Move(4, DuelMove.Buy(Extra.SCOUT), -5),
            NetMessage.Move(5, DuelMove.EndTurn, 0),
            NetMessage.Bye,
        )
        messages.forEach { assertEquals(it, wire(it)) }
    }

    @Test
    fun twoDevicesPlayWholeMatchesInStep() {
        repeat(10) { seed ->
            val devices = listOf(NetDuel(0, seed.toLong()), NetDuel(1, seed.toLong()))
            var steps = 0
            while (devices[0].state.stage != Stage.OVER) {
                val who = devices[0].state.actor()!!
                val local = devices[who]
                val (result, msg) = local.local(DuelBot.next(local.state, who))
                assertIs<DuelResult.Ok>(result)
                assertIs<NetDuel.Remote.Ok>(devices[1 - who].remote(wire(msg!!) as NetMessage.Move))
                assertEquals(NetDuel.checksum(devices[0].state), NetDuel.checksum(devices[1].state))
                check(++steps < 2000)
            }
            assertNotNull(devices[1].state.outcome)
        }
    }

    @Test
    fun tamperedOrOutOfOrderMovesBreakTheMatch() {
        val a = NetDuel(0, 1)
        val b = NetDuel(1, 1)
        val (_, msg) = a.local(DuelMove.Place(0, Hex.ORIGIN))
        assertIs<NetDuel.Remote.Broken>(b.remote(msg!!.copy(checksum = msg.checksum + 1)))
        assertIs<NetDuel.Remote.Broken>(b.remote(msg.copy(seq = 1)))
        assertIs<NetDuel.Remote.Ok>(b.remote(msg))
    }

    @Test
    fun rejectedLocalMovesAreNotSent() {
        val a = NetDuel(0, 1)
        val (result, msg) = a.local(DuelMove.Shoot(Hex.ORIGIN))
        assertIs<DuelResult.Rejected>(result)
        assertEquals(null, msg)
    }
}
