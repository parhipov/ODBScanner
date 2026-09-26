package com.odbscanner.gm

import com.odbscanner.bus.BusSniffer
import com.odbscanner.elm.Obd
import com.odbscanner.obd.Dtc
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Whose status byte it is: GM \$A9, UDS \$19 (ISO 14229) or KWP2000 \$18 (ISO 14230). */
enum class DtcScheme { GM, UDS, KWP }

/**
 * One DTC from a module's full memory, e.g. "C0035 5A" (GM \$A9, GMW3110 8.18) or a UDS \$19 code.
 * [failureType] −1 = the protocol has none (KWP); [vag] — the VAG 5-digit number for KWP codes.
 */
data class GmDtc(val code: String, val failureType: Int, val status: Int, val scheme: DtcScheme = DtcScheme.GM, val vag: Int? = null) {
    val full get() = when {
        vag != null -> if (code.startsWith("P")) "%s (VAG %05d)".format(code, vag) else "VAG %05d".format(vag)
        failureType >= 0 -> "%s %02X".format(code, failureType)
        else -> code
    }
    val description get() = Dtc.describe(code)
    val current get() = when (scheme) {
        DtcScheme.GM -> status and 0x02 != 0
        DtcScheme.UDS -> status and 0x01 != 0
        DtcScheme.KWP -> (status shr 5) and 3 == 3
    }
    val mil get() = status and 0x80 != 0

    val flags: String get() = buildList {
        when (scheme) {
            // GMW3110: 7 MIL, 6 current since power-up, 4 history, 3 failed since clear, 1 current.
            DtcScheme.GM -> {
                if (current) add("активна")
                if (status and 0x40 != 0 && !current) add("была в этом зажигании")
                if (status and 0x10 != 0) add("в истории")
                if (status and 0x08 != 0 && status and 0x12 == 0) add("была после сброса")
            }
            // ISO 14229: 0 testFailed, 1 this cycle, 2 pending, 3 confirmed, 5 failed since clear.
            DtcScheme.UDS -> {
                if (current) add("активна")
                if (status and 0x02 != 0 && !current) add("была в этом цикле")
                if (status and 0x04 != 0) add("ожидает подтверждения")
                if (status and 0x08 != 0) add("подтверждена")
                if (status and 0x20 != 0 && status and 0x0F == 0) add("была после сброса")
            }
            // ISO 14230: bits 6-5 — 11 present now, 10 intermittent, 01 stored, not present.
            DtcScheme.KWP -> when ((status shr 5) and 3) {
                3 -> add("активна")
                2 -> add("спорадическая")
                1 -> add("в памяти, сейчас нет")
            }
        }
        if (mil) add("Check")
    }.joinToString(", ").ifEmpty { "статус %02X".format(status) }
}

/** What one module answered. [complete] — the end-of-list marker arrived. */
data class GmDtcResult(val module: GmModule, val codes: List<GmDtc>, val result: String, val complete: Boolean)

/**
 * Reads the full DTC memory of a GM module: \$A9 \$81 (readStatusOfDTCByStatusMask).
 * Only reading — nothing is cleared. Unlike Mode 03 this also shows non-emission codes and
 * chassis/body modules (ABS, BCM), with the GM failure type byte and the status.
 *
 * The codes don't come as a normal reply: after the request the module streams one UUDT frame
 * per code on 0x5xx ("5E8 81 01 71 00 5A…" = P0171 00, status 5A) and ends with a zero code.
 * An ISO-TP parser can't read that, so for this one request the adapter works with raw frames
 * (ATCAF0) and a receive filter that passes both the usual reply id and the UUDT id.
 */
class GmDtcReader(private val obd: Obd, private val note: (String) -> Unit) {

    suspend fun read(module: GmModule, mask: Int = MASK): GmDtcResult {
        val uudt = 0x500 or (module.resp and 0xFF)
        val codes = mutableListOf<GmDtc>()
        var complete = false
        var nrc: Int? = null
        var bufferFull = false
        // Each frame is one whole code, so a garbled read loses codes but never invents them:
        // on the car a second read of the BCM lost 3 of 5 codes and looked complete. Read again, merge.
        for (attempt in 1..2) {
            val a = readOnce(module, uudt, mask)
            a.codes.forEach { if (it !in codes) codes += it }
            nrc = nrc ?: a.nrc
            bufferFull = bufferFull || a.bufferFull
            if (a.complete && a.clean) { complete = true; break }
            // Refusal or silence — asking again won't help.
            if (a.clean && a.codes.isEmpty() && !a.bufferFull) break
            note("GM DTC %s: попытка %d — %s, повтор".format(module.id, attempt, if (a.clean) "нет конца списка" else "кадры искажены"))
        }
        val result = when {
            codes.isNotEmpty() -> "кодов: ${codes.size}" + if (complete) "" else " (часть кадров потеряна — может быть не всё)"
            complete -> "нет кодов"
            nrc == 0x11 || nrc == 0x12 -> "не поддерживает \$A9 (отказ %02X)".format(nrc)
            nrc != null -> "отказ %02X".format(nrc)
            bufferFull -> "адаптер переполнен (BUFFER FULL)"
            else -> "нет ответа"
        }
        note("GM DTC %s: %s %s".format(module.id, result, codes.joinToString(" ") { it.full }))
        return GmDtcResult(module, codes, result, complete)
    }

    private class Attempt(val codes: List<GmDtc>, val complete: Boolean, val nrc: Int?, val clean: Boolean, val bufferFull: Boolean)

    private suspend fun readOnce(module: GmModule, uudt: Int, mask: Int): Attempt {
        obd.target(module.req, module.resp)
        obd.forgetRouting()
        // "X" = any digit: 7E8 and 5E8 (or 641 and 541) both pass. Clones without it: UUDT only.
        val both = obd.at("ATCRAX%02X".format(module.resp and 0xFF))
        if (both.isUnknown) obd.at("ATCRA%03X".format(uudt))
        val raw = try {
            obd.at("ATCAF0")
            // The frames come ~50 ms apart: fixed 1 s wait instead of adaptive timing.
            obd.at("ATAT0")
            obd.at("ATSTFF")
            obd.elm.send("03A981%02X00000000".format(mask), 15000)
        } finally {
            withContext(NonCancellable) {
                runCatching { obd.at("ATST32") }
                runCatching { obd.at(obd.adaptiveTiming) }
                var ok = false
                repeat(4) { if (!ok) ok = runCatching { obd.at("ATCAF1", 2000).isOk }.getOrDefault(false) }
                if (!ok) note("GM DTC: WARNING — ATCAF1 not confirmed")
                runCatching { obd.at("ATAR") }
            }
        }

        val codes = mutableListOf<GmDtc>()
        var complete = false
        var nrc: Int? = null
        val lost = raw.lines.filter { it != "NO DATA" && BusSniffer.parse(listOf(it)).isEmpty() }
        for (f in BusSniffer.parse(raw.lines)) {
            val d = f.data
            when {
                f.id == uudt && d.size >= 5 && d[0] == 0x81 -> {
                    if (d[1] == 0 && d[2] == 0 && d[3] == 0) complete = true
                    else if (!complete) GmDtc(Dtc.decode(d[1], d[2]), d[3], d[4]).let { if (it !in codes) codes += it }
                }
                // Single-frame USDT reply: "03 7F A9 xx" — refusal or 78 "response pending".
                f.id == module.resp && d.size >= 4 && d[1] == 0x7F && d[2] == 0xA9 -> if (d[3] != 0x78) nrc = d[3]
            }
        }
        return Attempt(codes, complete, nrc, lost.isEmpty(), raw.lines.any { it.contains("BUFFER FULL") })
    }

    companion object {
        /** Current (bit 1), history (4), failed since clear (3), current since power-up (6). */
        const val MASK = 0x5A
    }
}
