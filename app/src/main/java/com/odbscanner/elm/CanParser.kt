package com.odbscanner.elm

/** One reassembled ISO-TP message from one ECU. [data] starts with the service byte (e.g. 0x41). */
class EcuMessage(val header: Int, val data: IntArray) {
    val service get() = data.getOrElse(0) { -1 }
    val isNegative get() = service == 0x7F
    val nrc get() = if (isNegative) data.getOrElse(2) { -1 } else -1
    fun hex(from: Int = 0) = data.drop(from).joinToString(" ") { "%02X".format(it) }
}

class CanReply(val messages: List<EcuMessage>, val errors: List<String>, val raw: String, val timedOut: Boolean) {
    /** Worth asking again: a frame was lost or the adapter choked. */
    val garbled get() = errors.any { it.startsWith(CanParser.CORRUPT) || it.contains("BUFFER FULL") || it.contains("STOPPED") || it.contains("RX ERROR") }
    val noData get() = messages.isEmpty()
    fun from(header: Int) = messages.filter { it.header == header }
}

/**
 * Parses ELM327 output with headers on (ATH1) for CAN protocols and reassembles ISO-TP frames:
 *   7E8 06 41 00 BE 3F A8 13          single frame
 *   7E8 10 14 49 02 01 31 47 36       first frame
 *   7E8 21 44 50 35 37 37 58 38       consecutive frame
 */
object CanParser {
    private val STATUS = listOf("NO DATA", "CAN ERROR", "BUFFER FULL", "STOPPED", "UNABLE TO CONNECT",
        "BUS ERROR", "BUS BUSY", "DATA ERROR", "FB ERROR", "LV RESET", "ACT ALERT", "ERR", "<RX ERROR", "?")

    fun parse(reply: ElmReply, headerChars: Int): CanReply {
        val errors = mutableListOf<String>()
        val order = mutableListOf<Int>()
        val asm = HashMap<Int, Assembly>()
        val done = mutableListOf<EcuMessage>()
        val corrupted = mutableSetOf<Int>()

        for (line in reply.lines) {
            val compact = line.replace(" ", "")
            if (compact.isEmpty()) continue
            if (!compact.all { it.isHexDigitChar() }) {
                if (STATUS.any { line.contains(it) } || line.isNotBlank()) errors += line
                continue
            }
            val header: Int
            val body: String
            if (headerChars == 3 && compact.length % 2 == 1) {
                header = compact.substring(0, 3).toInt(16)
                body = compact.substring(3)
            } else if (headerChars == 8 && compact.length > 8) {
                header = compact.substring(0, 8).toLong(16).toInt()
                body = compact.substring(8)
            } else {
                // Headers missing (clone ignored ATH1) — treat as unknown ECU, single frames only.
                header = 0
                body = compact
            }
            if (body.length % 2 != 0 || body.isEmpty()) { errors += line; continue }
            if (!wellSpaced(line, headerChars)) {
                // Clone dropped characters mid-line ("7E8 22 3335 20 31 00"): bytes are shifted.
                errors += CORRUPT + " от %03X (искажённая строка «%s»)".format(header, line)
                asm.remove(header)
                corrupted += header
                continue
            }
            val bytes = IntArray(body.length / 2) { body.substring(it * 2, it * 2 + 2).toInt(16) }
            val pci = bytes[0] shr 4
            when (pci) {
                0 -> {
                    val len = bytes[0] and 0x0F
                    if (len == 0 || len > bytes.size - 1) { errors += line; continue }
                    done += EcuMessage(header, bytes.copyOfRange(1, 1 + len))
                    if (header !in order) order += header
                }
                1 -> {
                    if (bytes.size < 8) { errors += line; continue }
                    val len = ((bytes[0] and 0x0F) shl 8) or bytes[1]
                    asm[header] = Assembly(len).also { it.add(bytes, 2) }
                    corrupted -= header
                    if (header !in order) order += header
                }
                2 -> {
                    val a = asm[header]
                    when {
                        a == null -> errors += line
                        !a.nextSeq(bytes[0] and 0x0F) -> {
                            // Old clones drop frames on long replies; shifted data is worse than none.
                            errors += CORRUPT + " от %03X (кадр %X, ждали %X)".format(header, bytes[0] and 0x0F, a.expectedSeq)
                            asm.remove(header)
                            corrupted += header
                        }
                        // Only the last consecutive frame may be short; a short one mid-message lost bytes.
                        bytes.size < 8 && a.size + bytes.size - 1 < a.expected -> {
                            errors += CORRUPT + " от %03X (кадр %X короткий)".format(header, bytes[0] and 0x0F)
                            asm.remove(header)
                            corrupted += header
                        }
                        else -> a.add(bytes, 1)
                    }
                }
                3 -> { /* flow control from someone else — ignore */ }
                else -> errors += line
            }
            asm[header]?.let {
                if (it.complete) {
                    done += EcuMessage(header, it.result())
                    asm.remove(header)
                }
            }
        }
        for ((h, a) in asm) {
            errors += "ISO-TP: неполное сообщение от %03X (%d из %d байт)".format(h, a.size, a.expected)
            if (a.size > 0) done += EcuMessage(h, a.result())
        }
        val sorted = done.sortedBy { order.indexOf(it.header).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        return CanReply(sorted, errors, reply.text, reply.timedOut)
    }

    /** With spaces on (ATS1) every byte is exactly two digits; the 11-bit header is three. */
    private fun wellSpaced(line: String, headerChars: Int): Boolean {
        val t = line.trim().split(' ').filter { it.isNotEmpty() }
        if (t.size < 2) return true
        val bytesFrom = if (headerChars == 3 && t[0].length == 3) 1 else 0
        return t.drop(bytesFrom).all { it.length == 2 }
    }

    private fun Char.isHexDigitChar() = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

    const val CORRUPT = "ISO-TP: пропущен кадр"

    private class Assembly(val expected: Int) {
        private val buf = ArrayList<Int>(expected)
        var expectedSeq = 1
            private set
        fun nextSeq(seq: Int): Boolean {
            if (seq != expectedSeq) return false
            expectedSeq = (expectedSeq + 1) and 0x0F
            return true
        }
        val size get() = buf.size
        val complete get() = buf.size >= expected
        fun add(bytes: IntArray, from: Int) {
            for (i in from until bytes.size) if (buf.size < expected) buf += bytes[i]
        }
        fun result() = buf.toIntArray()
    }
}
