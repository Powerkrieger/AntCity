package com.example.antcity

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.antcity.core.duel.Duel
import com.example.antcity.core.duel.DuelBot
import com.example.antcity.core.duel.DuelConfig
import com.example.antcity.core.duel.DuelMove
import com.example.antcity.core.duel.DuelResult
import com.example.antcity.core.duel.DuelState
import com.example.antcity.core.duel.Extra
import com.example.antcity.core.duel.NetDuel
import com.example.antcity.core.duel.NetMessage
import com.example.antcity.core.duel.Stage
import com.example.antcity.core.duel.actor
import com.example.antcity.core.duel.play
import com.example.antcity.core.hex.Hex
import com.example.antcity.net.BluetoothTransport
import com.example.antcity.net.Link
import com.example.antcity.net.WifiTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A duel: against [DuelBot], two people passing one phone, or against another phone over
 * Bluetooth or Wi-Fi. In pass-and-play the screen is [covered] whenever the phone changes hands.
 */
class DuelViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { BOT, PASS, ONLINE }
    enum class Transport { BLUETOOTH, WIFI }

    sealed class Lobby {
        data object Choose : Lobby()
        /** Waiting for a guest; [addresses] tell the guest where to find this device. */
        data class Hosting(val transport: Transport, val addresses: List<String>) : Lobby()
        data class Browsing(val peers: List<BluetoothTransport.Peer>) : Lobby()
        data object EnterAddress : Lobby()
        data class Connecting(val name: String) : Lobby()
    }

    /** Buildings each player lost in the battle that just ended. */
    data class Report(val lost: List<Int>)

    var state by mutableStateOf<DuelState?>(null)
        private set
    var mode by mutableStateOf(Mode.BOT)
        private set
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
    /** The tile acted on last, highlighted on the board. */
    var last by mutableStateOf<Hex?>(null)
        private set

    /** Short feedback (toast). */
    var message by mutableStateOf<String?>(null)
    /** Something the player must acknowledge, like a lost connection. */
    var notice by mutableStateOf<String?>(null)

    // --- Local games ----------------------------------------------------------------------------

    fun start(mode: Mode) {
        require(mode != Mode.ONLINE)
        this.mode = mode
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
        last = null
        showOwn = false
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
        last = hex
        act(move)
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
                s = DuelBot.play(s, 1)
                track(s)
            }
        }
        state = s
        if (before == null || before.stage != s.stage || before.phase != s.phase || before.round != s.round || before.battle?.turn != s.battle?.turn) {
            clearSelection()
        }
        if (mode == Mode.PASS) {
            val who = s.actor()
            if (who != null && who != viewer) {
                viewer = who
                covered = true
            }
        }
    }

    private var tracked = Stage.BUILD
    private var killsAtBattleStart = List(Duel.PLAYERS) { 0 }

    /** Notices battles starting and ending, for the [report]. */
    private fun track(s: DuelState) {
        if (s.stage == Stage.BATTLE && tracked != Stage.BATTLE) killsAtBattleStart = s.players.map { it.kills }
        if (s.stage != Stage.BATTLE && tracked == Stage.BATTLE) {
            report = Report(s.players.indices.map { i ->
                val enemy = Duel.opponent(i)
                s.players[enemy].kills - killsAtBattleStart[enemy]
            })
        }
        tracked = s.stage
    }

    // --- Online ---------------------------------------------------------------------------------

    val bluetooth = BluetoothTransport(app)
    var lobby by mutableStateOf<Lobby?>(null)
        private set
    var opponent by mutableStateOf<String?>(null)
        private set
    var isHost by mutableStateOf(false)
        private set
    var connected by mutableStateOf(false)
        private set

    private var link: Link? = null
    private var net: NetDuel? = null
    private var lobbyJob: Job? = null
    private var linkJob: Job? = null
    /** Outgoing messages, sent one at a time so they can't overtake each other. */
    private var outbox = Channel<NetMessage>(Channel.UNLIMITED)

    fun openLobby() {
        lobby = Lobby.Choose
    }

    fun closeLobby() {
        lobbyJob?.cancel()
        lobby = null
    }

    fun backInLobby() {
        lobbyJob?.cancel()
        lobby = Lobby.Choose
    }

    fun host(transport: Transport) {
        lobbyJob?.cancel()
        lobby = Lobby.Hosting(transport, if (transport == Transport.WIFI) WifiTransport.addresses() else listOf(bluetooth.name))
        lobbyJob = viewModelScope.launch {
            connect(host = true) { if (transport == Transport.BLUETOOTH) bluetooth.host() else WifiTransport.host() }
        }
    }

    /** Lists paired devices and scans for more. */
    fun browse() {
        lobbyJob?.cancel()
        lobby = Lobby.Browsing(bluetooth.paired())
        lobbyJob = viewModelScope.launch {
            runCatching {
                bluetooth.discover().collect { peer ->
                    val l = lobby as? Lobby.Browsing ?: return@collect
                    if (l.peers.none { it.address == peer.address }) lobby = l.copy(peers = l.peers + peer)
                }
            }.onFailure { if (it is CancellationException) throw it else message = "Can't scan: ${it.message}" }
        }
    }

    fun join(peer: BluetoothTransport.Peer) {
        lobbyJob?.cancel()
        lobby = Lobby.Connecting(peer.name)
        lobbyJob = viewModelScope.launch { connect(host = false) { bluetooth.join(peer) } }
    }

    fun enterAddress() {
        lobbyJob?.cancel()
        lobby = Lobby.EnterAddress
    }

    fun joinWifi(address: String) {
        lobbyJob?.cancel()
        lobby = Lobby.Connecting(address)
        lobbyJob = viewModelScope.launch { connect(host = false) { WifiTransport.join(address) } }
    }

    private suspend fun connect(host: Boolean, open: suspend () -> Link) {
        val l = try {
            open()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice = "Couldn't connect: ${e.message ?: e.javaClass.simpleName}"
            lobby = Lobby.Choose
            return
        }
        link = l
        connected = true
        isHost = host
        opponent = null
        outbox = Channel(Channel.UNLIMITED)
        val out = outbox
        linkJob = viewModelScope.launch {
            launch {
                for (m in out) l.send(m)
            }
            try {
                l.messages().collect(::receive)
                lost("${opponent ?: "The other player"} left")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lost("Connection lost")
            }
        }
        outbox.trySend(NetMessage.Hello(NetMessage.PROTOCOL, bluetooth.name))
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
                runCatching { l.send(NetMessage.Bye) }
                l.close()
            }
        } else {
            l.close()
        }
        linkJob?.cancel()
        outbox.close()
    }

    override fun onCleared() {
        lobbyJob?.cancel()
        disconnect(sayBye = false)
    }
}
