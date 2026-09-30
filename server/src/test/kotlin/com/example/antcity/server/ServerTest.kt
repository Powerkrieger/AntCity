package com.example.antcity.server

import com.example.antcity.core.Action
import com.example.antcity.core.ApplyResult
import com.example.antcity.core.AttackRequest
import com.example.antcity.core.AttackResponse
import com.example.antcity.core.BuildingType
import com.example.antcity.core.GameJson
import com.example.antcity.core.GameState
import com.example.antcity.core.NEW_PLAYER_SHIELD_MS
import com.example.antcity.core.RegisterRequest
import com.example.antcity.core.RegisterResponse
import com.example.antcity.core.Sim
import com.example.antcity.core.Snapshot
import com.example.antcity.core.SyncRequest
import com.example.antcity.core.SyncResponse
import com.example.antcity.core.UnitType
import com.example.antcity.core.VerifyResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerTest {
    private var now = 1_700_000_000_000L
    private var n = 0
    private fun id() = "act-${n++}"

    private suspend inline fun <reified T> HttpResponse.body(): T = GameJson.decodeFromString(bodyAsText())

    private suspend inline fun <reified T> HttpClient.postJson(path: String, body: T, token: String? = null) =
        post(path) {
            contentType(ContentType.Application.Json)
            token?.let { header("Authorization", "Bearer $it") }
            setBody(GameJson.encodeToString(body))
        }

    /** Mimics the app: folds pending actions locally and reports the resulting hash. */
    private fun expectedHash(base: GameState, actions: List<Action>) =
        actions.fold(base) { s, a -> (Sim.step(s, a).second as ApplyResult.Ok).state }.hash()

    private fun withServer(block: suspend ApplicationTestBuilder.(GameService) -> Unit) {
        val dbFile = File.createTempFile("antcity", ".db").also { it.deleteOnExit() }
        val service = GameService(Db(dbFile.path), clock = { now })
        testApplication {
            application { module(service, adminToken = "admin") }
            block(service)
        }
    }

    @Test
    fun honestPlayIsAcceptedAndCheatsAreRejected() = withServer { service ->
        val reg = client.postJson("/api/register", RegisterRequest("Alice")).body<RegisterResponse>()
        val base = reg.snapshot

        now += 5_000
        val honest = listOf(
            Action.Build(id(), now, BuildingType.HOUSE, 0, 0),
            Action.Build(id(), now + 100, BuildingType.FARM, 1, 0),
        )
        now += 1_000
        val sync = client.postJson("/api/sync", SyncRequest(base.version, honest, expectedHash(base.state, honest)), reg.token)
            .body<SyncResponse>()
        assertTrue(sync.results.all { it.accepted })
        assertEquals(1, sync.snapshot.version)
        assertTrue(service.integrityEvents().isEmpty())

        // Retransmission of the same actions is idempotent.
        val again = client.postJson("/api/sync", SyncRequest(base.version, honest), reg.token).body<SyncResponse>()
        assertEquals(1, again.snapshot.version)
        assertEquals(sync.snapshot.state.buildings, again.snapshot.state.buildings)

        // Cheat 1: third building while both builders are busy + things we can't afford.
        val cheat = listOf(
            Action.Build(id(), now, BuildingType.TOWER, 2, 0),
            Action.Build(id(), now, BuildingType.HANGAR, 3, 0),
        )
        val r1 = client.postJson("/api/sync", SyncRequest(sync.snapshot.version, cheat), reg.token).body<SyncResponse>()
        assertTrue(r1.results.none { it.accepted })

        // Cheat 2: pretending more time has passed than really has.
        val future = listOf(Action.Upgrade(id(), now + 3_600_000, 1))
        val r2 = client.postJson("/api/sync", SyncRequest(r1.snapshot.version, future), reg.token).body<SyncResponse>()
        assertFalse(r2.results.single().accepted)

        val kinds = service.integrityEvents().map { it.kind }
        assertTrue("rejected_action" in kinds && "future_timestamp" in kinds)

        // The stored city still replays cleanly from genesis.
        val verify = client.get("/api/admin/verify/${reg.playerId}") { header("X-Admin-Token", "admin") }.body<VerifyResponse>()
        assertTrue(verify.valid, verify.reason)
        assertEquals(2, verify.entries)
    }

    @Test
    fun raidMovesLootAndBothCitiesStayVerifiable() = withServer { _ ->
        val attacker = client.postJson("/api/register", RegisterRequest("Attacker")).body<RegisterResponse>()
        val defender = client.postJson("/api/register", RegisterRequest("Defender")).body<RegisterResponse>()

        now += 1_000
        val hangar = listOf(Action.Build(id(), now, BuildingType.HANGAR, 0, 0))
        var snap = client.postJson("/api/sync", SyncRequest(0, hangar), attacker.token).body<SyncResponse>().snapshot

        now += NEW_PLAYER_SHIELD_MS + 60_000
        val train = listOf(Action.Train(id(), now, UnitType.INFANTRY, 7)) // 280 of the 300 starting food; there are no farms
        snap = client.postJson("/api/sync", SyncRequest(snap.version, train), attacker.token).body<SyncResponse>().snapshot
        assertEquals(1, snap.state.training.size)

        now += 10 * 60_000
        snap = client.get("/api/state") { header("Authorization", "Bearer ${attacker.token}") }.body<Snapshot>()
        assertEquals(7, snap.state.unitsOf(UnitType.INFANTRY))

        val res = client.postJson("/api/attack", AttackRequest(defender.playerId, mapOf(UnitType.INFANTRY to 7)), attacker.token)
            .body<AttackResponse>()
        assertTrue(res.report.outcome.victory)
        assertTrue(res.report.outcome.loot.total() > 0)
        assertTrue(res.snapshot.state.wholeStock.food > snap.state.wholeStock.food)

        // Defender is now shielded.
        val again = client.postJson("/api/attack", AttackRequest(defender.playerId, mapOf(UnitType.INFANTRY to 1)), attacker.token)
        assertEquals(409, again.status.value)

        val all = client.get("/api/admin/verify") { header("X-Admin-Token", "admin") }.body<List<VerifyResponse>>()
        assertTrue(all.all { it.valid }, all.toString())
    }
}
