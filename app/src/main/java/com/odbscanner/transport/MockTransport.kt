package com.odbscanner.transport

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Fake ELM327 v1.5 plugged into a simulated Cadillac CTS 2.8 — for developing without the car. */
class MockTransport : Transport {
    override val name = "Демо: CTS 2.8 (симуляция)"
    private val queue = LinkedBlockingQueue<Int>()
    private val car = MockCar()
    private val cmd = StringBuilder()

    override val input: InputStream = object : InputStream() {
        override fun read(): Int = queue.take()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val first = queue.take()
            if (first < 0) return -1
            b[off] = first.toByte()
            var n = 1
            while (n < len) {
                val next = queue.peek() ?: break
                if (next < 0) break
                b[off + n] = queue.take().toByte()
                n++
            }
            return n
        }
    }

    override val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            val c = b.toChar()
            if (c == '\r') {
                val line = cmd.toString()
                cmd.clear()
                if (line.isBlank()) return
                val (text, delay) = car.handle(line)
                Thread.sleep(delay)
                for (ch in text + "\r\r>") queue.put(ch.code)
            } else if (c != '\n') {
                cmd.append(c)
            }
        }
    }

    override fun open() {
        Thread.sleep(300)
    }

    override fun close() {
        queue.put(-1)
    }
}

/** Simulated vehicle: ECUs by CAN id, answering OBD and a few GM services. */
class MockCar {
    private val t0 = System.currentTimeMillis()
    private var header = 0x7DF
    private var headers = true
    private var stored = mutableListOf(0x0171, 0x0304)
    private var pending = mutableListOf(0x0174)
    private var protocolKnown = false
    private var rxFilter: Int? = null
    /** Like a real ELM: with auto-formatting off, OBD requests go out without PCI and nobody answers. */
    private var caf = true

    /** Periodic module traffic: id to period in ms. */
    private val busIds = mapOf(0x0C9 to 12, 0x0F1 to 25, 0x1E9 to 20, 0x1F5 to 25, 0x3E9 to 100, 0x4C1 to 500, 0x4D1 to 500, 0x52A to 1000)

    private fun busFrame(id: Int): String {
        // A burst is generated within a millisecond: jitter keeps the rpm bytes moving like on a car.
        val rpm = (jitter(rpm()) * 4).toInt()
        val d = when (id) {
            0x0C9 -> listOf(0x80, rpm shr 8, rpm and 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00)
            0x3E9 -> List(8) { if (it < 2) Random.nextInt(0, 3) else 0 }
            0x4C1 -> listOf(0x00, (coolant() + 40).toInt(), 0x00, 0x00)
            else -> List(8) { if (it == 7) Random.nextInt(0, 256) else it * 0x11 }
        }
        return "%03X ".format(id) + d.joinToString(" ") { "%02X".format(it) }
    }

    /** Like a clone: unfiltered traffic overflows it quickly, a filtered id streams fine. */
    private fun monitor(): Pair<String, Long> {
        val f = rxFilter
        if (f != null) {
            val period = busIds[f] ?: return "" to 1500L
            val n = 1500 / period
            return (List(n) { busFrame(f) } + "STOPPED").joinToString("\r") to 1500L
        }
        val lines = List(120) { busFrame(busIds.keys.random()) } + "BUFFER FULL"
        return lines.joinToString("\r") to 250L
    }

    private val ecmPids = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15,
        0x18, 0x19, 0x1C, 0x1F, 0x20, 0x21, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x3C, 0x3D, 0x40, 0x41, 0x42, 0x43,
        0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x4D, 0x4E, 0x51,
    )
    private val tcmPids = setOf(0x01, 0x0D, 0x1C)

    private fun t() = (System.currentTimeMillis() - t0) / 1000.0
    private fun wave(period: Double, phase: Double = 0.0) = (sin(2 * PI * t() / period + phase) + 1) / 2
    private fun jitter(x: Double) = x * (1 + Random.nextDouble(-0.02, 0.02))

    fun handle(raw: String): Pair<String, Long> {
        val c = raw.uppercase().replace(" ", "")
        if (c == "ATMA") return monitor()
        if (c.startsWith("AT")) return at(c.substring(2)) to 5L
        if (!c.all { it in '0'..'9' || it in 'A'..'F' }) return "?" to 5L
        val hex = if (c.length % 2 == 1) c.dropLast(1) else c
        val req = IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        val delay = if (!protocolKnown) 1500L.also { protocolKnown = true } else 35L
        if (!caf) return (rawRequest(req) ?: "NO DATA") to delay
        val targets = when (header) {
            0x7DF -> listOf(0x7E8, 0x7EA)
            0x7E0 -> listOf(0x7E8)
            0x7E2 -> listOf(0x7EA)
            0x241 -> listOf(0x641)
            0x243 -> listOf(0x643)
            else -> emptyList()
        }
        val lines = mutableListOf<String>()
        for (ecu in targets) {
            val payload = respond(ecu, req, functional = header == 0x7DF) ?: continue
            lines += frame(ecu, payload)
        }
        return (if (lines.isEmpty()) "NO DATA" else lines.joinToString("\r")) to delay
    }

    /**
     * With ATCAF0 the request carries its own PCI byte. Only GMLAN \$A9 81 is simulated: a
     * "response pending" on the USDT id, then one UUDT frame per DTC on 0x5xx and a zero code.
     */
    private fun rawRequest(r: IntArray): String? {
        if (r.size < 4 || r[1] != 0xA9 || r[2] != 0x81) return null
        val resp = when (header) { 0x7E0 -> 0x7E8; 0x7E2 -> 0x7EA; 0x241 -> 0x641; 0x243 -> 0x643; else -> return null }
        fun raw(id: Int, vararg b: Int) = "%03X ".format(id) + (b.toList() + List(8 - b.size) { 0 }).joinToString(" ") { "%02X".format(it) }
        // (DTC high, low, failure type, status); status 0xDA = active + Check, 0x18 = history.
        val codes = when (header) {
            0x7E0 -> stored.map { listOf(it shr 8, it and 0xFF, 0x00, if (it == 0x0304) 0xDA else 0x18) } + listOf(listOf(0x05, 0x62, 0x00, 0x18))
            0x241 -> listOf(listOf(0x93, 0x25, 0x03, 0x18))
            0x243 -> return raw(resp, 0x03, 0x7F, 0xA9, 0x11)
            else -> emptyList()
        }.filter { it[3] and r[3] != 0 }
        val uudt = 0x500 or (resp and 0xFF)
        return (listOf(raw(resp, 0x03, 0x7F, 0xA9, 0x78)) + codes.map { raw(uudt, 0x81, *it.toIntArray()) } + raw(uudt, 0x81, 0, 0, 0))
            .joinToString("\r")
    }

    private fun at(c: String): String = when {
        c == "Z" -> "\r\rELM327 v1.5"
        c == "I" -> "ELM327 v1.5"
        c == "@1" -> "OBDII to RS232 Interpreter"
        c == "RV" -> String.format(java.util.Locale.US, "%.1fV", 13.8 + wave(20.0) * 0.6)
        c == "DPN" -> "A6"
        c == "DP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        c.startsWith("SH") -> { header = c.substring(2).toInt(16); "OK" }
        c.startsWith("CRA") -> { rxFilter = c.substring(3).toIntOrNull(16); "OK" }
        c == "AR" -> { rxFilter = null; "OK" }
        c == "CAF0" -> { caf = false; "OK" }
        c == "CAF1" -> { caf = true; "OK" }
        c == "H1" -> { headers = true; "OK" }
        c == "H0" -> { headers = false; "OK" }
        else -> "OK"
    }

    private fun frame(ecu: Int, p: IntArray): List<String> {
        fun line(bytes: List<Int>) = (if (headers) "%03X ".format(ecu) else "") +
            (bytes + List(8 - bytes.size) { 0x00 }).take(8).joinToString(" ") { "%02X".format(it) }
        if (p.size <= 7) return listOf(line(listOf(p.size) + p.toList()))
        val out = mutableListOf(line(listOf(0x10 or (p.size shr 8), p.size and 0xFF) + p.take(6)))
        var i = 6
        var seq = 1
        while (i < p.size) {
            out += line(listOf(0x20 or (seq and 0x0F)) + p.drop(i).take(7))
            i += 7
            seq++
        }
        return out
    }

    private fun mask(supported: Set<Int>, base: Int): List<Int> {
        var m = 0L
        for (i in 1..32) if (base + i in supported) m = m or (1L shl (32 - i))
        return listOf((m shr 24).toInt() and 0xFF, (m shr 16).toInt() and 0xFF, (m shr 8).toInt() and 0xFF, m.toInt() and 0xFF)
    }

    private fun respond(ecu: Int, r: IntArray, functional: Boolean): IntArray? {
        val svc = r[0]
        val isEcm = ecu == 0x7E8
        return when (svc) {
            0x01 -> {
                val sup = if (isEcm) ecmPids else if (ecu == 0x7EA) tcmPids else return null
                val out = mutableListOf(0x41)
                for (pid in r.drop(1)) {
                    val d = if (pid % 0x20 == 0) mask(sup, pid).takeIf { pid == 0 || pid in sup } else if (pid in sup) pid01(pid) else null
                    if (d != null) { out += pid; out += d }
                }
                if (out.size == 1) null else out.toIntArray()
            }
            0x02 -> {
                if (!isEcm || r.size < 2) return null
                val pid = r[1]
                when {
                    pid == 0x00 -> (listOf(0x42, 0, 0) + mask(ecmPids, 0)).toIntArray()
                    pid == 0x02 -> intArrayOf(0x42, 0x02, 0x00, stored.firstOrNull()?.shr(8) ?: 0, stored.firstOrNull()?.and(0xFF) ?: 0)
                    pid in ecmPids -> (listOf(0x42, pid, 0) + pid01(pid)).toIntArray()
                    else -> null
                }
            }
            0x03, 0x07, 0x0A -> {
                val list = when (svc) { 0x03 -> if (isEcm) stored else emptyList(); 0x07 -> if (isEcm) pending else emptyList(); else -> emptyList() }
                (listOf(svc + 0x40, list.size) + list.flatMap { listOf(it shr 8, it and 0xFF) }).toIntArray()
            }
            0x04 -> { if (isEcm) { stored.clear(); pending.clear() }; intArrayOf(0x44) }
            0x06 -> if (isEcm) mode06(r.getOrElse(1) { 0 }) else null
            0x09 -> mode09(ecu, r.getOrElse(1) { 0 })
            0x1A -> if (functional) null else gm1A(ecu, r.getOrElse(1) { 0 })
            0x22 -> if (functional || r.size < 3) null else gm22(ecu, r[1] * 256 + r[2])
            0x3E -> if (functional) null else intArrayOf(0x7E, 0x00)
            else -> if (functional) null else intArrayOf(0x7F, svc, 0x11)
        }
    }

    private fun rpm() = 700 + 2600 * wave(18.0)
    private fun coolant() = minOf(92.0, 45 + t() * 0.8)

    private fun pid01(pid: Int): List<Int> {
        fun u8(x: Double) = x.toInt().coerceIn(0, 255)
        fun u16(x: Double) = x.toInt().coerceIn(0, 65535).let { listOf(it shr 8, it and 0xFF) }
        val rpm = rpm()
        val load = 18 + 50 * wave(18.0)
        return when (pid) {
            0x01 -> listOf(0x80 or stored.size, 0x07, 0x65, 0x04)
            0x03 -> listOf(if (coolant() < 60) 1 else 2, 0)
            0x04 -> listOf(u8(load * 2.55))
            0x05 -> listOf(u8(coolant() + 40))
            0x06 -> listOf(u8((100 + 4 * sin(t() * 3)) * 1.28))
            0x07 -> listOf(u8(106.0 * 1.28))
            0x08 -> listOf(u8((100 + 5 * sin(t() * 2.7)) * 1.28))
            0x09 -> listOf(u8(109.4 * 1.28))
            0x0B -> listOf(u8(30 + 60 * wave(18.0)))
            0x0C -> u16(jitter(rpm) * 4)
            0x0D -> listOf(u8(90 * wave(60.0)))
            0x0E -> listOf(u8((12 + 20 * wave(18.0, 1.0) + 64) * 2))
            0x0F -> listOf(u8(28 + 40.0))
            0x10 -> u16(jitter(3 + rpm / 3300 * 60) * 100)
            0x11 -> listOf(u8((15 + 60 * wave(18.0)) * 2.55))
            0x13 -> listOf(0x33)
            0x14 -> listOf(u8(if (sin(t() * 6) > 0) 160.0 else 30.0), u8(104 * 1.28))
            0x15 -> listOf(u8(130 + 10 * sin(t())), 0xFF)
            0x18 -> listOf(u8(if (sin(t() * 5.5) > 0) 170.0 else 25.0), u8(107 * 1.28))
            0x19 -> listOf(u8(125 + 10 * sin(t())), 0xFF)
            0x1C -> listOf(6)
            0x1F -> u16(t())
            0x21 -> u16(128.0)
            0x2E -> listOf(u8(30 * wave(40.0) * 2.55))
            0x2F -> listOf(u8(62 * 2.55))
            0x30 -> listOf(12)
            0x31 -> u16(840.0)
            0x32 -> u16((-120.0 * 4 + 65536))
            0x33 -> listOf(99)
            0x3C -> u16((520 + 200 * wave(30.0) + 40) * 10)
            0x3D -> u16((505 + 200 * wave(30.0) + 40) * 10)
            0x41 -> listOf(0x00, 0x07, 0x65, 0x61)
            0x42 -> u16((13.9 + 0.3 * wave(20.0)) * 1000)
            0x43 -> u16(load * 1.1 * 255 / 100)
            0x44 -> u16(if (coolant() < 60) 0.92 * 32768 else 1.0 * 32768)
            0x45 -> listOf(u8((10 + 60 * wave(18.0)) * 2.55))
            0x46 -> listOf(u8(18 + 40.0))
            0x47 -> listOf(u8((15 + 60 * wave(18.0)) * 2.55 * 0.9))
            0x49 -> listOf(u8((16 + 60 * wave(18.0)) * 2.55))
            0x4A -> listOf(u8((8 + 30 * wave(18.0)) * 2.55))
            0x4C -> listOf(u8((15 + 60 * wave(18.0)) * 2.55))
            0x4D -> u16(95.0)
            0x4E -> u16(4210.0)
            0x51 -> listOf(1)
            else -> listOf(0)
        }
    }

    private fun mode06(mid: Int): IntArray? {
        val mids = setOf(0x01, 0x02, 0x05, 0x06, 0x20, 0x21, 0x22, 0x40, 0x41, 0x45, 0x60, 0x80, 0x81, 0x82, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7)
        if (mid % 0x20 == 0) return (listOf(0x46, mid) + mask(mids, mid)).toIntArray()
        if (mid !in mids) return null
        fun rec(tid: Int, uas: Int, v: Int, min: Int, max: Int) = listOf(mid, tid, uas, v shr 8, v and 0xFF, min shr 8, min and 0xFF, max shr 8, max and 0xFF)
        val recs = when (mid) {
            in 0xA1..0xA7 -> {
                val cyl = mid - 0xA1
                val n = if (cyl == 4) 37 else if (cyl == 0) 45 else Random.nextInt(0, 3)
                rec(0x0B, 0x24, n / 2, 0, 0xFFFF) + rec(0x0C, 0x24, n, 0, 0xFFFF)
            }
            0x21, 0x22 -> rec(0x83, 0x01, if (mid == 0x21) 180 else 145, 0, 300) + rec(0x84, 0x20, 110, 0, 160)
            0x01, 0x05 -> rec(0x01, 0x0B, 450, 0, 0xFFFF) + rec(0x05, 0x10, 86, 0, 180) + rec(0x06, 0x10, 74, 0, 180)
            0x02, 0x06 -> rec(0x07, 0x0B, 80, 0, 200) + rec(0x08, 0x0B, 820, 700, 0xFFFF)
            0x41, 0x45 -> rec(0x81, 0x14, 6, 3, 10)
            0x81, 0x82 -> rec(0x80, 0x2F, 6100, 0, 7000)
            else -> rec(0x80, 0x01, 1, 0, 2)
        }
        return (listOf(0x46) + recs).toIntArray()
    }

    private fun mode09(ecu: Int, type: Int): IntArray? {
        val isEcm = ecu == 0x7E8
        fun ascii(s: String, len: Int) = s.padEnd(len, '\u0000').take(len).map { it.code }
        return when (type) {
            0x00 -> (listOf(0x49, 0x00) + if (isEcm) listOf(0x55, 0x40, 0x00, 0x00) else listOf(0x14, 0x40, 0x00, 0x00)).toIntArray()
            0x02 -> if (isEcm) (listOf(0x49, 0x02, 0x01) + ascii("1G6DM577980123456", 17)).toIntArray() else null
            0x04 -> (listOf(0x49, 0x04, 0x01) + ascii(if (isEcm) "12631416" else "24255818", 16)).toIntArray()
            0x06 -> (listOf(0x49, 0x06, 0x01) + if (isEcm) listOf(0xB7, 0x44, 0x1C, 0x3E) else listOf(0x00, 0x00, 0x51, 0x2A)).toIntArray()
            0x08 -> if (isEcm) (listOf(0x49, 0x08, 0x14) + List(20) { i -> listOf(0, 40 + i * 7) }.flatten()).toIntArray() else null
            0x0A -> (listOf(0x49, 0x0A, 0x01) + ascii(if (isEcm) "ECM-EngineControl" else "TCM-TransmissionCtl", 20)).toIntArray()
            else -> null
        }
    }

    private fun gm1A(ecu: Int, did: Int): IntArray {
        fun ok(bytes: List<Int>) = (listOf(0x5A, did) + bytes).toIntArray()
        return when (did) {
            0x90 -> ok("1G6DM577980123456".map { it.code })
            0xC1 -> if (ecu == 0x7E8) ok(listOf(0x00, 0xC0, 0xB2, 0x78)) else ok(listOf(0x01, 0x72, 0x3B, 0x1A))
            0xCC -> ok("AB".map { it.code })
            0xB4 -> ok("K8290V1234".map { it.code })
            else -> intArrayOf(0x7F, 0x1A, 0x31)
        }
    }

    private fun gm22(ecu: Int, did: Int): IntArray {
        fun ok(vararg b: Int) = intArrayOf(0x62, did shr 8, did and 0xFF, *b)
        return when {
            ecu == 0x7EA && did == 0x1940 -> ok((minOf(85.0, 30 + t() * 0.5) + 40).toInt())
            ecu == 0x7E8 && did == 0x1154 -> ok((coolant() + 40 - 3).toInt())
            ecu == 0x7E8 && did == 0x11A6 -> ok((10 * wave(9.0)).toInt())
            ecu == 0x7E8 && did == 0x1470 -> ok((80 + rpm() / 50).toInt() / 4)
            ecu == 0x7E8 && did == 0x119F -> ok(97)
            ecu == 0x7E8 && did == 0x1208 -> ok(Random.nextInt(0, 4))
            ecu == 0x7E8 && did in listOf(0x1206, 0x1205, 0x1207, 0x11EA, 0x11EB) -> ok(0)
            ecu == 0x7E8 && did in 0x1193..0x1198 -> ((2.4 + rpm() / 2000) * 65.535).toInt().let { ok(it shr 8, it and 0xFF) }
            ecu == 0x7EA && did == 0x1991 -> ok(0, (8 * 35 * wave(18.0)).toInt())
            ecu == 0x7EA && did == 0x199A -> ok(1 + (wave(60.0) * 5).toInt())
            ecu == 0x641 && did == 0x4001 -> ok(0x0C, 0x7B)
            else -> intArrayOf(0x7F, 0x22, 0x31)
        }
    }
}
