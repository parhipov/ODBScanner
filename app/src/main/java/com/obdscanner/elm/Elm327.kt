package com.obdscanner.elm

import com.obdscanner.transport.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
            .filter { it.isNotEmpty() && it != command && !it.startsWith("SEARCHING") && !(it.startsWith("BUS INIT") && !it.contains("ERROR")) }
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
                log('#', "link closed by the adapter (end of stream)")
            } catch (e: IOException) {
                if (alive) log('#', "link lost: ${e.message}")
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

    /**
     * After a timeout the chip may still be busy. A bare CR is not safe: if the prompt is just
     * late, the chip takes it as "repeat last command" and every later reply is shifted by one
     * (seen on the car: 06A3 got the 06A2 reply, 06A4 got 06A3...). So: wait for a late prompt;
     * if none, abort with a non-CR character, then resync with a harmless command.
     */
    private suspend fun recoverPrompt() {
        if (waitPrompt(400)) return settle()
        write("X")
        waitPrompt(800)
        // If the chip was idle after all, "X" is still in its line buffer: "XATI" → "?", still a prompt.
        drain()
        write("ATI\r")
        waitPrompt(800)
        settle()
    }

    private fun write(s: String) {
        runCatching {
            transport.output.write(s.toByteArray(Charsets.US_ASCII))
            transport.output.flush()
        }
    }

    private suspend fun waitPrompt(ms: Long): Boolean = withTimeoutOrNull(ms) {
        while (true) {
            val b = rx.receiveCatching().getOrNull() ?: break
            if (b.toInt().toChar() == '>') break
        }
        true
    } ?: false

    /** Discards whatever is still trickling in until the line is quiet. */
    private suspend fun settle() {
        repeat(10) { n ->
            var got = false
            while (rx.tryReceive().isSuccess) got = true
            if (!got && n > 0) return
            delay(60)
        }
    }

    private fun drain() {
        while (rx.tryReceive().isSuccess) { /* discard */ }
    }
}
