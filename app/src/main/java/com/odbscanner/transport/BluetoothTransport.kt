package com.odbscanner.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Classic Bluetooth (SPP/RFCOMM) — what old ELM327 clones speak.
 * [preferred] — the method that worked last time with this adapter, tried first; [onConnected] gets
 * the one that worked now.
 */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val device: BluetoothDevice,
    private val preferred: String? = null,
    private val log: (String) -> Unit,
    private val onConnected: (String) -> Unit = {},
) : Transport {

    override val name: String = device.name ?: device.address
    private var socket: BluetoothSocket? = null
    override lateinit var input: InputStream
    override lateinit var output: OutputStream

    override fun open() {
        // Clones are picky. Insecure first, like AndrOBD: on the Polo's clone secure hung for minutes while
        // insecure connected in seconds. Then the hidden channel-1 method, then secure.
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "channel 1 (reflection)" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
            "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
        ).sortedByDescending { it.first == preferred }
        // A running discovery slows RFCOMM connects down; cancelling needs BLUETOOTH_SCAN on new Android — best effort.
        runCatching { @Suppress("DEPRECATION") android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
        var last: Exception? = null
        for ((label, make) in attempts) {
            var s: BluetoothSocket? = null
            try {
                log("BT: connect via $label to ${device.address}")
                s = make()
                connectWithTimeout(s, label)
                socket = s
                input = s.inputStream
                output = s.outputStream
                log("BT: connected via $label")
                onConnected(label)
                return
            } catch (e: Exception) {
                log("BT: $label failed: ${e.message}")
                last = e
                runCatching { s?.close() }
            }
        }
        throw IOException("Не удалось подключиться к $name: ${last?.message}", last)
    }

    /**
     * Android waits up to a few minutes on a socket that doesn't answer — seen 60–190 s per attempt on
     * a clone that had just rebooted, while another method then connected in 2 s. Closing the socket
     * from the side makes connect() give up.
     */
    private fun connectWithTimeout(s: BluetoothSocket, label: String) {
        val done = AtomicBoolean(false)
        val watchdog = thread(isDaemon = true, name = "bt-timeout") {
            try {
                Thread.sleep(CONNECT_TIMEOUT_MS)
                if (!done.get()) {
                    log("BT: $label — no answer in ${CONNECT_TIMEOUT_MS / 1000} s, giving up")
                    runCatching { s.close() }
                }
            } catch (_: InterruptedException) {
            }
        }
        try {
            s.connect()
        } finally {
            done.set(true)
            watchdog.interrupt()
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
