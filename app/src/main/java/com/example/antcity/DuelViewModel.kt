package com.example.antcity

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antcity.core.duel.Duel
import com.example.antcity.core.duel.BotLevel
import com.example.antcity.core.duel.DuelConfig
import com.example.antcity.core.duel.DuelMove
import com.example.antcity.core.duel.DuelPlayer
import com.example.antcity.core.duel.DuelResult
import com.example.antcity.core.duel.DuelState
import com.example.antcity.core.duel.Extra
import com.example.antcity.core.duel.NetDuel
import com.example.antcity.core.duel.NetMessage
import com.example.antcity.core.duel.Player
import com.example.antcity.core.duel.Stage
import com.example.antcity.core.duel.actor
import com.example.antcity.core.duel.play
import com.example.antcity.core.hex.Hex
import com.example.antcity.net.LINK_CONFIG
import de.powerizzle.powerlink.Link
import de.powerizzle.powerlink.PowerLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A duel: against a bot of some [BotLevel], two people passing one phone, or against another phone over
 * Wi-Fi, Bluetooth or Wi-Fi Direct (via PowerLink). In pass-and-play the screen is [covered] whenever the phone changes hands.
 */
class DuelViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { BOT, PASS, ONLINE }

    sealed class Lobby {
        data object Choose : Lobby()
        /** Waiting for a guest on every medium PowerLink offers. */
        data class Hosting(val host: PowerLink.LinkHost) : Lobby()
        data object Joining : Lobby()
    }

    /** What each player did to the other's town in the battle that just ended. */
    data class Report(val damage: List<Int>, val destroyed: List<Int>)

    var state by mutableStateOf<DuelState?>(null)
        private set
    var mode by mutableStateOf(Mode.BOT)
        private set
    var level by mutableStateOf(BotLevel.NORMAL)
        private set
    /** The bot for the current match. */
    private var bot: DuelPlayer = level.create()
    /** The player whose screen is showing. */
    var viewer by mutableIntStateOf(0)
        private set
    var covered by mutableStateOf(false)
        private set
    var report by mutableStateOf<Report?>(null)

    // Building
    var selected by mutableStateOf<Int?>(null)
        private set
    var ghost by mutableStateOf<Hex?>(null)
        private set
    /** A taken tile the player tapped. */
    var invalid by mutableStateOf<Hex?>(null)
        private set

    // Battle
    var action by mutableStateOf(Extra.SHOT)
    var showOwn by mutableStateOf(false)
    /** The scout or shot just sent, for its animation; [Strike.id] tells repeats on one tile apart. */
    var strike by mutableStateOf<Strike?>(null)
        private set

    data class Strike(val hex: Hex, val kind: Extra, val id: Int)

    /** Short feedback (toast). */
    var message by mutableStateOf<String?>(null)
    /** Something the player must acknowledge, like a lost connection. */
    var notice by mutableStateOf<String?>(null)

    // --- Local games ----------------------------------------------------------------------------

    fun start(mode: Mode, level: BotLevel = this.level) {
        require(mode != Mode.ONLINE)
        this.mode = mode
        this.level = level
        bot = level.create()
        begin(Duel.start(DuelConfig(seed = System.currentTimeMillis())), viewer = 0)
        covered = mode == Mode.PASS
    }

    private fun begin(s: DuelState, viewer: Int) {
        this.viewer = viewer
        covered = false
        report = null
        tracked = Stage.BUILD
        clearSelection()
        state = s
    }

    fun quit() {
        if (mode == Mode.ONLINE) disconnect(sayBye = true)
        state = null
    }

    fun playAgain() {
        when (mode) {
            Mode.ONLINE -> if (isHost && connected) startOnline()
            else -> start(mode)
        }
    }

    fun uncover() {
        covered = false
    }

    // --- Input ----------------------------------------------------------------------------------

    private fun clearSelection() {
        selected = null
        ghost = null
        invalid = null
        strike = null
        showOwn = false
        // Scouting first sets the stage for the shots.
        action = if ((state?.battle?.budgets?.getOrNull(viewer)?.scouts ?: 0) > 0) Extra.SCOUT else Extra.SHOT
    }

    fun select(offer: Int) {
        selected = if (selected == offer) null else offer
        ghost = null
        invalid = null
    }

    fun tapOwn(hex: Hex) {
        val s = state ?: return
        if (selected == null || s.stage != Stage.BUILD) return
        val free = s.players[viewer].board.isFree(hex)
        ghost = hex.takeIf { free }
        invalid = hex.takeIf { !free }
    }

    /** A card is being dragged towards the board. */
    fun beginDrag(offer: Int) {
        selected = offer
        ghost = null
        invalid = null
    }

    /** Puts the selected building on [hex] while dragging; off the board or on a taken tile it's lifted off. */
    fun moveGhost(hex: Hex?) {
        val s = state ?: return
        if (selected == null || s.stage != Stage.BUILD) return
        ghost = hex?.takeIf(s.players[viewer].board::isFree)
        invalid = null
    }

    /** Takes the placed building back off the board; it stays selected. */
    fun unplace() {
        ghost = null
        invalid = null
    }

    fun confirm() {
        val offer = selected ?: return
        val hex = ghost ?: return
        act(DuelMove.Place(offer, hex))
    }

    fun tapEnemy(hex: Hex) {
        val s = state ?: return
        if (s.stage != Stage.BATTLE || showOwn || s.battle?.attacker != viewer) return
        val budget = s.battle!!.budgets[viewer]
        val move = when {
            action == Extra.SCOUT && budget.scouts > 0 -> DuelMove.Scout(hex)
            action == Extra.SHOT && budget.shots > 0 -> DuelMove.Shoot(hex)
            else -> return
        }
        strike = Strike(hex, action, (strike?.id ?: 0) + 1)
        act(move)
        // Out of scouts: on to the shots.
        if (action == Extra.SCOUT && (state?.battle?.budgets?.getOrNull(viewer)?.scouts ?: 0) == 0) action = Extra.SHOT
    }

    fun buy(extra: Extra) {
        action = extra
        act(DuelMove.Buy(extra))
    }

    fun endTurn() = act(DuelMove.EndTurn)

    private fun act(move: DuelMove) {
        val s = state ?: return
        val net = net
        if (mode == Mode.ONLINE && net != null) {
            val (r, msg) = net.local(move)
            if (r is DuelResult.Rejected) message = r.reason
            if (msg != null) {
                outbox.trySend(msg)
                show(net.state)
            }
        } else {
            when (val r = move.apply(s, viewer)) {
                is DuelResult.Rejected -> message = r.reason
                is DuelResult.Ok -> show(r.state)
            }
        }
    }

    /** Shows a new state: lets the bot move, hands the phone over in pass-and-play. */
    private fun show(next: DuelState) {
        val before = state
        var s = next
        track(s)
        if (mode == Mode.BOT) {
            while (s.actor() == 1) {
                s = bot.play(s, 1)
                track(s)
            }
        }
        state = s
        var handedOver = false
        if (mode == Mode.PASS) {
            val who = s.actor()
            if (who != null && who != viewer) {
                viewer = who
                covered = true
                handedOver = true
            }
        }
        // A new player mustn't see the last one's pick and where they put it.
        if (handedOver || before == null || before.stage != s.stage || before.phase != s.phase || before.round != s.round || before.battle?.turn != s.battle?.turn) {
            clearSelection()
        }
    }

    private var tracked = Stage.BUILD
    private var atBattleStart = emptyList<Player>()

    /** Notices battles starting and ending, for the [report]. */
    private fun track(s: DuelState) {
        if (s.stage == Stage.BATTLE && tracked != Stage.BATTLE) atBattleStart = s.players
        if (s.stage != Stage.BATTLE && tracked == Stage.BATTLE) {
            report = Report(
                damage = s.players.indices.map { s.players[it].hits - atBattleStart[it].hits },
                destroyed = s.players.indices.map { s.players[it].kills - atBattleStart[it].kills },
            )
        }
        tracked = s.stage
    }

    // --- Online ---------------------------------------------------------------------------------

    /** Local multiplayer over Wi-Fi, Bluetooth and Wi-Fi Direct (shared PowerLink module). */
    val powerLink = PowerLink(app, LINK_CONFIG)
    var lobby by mutableStateOf<Lobby?>(null)
        private set
    var opponent by mutableStateOf<String?>(null)
        private set
    var isHost by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set

    private var link: Link? = null
    /** Kept open during a hosted match: closing it would also end a Wi-Fi Direct group. */
    private var linkHost: PowerLink.LinkHost? = null
    private var net: NetDuel? = null
    private var lobbyJob: Job? = null
    private var linkJob: Job? = null
    /** Outgoing messages, sent one at a time so they can't overtake each other. */
    private var outbox = Channel<NetMessage>(Channel.UNLIMITED)

    private val myName: String
        get() = runCatching { powerLink.bluetoothName }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    fun openLobby() {
        lobby = Lobby.Choose
    }

    fun closeLobby() {
        stopLobby()
        lobby = null
    }

    fun backInLobby() {
        stopLobby()
        lobby = Lobby.Choose
    }

    private fun stopLobby() {
        lobbyJob?.cancel()
        if (link == null) {
            linkHost?.close()
            linkHost = null
        }
    }

    /** Hosts on every medium at once; the first phone that connects is the opponent. */
    fun host() {
        stopLobby()
        val h = powerLink.host(myName)
        h.update("waiting", open = true)
        linkHost = h
        lobby = Lobby.Hosting(h)
        lobbyJob = viewModelScope.launch {
            h.guests.collect { guest ->
                if (link != null) {
                    guest.close() // a duel has two players
                } else {
                    h.update("playing", open = false)
                    connect(host = true, guest)
                }
            }
        }
    }

    fun join() {
        stopLobby()
        lobby = Lobby.Joining
    }

    /** Called by the join panel once it reached a host. */
    fun joined(l: Link) {
        connect(host = false, l)
    }

    private fun connect(host: Boolean, l: Link) {
        link = l
        connected = true
        isHost = host
        opponent = null
        outbox = Channel(Channel.UNLIMITED)
        val out = outbox
        linkJob = viewModelScope.launch {
            launch {
                for (m in out) if (!l.send(m.encode())) break
            }
            while (true) {
                val line = l.receive() ?: break
                val m = runCatching { NetMessage.decode(line) }.getOrNull() ?: continue
                receive(m)
            }
            lost("${opponent ?: "The other player"} left or got out of range")
        }
        outbox.trySend(NetMessage.Hello(NetMessage.PROTOCOL, myName))
        if (host) startOnline()
    }

    /** The host picks the seed and starts (or restarts) the match. */
    private fun startOnline() {
        val seed = System.currentTimeMillis()
        outbox.trySend(NetMessage.Start(seed))
        beginOnline(seed, me = 0)
    }

    private fun beginOnline(seed: Long, me: Int) {
        val n = NetDuel(me, seed)
        net = n
        mode = Mode.ONLINE
        lobby = null
        begin(n.state, viewer = me)
    }

    private fun receive(m: NetMessage) {
        when (m) {
            is NetMessage.Hello -> {
                if (m.protocol != NetMessage.PROTOCOL) lost("The other phone has a different version of the game")
                else opponent = m.name
            }
            is NetMessage.Start -> if (!isHost) beginOnline(m.seed, me = 1)
            is NetMessage.Move -> when (val r = net?.remote(m)) {
                is NetDuel.Remote.Ok -> show(r.state)
                is NetDuel.Remote.Broken -> lost("Out of sync: ${r.reason}")
                null -> lost("Got a move before the match started")
            }
            NetMessage.Bye -> lost("${opponent ?: "The other player"} left")
        }
    }

    private fun lost(reason: String) {
        if (link == null) return
        disconnect(sayBye = false)
        notice = reason
        if (state?.stage != Stage.OVER) state = null
    }

    private fun disconnect(sayBye: Boolean) {
        val l = link ?: return
        link = null
        connected = false
        net = null
        if (sayBye) {
            // Best effort: the socket is closed right after.
            viewModelScope.launch {
                l.send(NetMessage.Bye.encode())
                l.close()
            }
        } else {
            l.close()
        }
        linkJob?.cancel()
        outbox.close()
        linkHost?.close()
        linkHost = null
    }

    override fun onCleared() {
        stopLobby()
        disconnect(sayBye = false)
        linkHost?.close()
    }
}
