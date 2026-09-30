package com.example.antcity.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val GameJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
}

/** Something the player did. Clients upload these; the server replays them. */
@Serializable
sealed class Action {
    /** Client-generated unique id, makes uploads idempotent. */
    abstract val id: String

    /** When the action happened (epoch millis, server clock). */
    abstract val at: Long

    abstract fun withAt(at: Long): Action

    @Serializable
    @SerialName("build")
    data class Build(override val id: String, override val at: Long, val building: BuildingType, val x: Int, val y: Int) : Action() {
        override fun withAt(at: Long) = copy(at = at)
    }

    @Serializable
    @SerialName("upgrade")
    data class Upgrade(override val id: String, override val at: Long, val buildingId: Int) : Action() {
        override fun withAt(at: Long) = copy(at = at)
    }

    @Serializable
    @SerialName("demolish")
    data class Demolish(override val id: String, override val at: Long, val buildingId: Int) : Action() {
        override fun withAt(at: Long) = copy(at = at)
    }

    @Serializable
    @SerialName("train")
    data class Train(override val id: String, override val at: Long, val unit: UnitType, val count: Int) : Action() {
        override fun withAt(at: Long) = copy(at = at)
    }
}

/** State changes caused by the server (other players), not by the player. */
@Serializable
sealed class ServerEvent {
    abstract val at: Long

    /** This city was raided. [loot] is in whole resources. */
    @Serializable
    @SerialName("raided")
    data class Raided(override val at: Long, val loot: Res, val shieldUntil: Long) : ServerEvent()

    /** This city's army returned from a raid. Attacking drops the attacker's own shield. */
    @Serializable
    @SerialName("raid_returned")
    data class RaidReturned(override val at: Long, val lostUnits: Map<UnitType, Int>, val loot: Res) : ServerEvent()
}

/** One entry of a player's permanent history. Genesis + all entries fully determines the state. */
@Serializable
sealed class LogEntry {
    abstract val at: Long

    @Serializable
    @SerialName("action")
    data class PlayerAction(val action: Action) : LogEntry() {
        override val at get() = action.at
    }

    @Serializable
    @SerialName("event")
    data class Server(val event: ServerEvent) : LogEntry() {
        override val at get() = event.at
    }
}
