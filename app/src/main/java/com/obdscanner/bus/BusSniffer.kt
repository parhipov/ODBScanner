package com.obdscanner.bus

import com.obdscanner.elm.Obd
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Summary of one CAN id heard on the bus. */
data class BusId(
    val id: Int,
    val frames: Int,
    val hz: Double,
    val last: IntArray,
    /** One char per byte: '.' constant, 'x' changes. */
    val changing: String,
) {
    val idHex get() = "%03X".format(id)
    val lastHex get() = last.joinToString(" ") { "%02X".format(it) }
    override fun equals(other: Any?) = other is BusId && other.id == id && other.frames == frames
    override fun hashCode() = id
}

class BusFrame(val id: Int, val data: IntArray)

/**
 * Passive listening to the normal traffic between modules (not diagnostics — nothing is sent
 * to the modules). Old clones can't pass the whole 500 kbit/s bus over Bluetooth, so:
 *  1. overview: ATMA a few times (clone overflows → restart) to learn which ids exist;
 *  2. per id: hardware filter (ATCRA) + ATMA for a short window — complete stream of that id.
 */
class BusSniffer(
    private val obd: Obd,
    private val note: (String) -> Unit,
    private val onFrames: (window: Int, frames: List<BusFrame>, windowMs: Long) -> Unit,
) {
    suspend fun run(
        overviewMs: Long = 10_000,
        perIdMs: Long = 1_500,
        maxIds: Int = 80,
        onProgress: (Float, String) -> Unit,
        onSummary: (List<BusId>) -> Unit,
    ) {
        val stats = LinkedHashMap<Int, Acc>()
        try {
            // Silent monitoring (the ELM default since v1.4; clones may answer "?" — harmless).
            obd.at("ATCSM1")
            // Raw frames: without auto-formatting the adapter shows every byte, no PCI guessing.
            obd.at("ATCAF0")
            obd.at("ATAR")

            // 1. Overview
            val t0 = System.currentTimeMillis()
            var window = 0
            while (System.currentTimeMillis() - t0 < overviewMs) {
                currentCoroutineContext().ensureActive()
                val left = overviewMs - (System.currentTimeMillis() - t0)
                onProgress((System.currentTimeMillis() - t0).toFloat() / overviewMs * 0.2f, "Обзор шины… найдено ID: ${stats.size}")
                val started = System.currentTimeMillis()
                val r = obd.elm.monitor("ATMA", left.coerceAtLeast(500))
                if (r.lines.any { it == "?" }) {
                    note("BUS: adapter does not support ATMA")
                    onProgress(1f, "Адаптер не поддерживает прослушку (ATMA)")
                    return
                }
                val frames = parse(r.lines)
                onFrames(window++, frames, System.currentTimeMillis() - started)
                for (f in frames) stats.getOrPut(f.id) { Acc() }.overview++
                if (frames.isEmpty() && !r.timedOut) break // nothing on the bus at all
            }
            note("BUS: overview heard ${stats.size} ids")
            onSummary(summary(stats))

            // 2. Per id, with a hardware filter — no overflow, true rate.
            val ids = stats.keys.sorted().take(maxIds)
            for ((i, id) in ids.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress(0.2f + 0.8f * i / ids.size, "Слушаю %03X (%d из %d)".format(id, i + 1, ids.size))
                obd.at("ATCRA%03X".format(id))
                val started = System.currentTimeMillis()
                val r = obd.elm.monitor("ATMA", perIdMs)
                val took = System.currentTimeMillis() - started
                val frames = parse(r.lines).filter { it.id == id }
                onFrames(window++, frames, took)
                stats.getValue(id).apply {
                    windowMs = took
                    count = frames.size
                    frames.forEach { add(it.data) }
                }
                onSummary(summary(stats))
            }
            onProgress(1f, "Готово: ${stats.size} ID")
        } finally {
            // Without CAF1 normal OBD requests stop working — insist until the adapter confirms.
            withContext(NonCancellable) {
                runCatching { obd.at("ATAR") }
                var ok = false
                repeat(4) {
                    if (!ok) ok = runCatching { obd.at("ATCAF1", 2000).isOk }.getOrDefault(false)
                }
                note(if (ok) "BUS: adapter restored (ATCAF1 OK)" else "BUS: WARNING — ATCAF1 not confirmed")
            }
        }
    }

    private fun summary(stats: Map<Int, Acc>) = stats.entries.sortedBy { it.key }.map { (id, a) ->
        BusId(id, a.count, if (a.windowMs > 0) a.count * 1000.0 / a.windowMs else 0.0, a.last ?: IntArray(0), a.changing())
    }

    private class Acc {
        var overview = 0
        var count = 0
        var windowMs = 0L
        var last: IntArray? = null
        private var first: IntArray? = null
        private var diff = BooleanArray(8)
        fun add(d: IntArray) {
            if (first == null) first = d
            first?.let { f -> for (i in d.indices) if (i < 8 && (i >= f.size || f[i] != d[i])) diff[i] = true }
            last = d
        }
        fun changing(): String {
            val n = last?.size ?: 0
            return (0 until n).joinToString("") { if (diff[it]) "x" else "." }
        }
    }

    companion object {
        /** "0C9 00 11 22 33" (ATH1, ATS1) → frame. Anything else (BUFFER FULL, <RX ERROR…) is skipped. */
        fun parse(lines: List<String>): List<BusFrame> = lines.mapNotNull { line ->
            val t = line.trim().split(' ').filter { it.isNotEmpty() }
            if (t.size < 2 || t[0].length != 3) return@mapNotNull null
            val id = t[0].toIntOrNull(16) ?: return@mapNotNull null
            val bytes = t.drop(1)
            if (bytes.any { it.length != 2 }) return@mapNotNull null
            val data = bytes.map { it.toIntOrNull(16) ?: return@mapNotNull null }.toIntArray()
            if (data.size > 8) null else BusFrame(id, data)
        }
    }
}
