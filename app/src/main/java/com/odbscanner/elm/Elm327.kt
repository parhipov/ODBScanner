package com.odbscanner.elm

import com.odbscanner.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.concurrent.thread

class ElmReply(val command: String, val text: String, val timedOut: Boolean) {
    /** Non-empty lines without echo and the usual chatter. */
    val lines: List<String> by lazy {
        text.split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != command && !it.startsWith("SEARCHING") && it != "BUS INIT: ..." }
    }
    val isOk get() = !timedOut && lines.any { it == "OK" }
    val isUnknown get() = lines.any { it == "?" }
}

/**
 * Minimal ELM327 driver: one command in flight, reply ends at the '>' prompt.
 * Every TX/RX goes to [log] so the session file shows exactly what the adapter said.
 */
class Elm327(
    private val transport: Transport,
    private val log: (dir: Char, text: String) -> Unit,
) {
    private val rx = Channel<Byte>(Channel.UNLIMITED)
    private val mutex = Mutex()
    @Volatile var alive = false
        private set

    val name get() = transport.name

    fun open() {
        transport.open()
        alive = true
        thread(isDaemon = true, name = "elm-reader") {
            val buf = ByteArray(512)
            try {
                while (true) {
                    val n = transport.input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) rx.trySend(buf[i])
                }
            } catch (_: IOException) {
            } finally {
                alive = false
                rx.close()
            }
        }
    }

    fun close() {
        alive = false
        transport.close()
    }

    suspend fun send(cmd: String, timeoutMs: Long = 1500): ElmReply {
        val r = sendOnce(cmd, timeoutMs)
        return if (r.lines.any { it.contains("STOPPED") }) sendOnce(cmd, timeoutMs) else r
    }

    private suspend fun sendOnce(cmd: String, timeoutMs: Long): ElmReply = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!alive) throw IOException("Адаптер отключён")
            drain()
            log('>', cmd)
            transport.output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            transport.output.flush()
            val sb = StringBuilder()
            val done = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val b = rx.receiveCatching().getOrNull() ?: throw IOException("Связь с адаптером потеряна")
                    val c = (b.toInt() and 0xFF).toChar()
                    if (c == '>') break
                    if (c != '\u0000') sb.append(c)
                }
                true
            }
            val text = sb.toString()
            log('<', text.trim().replace("\r", " | ") + if (done == null) "  [TIMEOUT ${timeoutMs}ms]" else "")
            if (done == null) recoverPrompt()
            ElmReply(cmd, text, done == null)
        }
    }

    /**
     * Passive bus monitoring (ATMA and friends): collects whatever the adapter prints for up to
     * [durationMs], then stops it with a CR. Returns early if the chip quits by itself
     * (e.g. BUFFER FULL on clones). Always leaves the adapter at the prompt, even on cancel.
     */
    suspend fun monitor(cmd: String, durationMs: Long): ElmReply = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!alive) throw IOException("Адаптер отключён")
            drain()
            log('>', cmd)
            transport.output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            transport.output.flush()
            val sb = StringBuilder()
            var ended = false
            try {
                withTimeoutOrNull(durationMs) {
                    while (true) {
                        val b = rx.receiveCatching().getOrNull() ?: throw IOException("Связь с адаптером потеряна")
                        val c = (b.toInt() and 0xFF).toChar()
                        if (c == '>') break
                        if (c != '\u0000') sb.append(c)
                    }
                    ended = true
                }
            } finally {
                if (!ended) withContext(NonCancellable) { recoverPrompt() }
            }
            val lines = sb.count { it == '\r' }
            log('<', "[monitor: $lines строк, ${sb.length} символов${if (ended) ", адаптер остановился сам: " + sb.lines().lastOrNull { it.isNotBlank() }?.trim() else ""}]")
            ElmReply(cmd, sb.toString(), !ended)
        }
    }

    /** After a timeout the chip may still be busy: poke it and wait for the prompt so the next command isn't eaten. */
    private suspend fun recoverPrompt() {
        runCatching {
            transport.output.write("\r".toByteArray())
            transport.output.flush()
        }
        withTimeoutOrNull(800) {
            while (true) {
                val b = rx.receiveCatching().getOrNull() ?: break
                if (b.toInt().toChar() == '>') break
            }
        }
        drain()
    }

    private fun drain() {
        while (rx.tryReceive().isSuccess) { /* discard */ }
    }
}
