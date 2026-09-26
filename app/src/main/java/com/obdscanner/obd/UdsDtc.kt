package com.obdscanner.obd

import com.obdscanner.elm.CanReply
import com.obdscanner.elm.Obd
import com.obdscanner.gm.DtcScheme
import com.obdscanner.gm.GmDtc
import com.obdscanner.gm.GmDtcResult
import com.obdscanner.gm.GmModule
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Full DTC memory of one module, read only: UDS \$19 02 (reportDTCByStatusMask), or for an
 * older ECU KWP2000 \$18 02 FF00 (readDTCByStatus, all groups). Nothing is cleared.
 */
class UdsDtcReader(private val obd: Obd, private val vagNumbers: Boolean = false, private val note: (String) -> Unit) {

    suspend fun read(module: GmModule): GmDtcResult {
        obd.target(module.req, module.resp)
        var uds = request(module, "1902FF")
        // Some ECUs refuse mask bits they don't support instead of masking them.
        if (uds.second == 0x31) uds = request(module, "19020D")
        val result = uds.first?.let { parseUds(module, it) } ?: run {
            val kwp = request(module, "1802FF00")
            kwp.first?.let { parseKwp(module, it) } ?: GmDtcResult(module, emptyList(), when {
                uds.second == null && kwp.second == null -> "нет ответа"
                else -> "не отдаёт ошибки (UDS: %s, KWP: %s)".format(nrcText(uds.second), nrcText(kwp.second))
            }, false)
        }
        note("DTC %s: %s %s".format(module.id, result.result, result.codes.joinToString(" ") { it.full }))
        return result
    }

    private fun nrcText(nrc: Int?) = if (nrc == null) "нет ответа" else "отказ %02X".format(nrc)

    /** [59 02 availMask (DTC_hi DTC_mid FTB status)*] */
    internal fun parseUds(module: GmModule, d: IntArray): GmDtcResult {
        val codes = mutableListOf<GmDtc>()
        var i = 3
        while (i + 3 < d.size) {
            val c = GmDtc(Dtc.decode(d[i], d[i + 1]), d[i + 2], d[i + 3], DtcScheme.UDS)
            if (c !in codes) codes += c
            i += 4
        }
        val tail = (d.size - 3) % 4
        return GmDtcResult(module, codes, (if (codes.isEmpty()) "нет кодов" else "кодов: ${codes.size}") + " (UDS)" +
            if (tail != 0) ", лишних байт в конце: $tail" else "", tail == 0)
    }

    /** [58 count (DTC_hi DTC_lo status)*]. VAG numbers the codes by the two bytes as a 5-digit decimal; others use SAE. */
    internal fun parseKwp(module: GmModule, d: IntArray): GmDtcResult {
        val codes = mutableListOf<GmDtc>()
        var i = 2
        while (i + 2 < d.size) {
            val c = if (vagNumbers) {
                val n = d[i] * 256 + d[i + 1]
                // VAG 16384 + N is SAE P0N (16684 = P0300, 16555 = P0171); other VAG numbers have no SAE form.
                val code = if (n in 16384..17383) "P%04d".format(n - 16384) else "%05d".format(n)
                GmDtc(code, -1, d[i + 2], DtcScheme.KWP, vag = n)
            } else GmDtc(Dtc.decode(d[i], d[i + 1]), -1, d[i + 2], DtcScheme.KWP)
            if (c !in codes) codes += c
            i += 3
        }
        val count = d.getOrElse(1) { -1 }
        return GmDtcResult(module, codes, (if (codes.isEmpty()) "нет кодов" else "кодов: ${codes.size}") + " (KWP)" +
            if (count != codes.size) ", блок сообщил $count" else "", count == codes.size)
    }

    /** Returns (reply data, NRC); both null = silence. "7F xx 78" (wait) → ask again with a long timeout. */
    private suspend fun request(module: GmModule, req: String): Pair<IntArray?, Int?> {
        val service = req.substring(0, 2).toInt(16)
        var reply: CanReply = obd.request(req, 3000)
        var msg = answer(reply, module.resp, service)
        if (msg == null && reply.from(module.resp).any { it.nrc == 0x78 }) {
            obd.at("ATAT0")
            obd.at("ATSTFF")
            try {
                reply = obd.request(req, 5000)
                msg = answer(reply, module.resp, service)
            } finally {
                withContext(NonCancellable) {
                    runCatching { obd.at("ATST32") }
                    runCatching { obd.at(obd.adaptiveTiming) }
                }
            }
        }
        msg ?: return null to null
        if (msg.isNegative) return null to msg.nrc
        if (msg.service != service + 0x40) return null to -1
        return msg.data to null
    }

    private fun answer(reply: CanReply, resp: Int, service: Int) =
        reply.from(resp).firstOrNull { it.nrc != 0x78 && (it.service == service + 0x40 || (it.isNegative && it.data.getOrNull(1) == service)) }
}
