package com.odbscanner

import com.odbscanner.elm.CanParser
import com.odbscanner.elm.Elm327
import com.odbscanner.elm.ElmReply
import com.odbscanner.elm.Obd
import com.odbscanner.gm.GmModule
import com.odbscanner.gm.GmScanner
import com.odbscanner.obd.Dtc
import com.odbscanner.obd.DtcKind
import com.odbscanner.obd.Mode06
import com.odbscanner.obd.Mode09
import com.odbscanner.obd.Pids
import com.odbscanner.transport.MockTransport
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

    /** Listening must leave the adapter exactly as usable as before — and survive a cancel mid-way. */
    @Test
    fun busListeningRestoresAdapter() = runBlocking {
        val o = open()
        for (c in listOf("ATZ", "ATE0", "ATH1", "ATS1")) o.at(c)
        o.broadcast()
        assertEquals(2, o.request("0100", 5000).messages.size)

        val windows = mutableListOf<Int>()
        var summary: List<com.odbscanner.bus.BusId> = emptyList()
        com.odbscanner.bus.BusSniffer(o, { println(it) }) { _, frames, _ -> windows += frames.size }
            .run(overviewMs = 1500, perIdMs = 400, onProgress = { _, _ -> }, onSummary = { summary = it })
        assertTrue(summary.any { it.id == 0x0C9 && it.frames > 0 })
        assertTrue(summary.first { it.id == 0x0C9 }.changing.contains('x'))
        assertEquals(2, o.request("0100").messages.size)

        // Cancelled in the middle of a listening window.
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            com.odbscanner.bus.BusSniffer(o, { println(it) }) { _, _, _ -> }
                .run(overviewMs = 5000, onProgress = { _, _ -> }, onSummary = { })
        }
        kotlinx.coroutines.delay(700)
        job.cancel()
        job.join()
        assertEquals(2, o.request("0100").messages.size)
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
