package com.odbscanner.gm

import com.odbscanner.elm.CanReply
import com.odbscanner.elm.Obd
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Read-only exploration of GM modules on HS-CAN: $1A ReadDataByIdentifier (GMLAN) and
 * $22 ReadDataByParameterIdentifier. Nothing here writes, resets or changes sessions.
 */
class GmScanner(private val obd: Obd, private val note: (String) -> Unit) {

    suspend fun probeModules(
        all: List<Pair<Int, Int>> = GmModules.candidates,
        probes: List<String> = listOf("1A90", "22F190", "3E00"),
        name: (Int) -> String = GmModules::name,
        onProgress: (Float, String) -> Unit,
    ): List<GmModule> {
        val found = mutableListOf<GmModule>()
        for ((i, pair) in all.withIndex()) {
            currentCoroutineContext().ensureActive()
            val (req, resp) = pair
            onProgress(i.toFloat() / all.size, "Опрос %03X…".format(req))
            obd.target(req, resp)
            for (p in probes) {
                val r = obd.request(p, timeoutMs = 400, expectOne = true)
                val msg = r.from(resp).firstOrNull()
                if (msg != null) {
                    val what = if (msg.isNegative) "$p → отказ %02X".format(msg.nrc) else "$p → OK"
                    found += GmModule(req, resp, name(req), what)
                    note("GM: module %03X answered: %s".format(req, what))
                    break
                }
            }
        }
        obd.broadcast()
        return found
    }

    /**
     * Reads the identification \$1A DIDs of a module (name, programming date, software and part
     * numbers) — what's needed to look up bulletins and calibration updates.
     */
    suspend fun identify(module: GmModule, onHit: (ScanHit) -> Unit) {
        obd.target(module.req, module.resp)
        var silent = 0
        for (did in IDENTITY) {
            currentCoroutineContext().ensureActive()
            val r = read(module, "1A", did)
            if (r == null) {
                if (++silent >= 3) return
                continue
            }
            silent = 0
            if (r.first == null && r.second == 0x11) return
            r.first?.let { onHit(ScanHit(module.req, module.resp, "1A", did, it)) }
        }
    }

    /** Detects whether this clone supports the response-count digit (big speedup for scans). */
    suspend fun detectCountDigit(module: GmModule) {
        obd.target(module.req, module.resp)
        obd.countDigit = true
        val fast = obd.request("1A90", timeoutMs = 800, expectOne = true)
        if (fast.errors.any { it.contains("?") } || fast.noData) obd.countDigit = false
        note("GM: response-count digit ${if (obd.countDigit) "supported" else "NOT supported"}")
    }

    /**
     * Scans [range] with service [service] ("1A" or "22"). Stops the module early if the
     * service itself is rejected (NRC 11) many times in a row.
     */
    suspend fun scan(
        module: GmModule,
        service: String,
        range: IntRange,
        onHit: (ScanHit) -> Unit,
        onProgress: (Float, String) -> Unit,
    ) {
        obd.target(module.req, module.resp)
        val total = range.last - range.first + 1
        var serviceRejected = 0
        var silent = 0
        for ((n, did) in range.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (n % 8 == 0) onProgress(n.toFloat() / total, "%s %s %s: %d из %d".format(module.id, service, fmtDid(service, did), n, total))
            val r = read(module, service, did) ?: run {
                if (++silent >= 40) {
                    note("GM: %s service %s — 40 silent requests in a row, giving up".format(module.id, service))
                    return
                }
                null
            }
            if (r != null) silent = 0
            when {
                r == null -> Unit
                r.first == null && r.second == 0x11 -> {
                    if (++serviceRejected >= 5) {
                        note("GM: %s rejects service %s (NRC 11), stop".format(module.id, service))
                        return
                    }
                }
                r.first != null -> onHit(ScanHit(module.req, module.resp, service, did, r.first!!))
            }
        }
        onProgress(1f, "Готово")
    }

    /** Returns (data after the DID echo, NRC) or null when nobody answered. */
    suspend fun read(module: GmModule, service: String, did: Int): Pair<IntArray?, Int>? {
        val req = service + fmtDid(service, did)
        var reply: CanReply = obd.request(req, timeoutMs = 600, expectOne = true)
        var msg = answer(reply, module.resp)
        // Truncated multi-frame with the count digit → retry without it.
        if (obd.countDigit && reply.errors.any { it.startsWith("ISO-TP") }) {
            reply = obd.request(req, timeoutMs = 1200)
            msg = answer(reply, module.resp)
        }
        // Only "7F xx 78" (response pending) arrived — the ECM on the car does this for $1A B4:
        // the real answer comes after the prompt and is lost. Ask again with a longer wait.
        if (msg == null && reply.from(module.resp).any { it.nrc == 0x78 }) {
            obd.at("ATAT0")
            obd.at("ATSTFF")
            try {
                reply = obd.request(req, timeoutMs = 3000)
                msg = answer(reply, module.resp)
            } finally {
                withContext(NonCancellable) {
                    runCatching { obd.at("ATST32") }
                    runCatching { obd.at(obd.adaptiveTiming) }
                }
            }
        }
        msg ?: return null
        if (msg.isNegative) return null to msg.nrc
        val echo = if (service == "1A") 2 else 3
        if (msg.service != service.toInt(16) + 0x40 || msg.data.size < echo) return null to -1
        return msg.data.copyOfRange(echo, msg.data.size) to 0
    }

    /** First real answer: "7F xx 78" only means "wait", the reply may follow in the same read. */
    private fun answer(reply: CanReply, resp: Int) = reply.from(resp).firstOrNull { it.nrc != 0x78 }

    suspend fun readRaw(req: Int, resp: Int, service: String, did: Int): IntArray? {
        obd.target(req, resp)
        return read(GmModule(req, resp, "", ""), service, did)?.first
    }

    private fun fmtDid(service: String, did: Int) = if (service == "1A") "%02X".format(did) else "%04X".format(did)

    companion object {
        val IDENTITY = listOf(0x97, 0x99, 0xB4, 0xC0) + (0xC1..0xC6) + listOf(0xCB, 0xCC)
    }
}
