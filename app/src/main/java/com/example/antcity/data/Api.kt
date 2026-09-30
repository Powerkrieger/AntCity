package com.example.antcity.data

import com.example.antcity.core.AttackRequest
import com.example.antcity.core.AttackResponse
import com.example.antcity.core.BattleReport
import com.example.antcity.core.ErrorResponse
import com.example.antcity.core.GameJson
import com.example.antcity.core.RegisterRequest
import com.example.antcity.core.RegisterResponse
import com.example.antcity.core.Snapshot
import com.example.antcity.core.SyncRequest
import com.example.antcity.core.SyncResponse
import com.example.antcity.core.TargetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import java.net.HttpURLConnection
import java.net.URL

/** The server answered with an error (as opposed to being unreachable). */
class ApiError(val code: Int, message: String) : Exception(message)

class Api(baseUrl: String, private val token: String? = null) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun register(name: String): RegisterResponse =
        request("POST", "/api/register", GameJson.encodeToString(RegisterRequest.serializer(), RegisterRequest(name)), RegisterResponse.serializer())

    suspend fun state(): Snapshot = request("GET", "/api/state", null, Snapshot.serializer())

    suspend fun sync(req: SyncRequest): SyncResponse =
        request("POST", "/api/sync", GameJson.encodeToString(SyncRequest.serializer(), req), SyncResponse.serializer())

    suspend fun targets(): List<TargetInfo> = request("GET", "/api/targets", null, ListSerializer(TargetInfo.serializer()))

    suspend fun attack(req: AttackRequest): AttackResponse =
        request("POST", "/api/attack", GameJson.encodeToString(AttackRequest.serializer(), req), AttackResponse.serializer())

    suspend fun reports(): List<BattleReport> = request("GET", "/api/reports", null, ListSerializer(BattleReport.serializer()))

    private suspend fun <T> request(method: String, path: String, body: String?, deserializer: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val message = runCatching { GameJson.decodeFromString(ErrorResponse.serializer(), text).error }.getOrDefault("HTTP $code")
                    throw ApiError(code, message)
                }
                GameJson.decodeFromString(deserializer, text)
            } finally {
                conn.disconnect()
            }
        }
}
