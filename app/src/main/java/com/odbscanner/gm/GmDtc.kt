package com.odbscanner.gm

import com.odbscanner.bus.BusSniffer
import com.odbscanner.elm.Obd
import com.odbscanner.obd.Dtc
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** One DTC reported by a GM module through \$A9 (GMW3110 8.18), e.g. "C0035 5A". */
data class GmDtc(val code: String, val failureType: Int, val status: Int) {
    val full get() = "%s %02X".format(code, failureType)
    val description get() = Dtc.describe(code)
    val current get() = status and 0x02 != 0
    val mil get() = status and 0x80 != 0

    /** Status bits (GMW3110): 7 MIL, 6 current since power-up, 4 history, 3 failed since clear, 1 current. */
    val flags: String get() = buildList {
        if (current) add("активна")
        if (status and 0x40 != 0 && !current) add("была в этом зажигании")
        if (status and 0x10 != 0) add("в истории")
        if (status and 0x08 != 0 && status and 0x12 == 0) add("была после сброса")
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
        val result = when {
            codes.isNotEmpty() -> "кодов: ${codes.size}" + if (complete) "" else " (конец списка не пришёл — может быть не всё)"
            complete -> "нет кодов"
            nrc == 0x11 || nrc == 0x12 -> "не поддерживает \$A9 (отказ %02X)".format(nrc)
            nrc != null -> "отказ %02X".format(nrc)
            raw.lines.any { it.contains("BUFFER FULL") } -> "адаптер переполнен (BUFFER FULL)"
            else -> "нет ответа"
        }
        note("GM DTC %s: %s %s".format(module.id, result, codes.joinToString(" ") { it.full }))
        return GmDtcResult(module, codes, result, complete)
    }

    companion object {
        /** Current (bit 1), history (4), failed since clear (3), current since power-up (6). */
        const val MASK = 0x5A
    }
}
