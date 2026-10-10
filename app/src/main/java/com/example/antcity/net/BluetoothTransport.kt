package com.example.antcity.net

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID

/**
 * Classic Bluetooth (RFCOMM) with the platform's own API. The host listens for one guest; the guest
 * connects to a paired or discovered device. Insecure sockets skip the pairing prompt, which is
 * fine for a game.
 */
@SuppressLint("MissingPermission") // Callers request [permissions] first.
class BluetoothTransport(private val context: Context) {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    val available get() = adapter != null
    val enabled get() = adapter?.isEnabled == true
    val hasPermissions get() = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val name: String get() = runCatching { adapter?.name }.getOrNull() ?: Build.MODEL

    data class Peer(val name: String, val address: String, val paired: Boolean, val device: BluetoothDevice)

    private fun BluetoothDevice.toPeer() = Peer(runCatching { name }.getOrNull() ?: address, address, bondState == BluetoothDevice.BOND_BONDED, this)

    suspend fun host(): Link {
        val server = adapter!!.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
        val socket = try {
            cancellableBlocking({ server.close() }) { server.accept() }
        } finally {
            server.close()
        }
        return Link(socket.inputStream, socket.outputStream, socket)
    }

    suspend fun join(peer: Peer): Link {
        adapter!!.cancelDiscovery()
        val socket = peer.device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
        cancellableBlocking({ socket.close() }) { socket.connect() }
        return Link(socket.inputStream, socket.outputStream, socket)
    }

    fun paired(): List<Peer> = adapter?.bondedDevices.orEmpty().map { it.toPeer() }

    /** Devices found by scanning, until the flow is cancelled. */
    fun discover(): Flow<Peer> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val device: BluetoothDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) ?: return
                trySend(device.toPeer())
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_FOUND), ContextCompat.RECEIVER_EXPORTED)
        adapter?.startDiscovery()
        awaitClose {
            adapter?.cancelDiscovery()
            context.unregisterReceiver(receiver)
        }
    }

    companion object {
        private const val SERVICE_NAME = "AntCity duel"
        private val SERVICE_UUID: UUID = UUID.fromString("5b7d8f3e-2c1a-4e6b-9f0d-a17c3e5b2d84")

        val permissions: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                // Scanning for devices needs location before Android 12.
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        const val DISCOVERABLE_SECONDS = 300
        fun enableIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        fun discoverableIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS)
    }
}
