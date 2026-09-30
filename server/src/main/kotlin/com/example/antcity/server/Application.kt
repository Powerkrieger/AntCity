package com.example.antcity.server

import com.example.antcity.core.AttackRequest
import com.example.antcity.core.ErrorResponse
import com.example.antcity.core.GameJson
import com.example.antcity.core.RegisterRequest
import com.example.antcity.core.SyncRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    val db = Db(System.getenv("DB_PATH") ?: "data/antcity.db")
    val service = GameService(db)
    val adminToken = System.getenv("ADMIN_TOKEN")?.takeIf { it.isNotBlank() }
    embeddedServer(Netty, port = port) { module(service, adminToken) }.start(wait = true)
}

@Serializable
data class IntegrityEventDto(val playerId: String, val playerName: String, val at: Long, val kind: String, val detail: String)

fun Application.module(service: GameService, adminToken: String?) {
    install(ContentNegotiation) { json(GameJson) }
    install(CallLogging)
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorResponse(e.message ?: "Error")) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request")) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
        }
    }

    /** Game logic uses blocking JDBC; keep it off the event loop. */
    suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    suspend fun ApplicationCall.playerId(): String {
        val token = request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            ?: throw ApiException(401, "Missing token")
        return io { service.authenticate(token) } ?: throw ApiException(401, "Invalid token")
    }

    fun ApplicationCall.requireAdmin() {
        if (adminToken == null || request.headers["X-Admin-Token"] != adminToken) throw ApiException(403, "Forbidden")
    }

    routing {
        get("/health") { call.respondText("ok") }

        route("/api") {
            post("/register") {
                val req = call.receive<RegisterRequest>()
                call.respond(io { service.register(req.name) })
            }
            get("/state") {
                val id = call.playerId()
                call.respond(io { service.snapshot(id) })
            }
            post("/sync") {
                val id = call.playerId()
                val req = call.receive<SyncRequest>()
                call.respond(io { service.sync(id, req) })
            }
            get("/targets") {
                val id = call.playerId()
                call.respond(io { service.targets(id) })
            }
            post("/attack") {
                val id = call.playerId()
                val req = call.receive<AttackRequest>()
                call.respond(io { service.attack(id, req) })
            }
            get("/reports") {
                val id = call.playerId()
                call.respond(io { service.reports(id) })
            }

            route("/admin") {
                get("/verify") {
                    call.requireAdmin()
                    call.respond(io { service.verifyAll() })
                }
                get("/verify/{playerId}") {
                    call.requireAdmin()
                    val id = call.parameters["playerId"]!!
                    call.respond(io { service.verify(id) })
                }
                get("/integrity") {
                    call.requireAdmin()
                    call.respond(io { service.integrityEvents().map { IntegrityEventDto(it.playerId, it.playerName, it.at, it.kind, it.detail) } })
                }
            }
        }
    }
}
