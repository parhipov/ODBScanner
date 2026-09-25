package com.odbscanner.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Classic Bluetooth (SPP/RFCOMM) — what old ELM327 clones speak. */
@SuppressLint("MissingPermission")
class BluetoothTransport(
    private val device: BluetoothDevice,
    private val log: (String) -> Unit,
) : Transport {

    override val name: String = device.name ?: device.address
    private var socket: BluetoothSocket? = null
    override lateinit var input: InputStream
    override lateinit var output: OutputStream

    override fun open() {
        // Clones are picky: try the standard SPP socket, then insecure, then the hidden channel-1 method.
        val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "secure SPP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            "channel 1 (reflection)" to {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for ((label, make) in attempts) {
            var s: BluetoothSocket? = null
            try {
                log("BT: connect via $label to ${device.address}")
                s = make()
                s.connect()
                socket = s
                input = s.inputStream
                output = s.outputStream
                log("BT: connected via $label")
                return
            } catch (e: Exception) {
                log("BT: $label failed: ${e.message}")
                last = e
                runCatching { s?.close() }
            }
        }
        throw IOException("Не удалось подключиться к $name: ${last?.message}", last)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
