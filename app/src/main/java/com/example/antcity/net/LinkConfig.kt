package com.example.antcity.net

import de.powerizzle.powerlink.LinkConfig
import java.util.UUID

/** How PowerLink finds and connects AntCity phones. Never change these, old and new versions must agree. */
val LINK_CONFIG = LinkConfig(
    app = "antcity",
    tcpPort = 47321,
    discoveryPort = 47322,
    bluetoothUuid = UUID.fromString("5b7d8f3e-2c1a-4e6b-9f0d-a17c3e5b2d84"),
)
