package com.example.antcity.net

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** Plain TCP on the local network, for when Bluetooth isn't an option (and for testing on emulators). */
object WifiTransport {
    const val PORT = 47321
    private const val CONNECT_TIMEOUT_MS = 5_000

    suspend fun host(): Link {
        // Reuse the port right away when hosting again.
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        val socket = try {
            cancellableBlocking({ server.close() }) { server.accept() }
        } finally {
            server.close()
        }
        return Link(socket.getInputStream(), socket.getOutputStream(), socket)
    }

    suspend fun join(address: String): Link {
        val socket = Socket()
        cancellableBlocking({ socket.close() }) { socket.connect(InetSocketAddress(address.trim(), PORT), CONNECT_TIMEOUT_MS) }
        return Link(socket.getInputStream(), socket.getOutputStream(), socket)
    }

    /** This device's IPv4 addresses, for the guest to type in. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
    }.getOrDefault(emptyList())
}
