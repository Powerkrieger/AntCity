package com.example.antcity.core

import kotlinx.serialization.Serializable

// Request/response bodies of the HTTP API, shared by app and server.

@Serializable
data class RegisterRequest(val name: String)

@Serializable
data class RegisterResponse(val playerId: String, val token: String, val snapshot: Snapshot)

/** The authoritative state, advanced to [serverTime]. */
@Serializable
data class Snapshot(val state: GameState, val version: Long, val serverTime: Long)

@Serializable
data class SyncRequest(
    /** Version of the snapshot the client's pending actions were built on. */
    val baseVersion: Long,
    val actions: List<Action>,
    /** Hash of the client's state right after its last action; lets the server detect divergence. */
    val expectedHash: String? = null,
)

@Serializable
data class ActionResult(val id: String, val accepted: Boolean, val reason: String? = null)

@Serializable
data class SyncResponse(val snapshot: Snapshot, val results: List<ActionResult>)

@Serializable
data class TargetInfo(
    val playerId: String,
    val name: String,
    val townHallLevel: Int,
    val defense: DefenseSummary,
    val shielded: Boolean,
    /** What a full victory would yield, before carry limits. */
    val potentialLoot: Res,
)

@Serializable
data class AttackRequest(val targetId: String, val army: Map<UnitType, Int>)

@Serializable
data class BattleReport(
    val id: Long,
    val at: Long,
    val attackerName: String,
    val defenderName: String,
    val army: Map<UnitType, Int>,
    val outcome: BattleOutcome,
)

@Serializable
data class AttackResponse(val report: BattleReport, val snapshot: Snapshot)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class VerifyResponse(
    val playerId: String,
    val valid: Boolean,
    val entries: Int,
    val matchesStoredState: Boolean,
    val failedAtIndex: Int? = null,
    val reason: String? = null,
)
