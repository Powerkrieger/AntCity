package com.example.antcity.core.duel

import com.example.antcity.core.hex.Hex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One player action, as sent between devices. */
@Serializable
sealed class DuelMove {
    @Serializable @SerialName("place")
    data class Place(val offer: Int, val hex: Hex) : DuelMove()

    @Serializable @SerialName("scout")
    data class Scout(val hex: Hex) : DuelMove()

    @Serializable @SerialName("shoot")
    data class Shoot(val hex: Hex) : DuelMove()

    @Serializable @SerialName("buy")
    data class Buy(val extra: Extra) : DuelMove()

    @Serializable @SerialName("end_turn")
    data object EndTurn : DuelMove()

    fun apply(s: DuelState, player: Int): DuelResult = when (this) {
        is Place -> Duel.place(s, player, offer, hex)
        is Scout -> Duel.scout(s, player, hex)
        is Shoot -> Duel.shoot(s, player, hex)
        is Buy -> Duel.buy(s, player, extra)
        EndTurn -> Duel.endTurn(s, player)
    }
}

/**
 * Messages between two devices playing a duel. One line of JSON each. The host is player 0 and
 * picks the seed; after that both devices run the same rules and only exchange moves.
 */
@Serializable
sealed class NetMessage {
    @Serializable @SerialName("hello")
    data class Hello(val protocol: Int, val name: String) : NetMessage()

    /** Starts a match (also a rematch). Only the host sends it. */
    @Serializable @SerialName("start")
    data class Start(val seed: Long) : NetMessage()

    /** [seq] counts the sender's moves from 0; [checksum] is of the state after the move. */
    @Serializable @SerialName("move")
    data class Move(val seq: Int, val move: DuelMove, val checksum: Long) : NetMessage()

    @Serializable @SerialName("bye")
    data object Bye : NetMessage()

    fun encode() = json.encodeToString(serializer(), this)

    companion object {
        /** Bumped whenever the rules or messages change, so mismatched versions don't play. */
        const val PROTOCOL = 2

        private val json = Json { ignoreUnknownKeys = true }
        fun decode(line: String): NetMessage = json.decodeFromString(serializer(), line)
    }
}

/**
 * A duel against a remote player. Holds the full state on both devices; [me] is this device's
 * player. Moves are applied locally and sent; incoming moves are checked for order and for
 * matching checksums, so the two devices can't silently drift apart.
 */
class NetDuel(val me: Int, seed: Long) {
    var state = Duel.start(DuelConfig(seed = seed))
        private set
    private var sent = 0
    private var received = 0

    sealed class Remote {
        data class Ok(val state: DuelState) : Remote()
        /** The devices disagree; the match can't continue. */
        data class Broken(val reason: String) : Remote()
    }

    /** Applies this player's [move]; returns the message to send, or the rejection. */
    fun local(move: DuelMove): Pair<DuelResult, NetMessage.Move?> {
        val r = move.apply(state, me)
        if (r !is DuelResult.Ok) return r to null
        state = r.state
        return r to NetMessage.Move(sent++, move, checksum(state))
    }

    fun remote(m: NetMessage.Move): Remote {
        if (m.seq != received) return Remote.Broken("Move ${m.seq} arrived, expected $received")
        val r = m.move.apply(state, Duel.opponent(me))
        if (r !is DuelResult.Ok) return Remote.Broken("Opponent's move was rejected: ${(r as DuelResult.Rejected).reason}")
        if (checksum(r.state) != m.checksum) return Remote.Broken("Game states differ")
        received++
        state = r.state
        return Remote.Ok(state)
    }

    companion object {
        /**
         * A hash of everything that matters in [s], identical on every device (unlike hashCode(),
         * which isn't stable for enums across processes).
         */
        fun checksum(s: DuelState): Long {
            val text = buildString {
                append(s.phase).append('/').append(s.round).append('/').append(s.stage.name).append('/').append(s.outcome).append('|')
                s.battle?.let { append(it.order).append(it.turn).append(it.budgets) }
                for (p in s.players) {
                    append('|').append(p.materials).append(',').append(p.kills).append(',').append(p.picked)
                    p.board.buildings.sortedWith(compareBy({ it.hex.q }, { it.hex.r })).forEach { append(';').append(it) }
                    p.intel.entries.sortedWith(compareBy({ it.key.q }, { it.key.r })).forEach { append(';').append(it.key).append(it.value) }
                    p.pending.entries.sortedWith(compareBy({ it.key.q }, { it.key.r })).forEach { append(';').append(it.key).append(it.value) }
                    p.scouted.sortedWith(compareBy({ it.q }, { it.r })).forEach { append(';').append(it) }
                }
            }
            // FNV-1a, 64 bit.
            var h = -0x340d631b7bdddcdbL
            for (c in text) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
