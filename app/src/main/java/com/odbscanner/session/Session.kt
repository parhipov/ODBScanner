package com.odbscanner.session

import android.util.Log
import com.odbscanner.obd.Reading
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One connection = one folder:
 *   raw.log    — every byte exchanged with the adapter, with timestamps (debugging)
 *   data.csv   — every decoded value (long format: one row per value)
 *   report.txt — discovery results: ECUs, supported PIDs, VIN, DTCs, Mode 06, GM scan
 *   scan.csv   — GM Mode 22 / 1A scan hits
 *   bus.csv    — passive bus listening (only if it was started)
 */
class Session(val dir: File) {
    private val t0 = System.currentTimeMillis()
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val raw = writer("raw.log")
    private val csv = writer("data.csv")
    private val report = writer("report.txt")
    private val scan = writer("scan.csv")
    @Volatile var closed = false
        private set

    init {
        csv.write("t_ms,time,ecu,key,name,value,text,unit\n")
        scan.write("time,module_req,module_resp,service,id,len,hex,ascii\n")
        report.write("ODB Scanner — сессия ${dir.name}\n")
    }

    private fun writer(name: String) = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, name), true), Charsets.UTF_8))

    private fun now() = clock.format(Date())

    @Synchronized fun raw(direction: Char, text: String) {
        if (closed) return
        Log.d(TAG, "$direction $text")
        raw.write("${now()} $direction $text\n")
    }

    @Synchronized fun note(text: String) {
        if (closed) return
        Log.i(TAG, "# $text")
        raw.write("${now()} # $text\n")
    }

    private val lastLogged = HashMap<String, Pair<Any?, Long>>()

    /** Logs a value when it changes, and unchanged values at most once per second. */
    @Synchronized fun value(r: Reading) {
        if (closed) return
        val v: Any? = r.value ?: r.text
        val prev = lastLogged[r.key]
        if (prev != null && prev.first == v && r.time - prev.second < 1000) return
        lastLogged[r.key] = v to r.time
        val ecu = "%03X".format(r.ecu)
        csv.write("${r.time - t0},${now()},$ecu,${q(r.source)},${q(r.name)},${r.value ?: ""},${q(r.text ?: "")},${q(r.unit)}\n")
    }

    @Synchronized fun report(title: String, body: String) {
        if (closed) return
        report.write("\n=== $title ===\n$body\n")
        report.flush()
    }

    @Synchronized fun scanHit(req: Int, resp: Int, service: String, id: String, data: IntArray) {
        if (closed) return
        val hex = data.joinToString(" ") { "%02X".format(it) }
        val ascii = data.map { if (it in 0x20..0x7E) it.toChar() else '.' }.joinToString("")
        val ids = "%03X,%03X".format(req, resp)
        scan.write("${now()},$ids,$service,$id,${data.size},$hex,${q(ascii)}\n")
        scan.flush()
    }

    private var bus: BufferedWriter? = null

    /** Frames from one listening window. ELM gives no per-frame time, so it's spread over the window. */
    @Synchronized fun busFrames(window: Int, frames: List<Pair<Int, IntArray>>, windowMs: Long) {
        if (closed) return
        val w = bus ?: writer("bus.csv").also {
            it.write("window,t_ms_approx,id,len,hex\n")
            bus = it
        }
        val start = System.currentTimeMillis() - windowMs - t0
        frames.forEachIndexed { i, (id, d) ->
            val t = start + if (frames.size > 1) windowMs * i / (frames.size - 1) else 0
            w.write("$window,$t,%03X,${d.size},".format(id) + d.joinToString(" ") { "%02X".format(it) } + "\n")
        }
        w.flush()
    }

    @Synchronized fun flush() {
        if (closed) return
        raw.flush(); csv.flush(); report.flush(); scan.flush(); bus?.flush()
    }

    @Synchronized fun close() {
        if (closed) return
        flush()
        closed = true
        runCatching { raw.close(); csv.close(); report.close(); scan.close(); bus?.close() }
    }

    companion object {
        /** Logcat tag: `adb logcat ODB:D *:S` shows the adapter exchange live. */
        const val TAG = "ODB"
    }

    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace("\n", " / ") + "\"" else s
}

class SessionStore(private val root: File, private val shareDir: File) {
    init { root.mkdirs() }

    fun create(): Session {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val dir = File(root, name).apply { mkdirs() }
        return Session(dir)
    }

    fun list(): List<File> = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

    fun delete(dir: File) { dir.deleteRecursively() }

    fun size(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Packs a session folder for sharing. */
    fun zip(dir: File): File {
        shareDir.mkdirs()
        shareDir.listFiles()?.forEach { it.delete() }
        val out = File(shareDir, "odb_${dir.name}.zip")
        ZipOutputStream(FileOutputStream(out)).use { z ->
            dir.listFiles()?.filter { it.isFile }?.forEach { f ->
                z.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }
}
