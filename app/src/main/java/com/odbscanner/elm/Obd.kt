package com.odbscanner.elm

/** OBD/CAN request layer on top of the raw ELM327 driver. */
class Obd(val elm: Elm327) {
    /** 3 for 11-bit CAN, 8 for 29-bit. */
    var headerChars = 3
    /** ISO 9141-2 / ISO 14230: standard OBD only — no CAN headers, filters or physical addressing. */
    var kline = false
    var currentHeader: Int? = null
        private set
    var responseFilter: Int? = null
        private set
    /** Clone supports the "expected responses" digit after the request ("22F190 1"). */
    var countDigit = false
    /** Adaptive timing command that worked at init — to restore after a fixed-timeout operation. */
    var adaptiveTiming = "ATAT1"

    suspend fun at(cmd: String, timeoutMs: Long = 1500) = elm.send(cmd, timeoutMs)

    suspend fun request(hex: String, timeoutMs: Long = 1500, expectOne: Boolean = false): CanReply {
        val cmd = if (expectOne && countDigit) hex + "1" else hex
        val first = CanParser.parse(elm.send(cmd, timeoutMs), headerChars)
        if (!first.garbled) return first
        val second = CanParser.parse(elm.send(cmd, timeoutMs), headerChars)
        return if (second.garbled && second.messages.size < first.messages.size) first else second
    }

    /** Header/filter/flow-control are non-default (needed only for GM USDT 0x24x → 0x64x). */
    private var customRouting = false

    /** Physical addressing to one module. Tolerates clones that don't know ATCRA. */
    suspend fun target(req: Int, resp: Int) {
        // ATSH with a CAN id would replace the K-line header (68 6A F1) and break every later request.
        check(!kline) { "адресация блоков есть только на CAN" }
        if (req in 0x7E0..0x7E7 && resp == req + 8) {
            // Standard OBD ids: the default receive filter and automatic flow control already fit.
            if (customRouting) resetRouting()
            if (currentHeader != req) {
                at("ATSH%03X".format(req))
                currentHeader = req
            }
            return
        }
        customRouting = true
        if (currentHeader != req) {
            at("ATSH%03X".format(req))
            at("ATFCSH%03X".format(req))
            at("ATFCSD300000")
            at("ATFCSM1")
            currentHeader = req
        }
        if (responseFilter != resp) {
            val r = at("ATCRA%03X".format(resp))
            if (r.isUnknown) {
                at("ATCF%03X".format(resp))
                at("ATCM7FF")
            }
            responseFilter = resp
        }
    }

    /** After ATZ the adapter is back to defaults (header 7DF, no filters). */
    fun resetState() {
        currentHeader = null
        responseFilter = null
        customRouting = false
    }

    /** Someone changed the adapter's filters behind our back: the next target/broadcast sets everything again. */
    fun forgetRouting() {
        customRouting = true
        responseFilter = null
    }

    /** Back to functional OBD broadcast (7DF, all ECUs answer). */
    suspend fun broadcast() {
        if (kline) return
        if (customRouting) resetRouting()
        if (currentHeader == 0x7DF) return
        at("ATSH7DF")
        currentHeader = 0x7DF
    }

    private suspend fun resetRouting() {
        at("ATFCSM0")
        at("ATAR")
        responseFilter = null
        customRouting = false
    }
}
