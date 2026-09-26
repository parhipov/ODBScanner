package com.obdscanner

import com.obdscanner.elm.CanParser
import com.obdscanner.elm.Elm327
import com.obdscanner.elm.ElmReply
import com.obdscanner.elm.Obd
import com.obdscanner.gm.GmModule
import com.obdscanner.gm.GmScanner
import com.obdscanner.obd.Dtc
import com.obdscanner.obd.DtcKind
import com.obdscanner.obd.Make
import com.obdscanner.gm.GmModule as Module
import com.obdscanner.obd.UdsDtcReader
import com.obdscanner.obd.Mode06
import com.obdscanner.obd.Mode09
import com.obdscanner.obd.Pids
import com.obdscanner.transport.MockTransport
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPipelineTest {
    private fun open(): Obd {
        val elm = Elm327(MockTransport()) { d, t -> println("$d $t") }
        elm.open()
        return Obd(elm)
    }

    @Test
    fun parsesMultiFrameWithHeaders() {
        val raw = listOf(
            "7E8 10 14 49 02 01 31 47 36",
            "7E8 21 44 4D 35 37 37 39 38",
            "7E8 22 30 31 32 33 34 35 36",
        ).joinToString("\r")
        val msg = CanParser.parse(ElmReply("0902", raw, false), 3).messages.single()
        assertEquals(0x7E8, msg.header)
        assertEquals("1G6DM577980123456", Mode09.decode(msg.data))
    }

    @Test
    fun parsesVagDtcs() {
        val reader = UdsDtcReader(open(), vagNumbers = true) { }
        val mod = Module(0x7E0, 0x7E8, "01", "")
        // UDS 59 02 FF: P0171 FTB 00 status 09 (failed + confirmed), U0121 status 28 (failed since clear).
        val uds = reader.parseUds(mod, intArrayOf(0x59, 0x02, 0xFF, 0x01, 0x71, 0x00, 0x09, 0xC1, 0x21, 0x00, 0x28))
        assertEquals(listOf("P0171 00", "U0121 00"), uds.codes.map { it.full })
        assertTrue(uds.codes[0].current)
        assertTrue(!uds.codes[1].current)
        assertTrue(uds.complete)
        // KWP 58 02: 0x412C = VAG 16684 = P0300, status 0x60 = present now; 0x462D = VAG 17965, no SAE form.
        val kwp = reader.parseKwp(mod, intArrayOf(0x58, 0x02, 0x41, 0x2C, 0x60, 0x46, 0x2D, 0x20))
        assertEquals(listOf("P0300 (VAG 16684)", "VAG 17965"), kwp.codes.map { it.full })
        assertTrue(kwp.codes[0].current)
        assertTrue(!kwp.codes[1].current)
    }

    /** ISO 9141-2 with headers on: header 48 6B <ECU>, data, checksum; the 5 VIN messages are glued back together. */
    @Test
    fun parsesKline() {
        val pid = CanParser.parse(ElmReply("0100", "SEARCHING...\r48 6B 10 41 00 BE 3E B8 11 C9", false), 3)
        assertEquals(0x10, pid.messages.single().header)
        assertEquals(listOf(0x41, 0x00, 0xBE, 0x3E, 0xB8, 0x11), pid.messages.single().data.toList())
        assertTrue(pid.errors.isEmpty())
        // ISO 14230: the length is in the format byte (83 = 3 data bytes).
        assertEquals(listOf(0x41, 0x0D, 0x32), CanParser.parse(ElmReply("010D", "83 F1 10 41 0D 32 04", false), 3).messages.single().data.toList())
        // A wrong checksum is not taken as a K-line message.
        assertTrue(CanParser.parse(ElmReply("010D", "83 F1 10 41 0D 32 05", false), 3).messages.none { it.header == 0x10 })
        val vin = listOf(
            "BUS INIT: ...OK",
            "48 6B 10 49 02 01 00 00 00 4A 59", "48 6B 10 49 02 02 54 4D 5A 44 4F", "48 6B 10 49 02 03 33 33 56 33 00",
            "48 6B 10 49 02 04 30 30 30 31 D3", "48 6B 10 49 02 05 32 33 34 35 E1",
        ).joinToString("\r")
        val r = CanParser.parse(ElmReply("0902", vin, false), 3)
        assertTrue(r.errors.isEmpty())
        assertEquals("JTMZD33V300012345", Mode09.decode(r.messages.single().data))
        val dtc = CanParser.parse(ElmReply("03", "48 6B 10 43 01 33 00 00 00 00 3A", false), 3).messages.single()
        assertEquals(listOf("P0133"), Dtc.parse(dtc.data, dtc.header, DtcKind.STORED).map { it.code })
    }

    @Test
    fun makeFromVin() {
        assertEquals(Make.GM, Make.fromVin("1G6DM577980123456"))
        assertEquals(Make.GM, Make.fromVin("W0L0AHL3575000000"))
        assertEquals(Make.VAG, Make.fromVin("XW8ZZZ61ZBG000000"))
        assertEquals(Make.VAG, Make.fromVin("wvwzzz6rzcy000000"))
        assertEquals(Make.TOYOTA, Make.fromVin("JTMZD33V300012345"))
        assertEquals(Make.LADA, Make.fromVin("XTAGFK330GY000000"))
        assertEquals(Make.HYUNDAI, Make.fromVin("Z94CT41DBBR000000"))
        assertEquals(Make.OTHER, Make.fromVin("SALLAAA1000000000"))
        assertEquals(Make.OTHER, Make.fromVin(null))
        assertEquals(Make.OTHER, Make.fromVin(""))
    }

    /** Real reply from the car (2026-09-25): the clone lost frame 24 and glued the rest — must not be decoded. */
    @Test
    fun dropsMessageWithMissingFrame() {
        val raw = listOf(
            "7E8 10 49 46 06 01 0A 14 08",
            "7E8 21 14 08 14 08 06 02 0A",
            "7E8 22 14 08 14 08 14 08 06",
            "7E8 23 05 10 00 00 00 00 000 00",
            "7E8 25 00 0E 66 06 08 0A 1E",
            "7E8 26 E1 0E 66 24 E8 06 81",
            "7E8 27 0A 13 B8 00 00 14 08",
            "7E8 28 06 82 0A 14 58 14 08",
            "7E8 29 24 E8 06 86 10 00 00",
            "7E8 2A 00 00 00 00 00 00 00",
        ).joinToString("\r")
        val reply = CanParser.parse(ElmReply("0606", raw, false), 3)
        assertTrue(reply.messages.isEmpty())
        assertTrue(reply.garbled)
    }

    /** Real reply (2026-09-25 19:08:33): clone dropped "64 80" and glued "3335" — baro came out 53 kPa. */
    @Test
    fun dropsBadlySpacedFrame() {
        val raw = "7EA 04 41 42 39 09\r7E8 10 0F 41 42 39 E2 10 06\r7E8 21 3A 0E 6F 2F FE 46 34\r7E8 22 3335 20 31 00"
        val reply = CanParser.parse(ElmReply("0142100E2F4633", raw, false), 3)
        assertEquals(listOf(0x7EA), reply.messages.map { it.header })
        assertTrue(reply.garbled)
    }

    /** Real reply (19:09:27): frame "7E8 21 21 04" lost 5 bytes — "distance with MIL on" came out 1246 km. */
    @Test
    fun dropsShortMiddleFrame() {
        val raw = listOf(
            "7EA 10 11 41 01 00 04 00 00", "7EA 21 21 00 00 30 70 31 04", "7E8 10 14 41 01 00 05 85 05",
            "7EA 22 E6 42 37 F8 AA AA AA", "7E8 21 21 04", "7E8 22 DE 42 38 3A 03 01 01",
        ).joinToString("\r")
        val reply = CanParser.parse(ElmReply("01012130314203", raw, false), 3)
        assertEquals(listOf(0x7EA), reply.messages.map { it.header })
        assertTrue(reply.garbled)
    }

    /** The CTS ECM answers PIDs 56/58 with one byte, not two. */
    @Test
    fun splitsMultiPidWithShortTrimPids() {
        val a = Pids.splitMulti(intArrayOf(0x41, 0x4A, 0x34, 0x4C, 0x19, 0x56, 0x80, 0x58, 0x80), listOf(0x4A, 0x4C, 0x56, 0x58))
        assertEquals(listOf(0x4A, 0x4C, 0x56, 0x58), a.map { it.first })
        assertEquals(listOf(1, 1, 1, 1), a.map { it.second.size })
        val b = Pids.splitMulti(
            intArrayOf(0x41, 0x07, 0x80, 0x08, 0x80, 0x09, 0x80, 0x58, 0x80, 0x01, 0x00, 0x05, 0x85, 0x05),
            listOf(0x07, 0x08, 0x09, 0x58, 0x01),
        )
        assertEquals(listOf(0x07, 0x08, 0x09, 0x58, 0x01), b.map { it.first })
        assertEquals(listOf(0x00, 0x05, 0x85, 0x05), b.last().second.toList())
        // Standard two-byte form still works.
        val c = Pids.splitMulti(intArrayOf(0x41, 0x56, 0x80, 0x7F, 0x0C, 0x10, 0x00), listOf(0x56, 0x0C))
        assertEquals(listOf(2, 2), c.map { it.second.size })
    }

    /** Listening must leave the adapter exactly as usable as before — and survive a cancel mid-way. */
    @Test
    fun busListeningRestoresAdapter() = runBlocking {
        val o = open()
        for (c in listOf("ATZ", "ATE0", "ATH1", "ATS1")) o.at(c)
        o.broadcast()
        assertEquals(2, o.request("0100", 5000).messages.size)

        val windows = mutableListOf<Int>()
        var summary: List<com.obdscanner.bus.BusId> = emptyList()
        com.obdscanner.bus.BusSniffer(o, { println(it) }) { _, frames, _ -> windows += frames.size }
            .run(overviewMs = 1500, perIdMs = 400, onProgress = { _, _ -> }, onSummary = { summary = it })
        assertTrue(summary.any { it.id == 0x0C9 && it.frames > 0 })
        assertTrue(summary.first { it.id == 0x0C9 }.changing.contains('x'))
        assertEquals(2, o.request("0100").messages.size)

        // Cancelled in the middle of a listening window.
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            com.obdscanner.bus.BusSniffer(o, { println(it) }) { _, _, _ -> }
                .run(overviewMs = 5000, onProgress = { _, _ -> }, onSummary = { })
        }
        kotlinx.coroutines.delay(700)
        job.cancel()
        job.join()
        assertEquals(2, o.request("0100").messages.size)
    }

    /** \$A9 streams UUDT frames with raw formatting — afterwards normal requests must work again. */
    @Test
    fun readsGmDtcsFromAllModules() = runBlocking {
        val o = open()
        for (c in listOf("ATZ", "ATE0", "ATH1", "ATS1")) o.at(c)
        val reader = com.obdscanner.gm.GmDtcReader(o) { println(it) }

        val ecm = reader.read(GmModule(0x7E0, 0x7E8, "ECM", ""))
        assertTrue(ecm.complete)
        assertEquals(listOf("P0171 00", "P0304 00", "P0562 00"), ecm.codes.map { it.full })
        assertTrue(ecm.codes[1].current && ecm.codes[1].mil)
        assertTrue(!ecm.codes[0].current)

        val bcm = reader.read(GmModule(0x241, 0x641, "BCM", ""))
        assertEquals(listOf("B1325 03"), bcm.codes.map { it.full })

        val abs = reader.read(GmModule(0x243, 0x643, "ABS", ""))
        assertTrue(abs.codes.isEmpty())
        assertTrue(abs.result.contains("не поддерживает"))

        o.broadcast()
        assertEquals(2, o.request("0100").messages.size)
        assertNotNull(GmScanner(o) { println(it) }.readRaw(0x7E2, 0x7EA, "22", 0x1940))
    }

    @Test
    fun fullPipelineAgainstMockCar() = runBlocking {
        val o = open()
        for (c in listOf("ATZ", "ATE0", "ATH1", "ATS1")) o.at(c)

        val sup = o.request("0100", 5000)
        assertEquals(setOf(0x7E8, 0x7EA), sup.messages.map { it.header }.toSet())

        val multi = o.request("010C0D05")
        val ecm = multi.from(0x7E8).single()
        assertEquals(0x41, ecm.service)
        assertEquals(0x0C, ecm.data[1])
        val rpm = Pids.decode(0x7E8, "01", 0x0C, ecm.data.copyOfRange(2, 4)).single().value!!
        assertTrue(rpm in 500.0..4000.0)

        val vin = o.request("0902", 3000).from(0x7E8).single()
        assertEquals("1G6DM577980123456", Mode09.decode(vin.data))

        val dtc = o.request("03").from(0x7E8).single()
        assertEquals(listOf("P0171", "P0304"), Dtc.parse(dtc.data, 0x7E8, DtcKind.STORED).map { it.code })

        val mis = o.request("06A5").from(0x7E8).single()
        val tests = Mode06.parse(mis.data, 0x7E8)
        assertEquals(2, tests.size)
        assertEquals(4, tests[0].misfireCylinder)
        assertEquals(37.0, tests[1].value, 0.01)

        val sc = GmScanner(o) { println(it) }
        val trans = sc.readRaw(0x7E2, 0x7EA, "22", 0x1940)
        assertNotNull(trans)
        val found = mutableListOf<Int>()
        sc.scan(GmModule(0x7E0, 0x7E8, "ECM", ""), "22", 0x1150..0x1160, { found += it.did }, { _, _ -> })
        assertEquals(listOf(0x1154), found)
        val bcm = sc.readRaw(0x241, 0x641, "22", 0x4001)
        assertNotNull(bcm)
        o.broadcast()
        assertEquals(2, o.request("0100").messages.size)
    }
}
