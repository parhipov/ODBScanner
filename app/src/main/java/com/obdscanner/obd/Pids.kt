package com.obdscanner.obd

class PidOut(val suffix: String, val name: String, val unit: String, val decimals: Int, val f: (IntArray) -> Double?)

/** Mode 01 / 02 PID. [len] — data bytes (needed to split multi-PID replies). */
class PidDef(
    val pid: Int,
    val len: Int,
    val name: String,
    val outs: List<PidOut>,
    val text: ((IntArray) -> String)? = null,
    /** Doesn't change while driving — read once. */
    val static: Boolean = false,
)

private fun ab(d: IntArray, i: Int = 0) = d[i] * 256 + d[i + 1]
private fun u32(d: IntArray, i: Int) = (d[i].toLong() shl 24) or (d[i + 1].toLong() shl 16) or (d[i + 2].toLong() shl 8) or d[i + 3].toLong()
private fun bit(d: IntArray, byte: Int, b: Int) = (d[byte] shr b) and 1 == 1

object Pids {
    private val list = mutableListOf<PidDef>()
    val byPid: Map<Int, PidDef> by lazy { list.associateBy { it.pid } }

    fun isBitmask(pid: Int) = pid % 0x20 == 0

    private fun num(pid: Int, len: Int, name: String, unit: String, dec: Int = 1, f: (IntArray) -> Double) {
        list += PidDef(pid, len, name, listOf(PidOut("", name, unit, dec, f)))
    }

    private fun multi(pid: Int, len: Int, name: String, vararg outs: PidOut) {
        list += PidDef(pid, len, name, outs.toList())
    }

    private fun txt(pid: Int, len: Int, name: String, static: Boolean = false, f: (IntArray) -> String) {
        list += PidDef(pid, len, name, emptyList(), f, static)
    }

    private val sensors13 = listOf("Б1 Д1", "Б1 Д2", "Б1 Д3", "Б1 Д4", "Б2 Д1", "Б2 Д2", "Б2 Д3", "Б2 Д4")

    init {
        list += PidDef(
            0x01, 4, "Статус мониторов с момента сброса",
            listOf(
                PidOut("MIL", "Check Engine", "", 0) { ((it[0] shr 7) and 1).toDouble() },
                PidOut("DTC", "Ошибок в памяти", "", 0) { (it[0] and 0x7F).toDouble() },
            ),
            text = { Readiness.summary(it) },
        )
        txt(0x02, 2, "DTC стоп-кадра") { Dtc.decode(it[0], it[1]) }
        txt(0x03, 2, "Статус топливной системы") { fuelSystem(it[0]) + if (it[1] != 0) " / Б2: " + fuelSystem(it[1]) else "" }
        num(0x04, 1, "Расчётная нагрузка", "%") { it[0] / 2.55 }
        num(0x05, 1, "Температура ОЖ", "°C", 0) { it[0] - 40.0 }
        num(0x06, 1, "Кратк. коррекция топлива Б1", "%") { it[0] / 1.28 - 100 }
        num(0x07, 1, "Долг. коррекция топлива Б1", "%") { it[0] / 1.28 - 100 }
        num(0x08, 1, "Кратк. коррекция топлива Б2", "%") { it[0] / 1.28 - 100 }
        num(0x09, 1, "Долг. коррекция топлива Б2", "%") { it[0] / 1.28 - 100 }
        num(0x0A, 1, "Давление топлива", "кПа", 0) { it[0] * 3.0 }
        num(0x0B, 1, "Давление во впуске (MAP)", "кПа", 0) { it[0].toDouble() }
        num(0x0C, 2, "Обороты", "об/мин", 0) { ab(it) / 4.0 }
        num(0x0D, 1, "Скорость", "км/ч", 0) { it[0].toDouble() }
        num(0x0E, 1, "Опережение зажигания", "°", 1) { it[0] / 2.0 - 64 }
        num(0x0F, 1, "Температура воздуха на впуске", "°C", 0) { it[0] - 40.0 }
        num(0x10, 2, "Расход воздуха (MAF)", "г/с", 2) { ab(it) / 100.0 }
        num(0x11, 1, "Положение дросселя", "%") { it[0] / 2.55 }
        txt(0x12, 1, "Вторичный воздух") { secondaryAir(it[0]) }
        txt(0x13, 1, "Установленные датчики O2", static = true) { d ->
            sensors13.filterIndexed { i, _ -> bit(d, 0, i) }.joinToString(", ").ifEmpty { "нет" }
        }
        for (i in 0..7) multi(
            0x14 + i, 2, "Датчик O2 ${sensors13[i]}",
            PidOut("V", "Датчик O2 ${sensors13[i]}, напряжение", "В", 3) { it[0] / 200.0 },
            PidOut("T", "Датчик O2 ${sensors13[i]}, коррекция", "%", 1) { if (it[1] == 0xFF) null else it[1] / 1.28 - 100 },
        )
        txt(0x1C, 1, "Стандарт OBD", static = true) { obdStandard(it[0]) }
        txt(0x1D, 1, "Датчики O2 (4 банка)", static = true) { "0x%02X".format(it[0]) }
        txt(0x1E, 1, "Вход PTO") { if (bit(it, 0, 0)) "активен" else "нет" }
        num(0x1F, 2, "Время с пуска двигателя", "с", 0) { ab(it).toDouble() }
        num(0x21, 2, "Пробег с горящим Check", "км", 0) { ab(it).toDouble() }
        num(0x22, 2, "Давление в рампе (отн. вакуума)", "кПа", 1) { ab(it) * 0.079 }
        num(0x23, 2, "Давление в рампе", "кПа", 0) { ab(it) * 10.0 }
        for (i in 0..7) multi(
            0x24 + i, 4, "ШДК ${i + 1} (λ/напряжение)",
            PidOut("L", "ШДК ${i + 1}, λ", "λ", 3) { ab(it) * 2.0 / 65536 },
            PidOut("V", "ШДК ${i + 1}, напряжение", "В", 3) { ab(it, 2) * 8.0 / 65536 },
        )
        num(0x2C, 1, "EGR (команда)", "%") { it[0] / 2.55 }
        num(0x2D, 1, "Ошибка EGR", "%") { it[0] / 1.28 - 100 }
        num(0x2E, 1, "Продувка адсорбера (команда)", "%") { it[0] / 2.55 }
        num(0x2F, 1, "Уровень топлива", "%") { it[0] / 2.55 }
        num(0x30, 1, "Прогревов после сброса ошибок", "", 0) { it[0].toDouble() }
        num(0x31, 2, "Пробег после сброса ошибок", "км", 0) { ab(it).toDouble() }
        num(0x32, 2, "Давление паров EVAP", "Па", 1) { ab(it).let { v -> if (v >= 32768) v - 65536 else v } / 4.0 }
        num(0x33, 1, "Атмосферное давление", "кПа", 0) { it[0].toDouble() }
        for (i in 0..7) multi(
            0x34 + i, 4, "ШДК ${i + 1} (λ/ток)",
            PidOut("L", "ШДК ${i + 1}, λ", "λ", 3) { ab(it) * 2.0 / 65536 },
            PidOut("I", "ШДК ${i + 1}, ток", "мА", 2) { ab(it, 2) / 256.0 - 128 },
        )
        num(0x3C, 2, "Температура катализатора Б1 Д1", "°C", 0) { ab(it) / 10.0 - 40 }
        num(0x3D, 2, "Температура катализатора Б2 Д1", "°C", 0) { ab(it) / 10.0 - 40 }
        num(0x3E, 2, "Температура катализатора Б1 Д2", "°C", 0) { ab(it) / 10.0 - 40 }
        num(0x3F, 2, "Температура катализатора Б2 Д2", "°C", 0) { ab(it) / 10.0 - 40 }
        txt(0x41, 4, "Мониторы текущей поездки") { Readiness.summary(it, thisCycle = true) }
        num(0x42, 2, "Напряжение бортсети (ЭБУ)", "В", 2) { ab(it) / 1000.0 }
        num(0x43, 2, "Абсолютная нагрузка", "%") { ab(it) * 100.0 / 255 }
        num(0x44, 2, "Заданная λ", "λ", 3) { ab(it) * 2.0 / 65536 }
        num(0x45, 1, "Относит. положение дросселя", "%") { it[0] / 2.55 }
        num(0x46, 1, "Температура за бортом", "°C", 0) { it[0] - 40.0 }
        num(0x47, 1, "Дроссель, датчик B", "%") { it[0] / 2.55 }
        num(0x48, 1, "Дроссель, датчик C", "%") { it[0] / 2.55 }
        num(0x49, 1, "Педаль газа, датчик D", "%") { it[0] / 2.55 }
        num(0x4A, 1, "Педаль газа, датчик E", "%") { it[0] / 2.55 }
        num(0x4B, 1, "Педаль газа, датчик F", "%") { it[0] / 2.55 }
        num(0x4C, 1, "Привод дросселя (команда)", "%") { it[0] / 2.55 }
        num(0x4D, 2, "Время с горящим Check", "мин", 0) { ab(it).toDouble() }
        num(0x4E, 2, "Время после сброса ошибок", "мин", 0) { ab(it).toDouble() }
        multi(
            0x4F, 4, "Максимумы шкал",
            PidOut("L", "Макс. λ", "", 0) { it[0].toDouble() },
            PidOut("V", "Макс. напряжение O2", "В", 0) { it[1].toDouble() },
            PidOut("I", "Макс. ток O2", "мА", 0) { it[2].toDouble() },
            PidOut("P", "Макс. MAP", "кПа", 0) { it[3] * 10.0 },
        )
        num(0x50, 4, "Макс. MAF", "г/с", 0) { it[0] * 10.0 }
        txt(0x51, 1, "Тип топлива", static = true) { fuelType(it[0]) }
        num(0x52, 1, "Доля этанола", "%") { it[0] / 2.55 }
        num(0x53, 2, "Абс. давление паров EVAP", "кПа", 3) { ab(it) / 200.0 }
        num(0x54, 2, "Давление паров EVAP (шир.)", "Па", 0) { ab(it) - 32767.0 }
        multi(
            0x55, 2, "Кратк. коррекция по 2-му O2, Б1/Б3",
            PidOut("A", "Кратк. коррекция по 2-му O2, Б1", "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", "Кратк. коррекция по 2-му O2, Б3", "%", 1) { it[1] / 1.28 - 100 },
        )
        multi(
            0x56, 2, "Долг. коррекция по 2-му O2, Б1/Б3",
            PidOut("A", "Долг. коррекция по 2-му O2, Б1", "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", "Долг. коррекция по 2-му O2, Б3", "%", 1) { it[1] / 1.28 - 100 },
        )
        multi(
            0x57, 2, "Кратк. коррекция по 2-му O2, Б2/Б4",
            PidOut("A", "Кратк. коррекция по 2-му O2, Б2", "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", "Кратк. коррекция по 2-му O2, Б4", "%", 1) { it[1] / 1.28 - 100 },
        )
        multi(
            0x58, 2, "Долг. коррекция по 2-му O2, Б2/Б4",
            PidOut("A", "Долг. коррекция по 2-му O2, Б2", "%", 1) { it[0] / 1.28 - 100 },
            PidOut("B", "Долг. коррекция по 2-му O2, Б4", "%", 1) { it[1] / 1.28 - 100 },
        )
        num(0x59, 2, "Абс. давление в рампе", "кПа", 0) { ab(it) * 10.0 }
        num(0x5A, 1, "Относит. положение педали", "%") { it[0] / 2.55 }
        num(0x5B, 1, "Заряд гибридной батареи", "%") { it[0] / 2.55 }
        num(0x5C, 1, "Температура масла", "°C", 0) { it[0] - 40.0 }
        num(0x5D, 2, "Угол начала впрыска", "°", 2) { ab(it) / 128.0 - 210 }
        num(0x5E, 2, "Расход топлива (ЭБУ)", "л/ч", 2) { ab(it) / 20.0 }
        txt(0x5F, 1, "Экостандарт", static = true) { "0x%02X".format(it[0]) }
        num(0x61, 1, "Запрошенный момент", "%", 0) { it[0] - 125.0 }
        num(0x62, 1, "Фактический момент", "%", 0) { it[0] - 125.0 }
        num(0x63, 2, "Номинальный момент", "Н·м", 0) { ab(it).toDouble() }
        multi(
            0x64, 5, "Точки крутящего момента",
            PidOut("0", "Момент на ХХ", "%", 0) { it[0] - 125.0 },
            PidOut("1", "Момент, точка 1", "%", 0) { it[1] - 125.0 },
            PidOut("2", "Момент, точка 2", "%", 0) { it[2] - 125.0 },
            PidOut("3", "Момент, точка 3", "%", 0) { it[3] - 125.0 },
            PidOut("4", "Момент, точка 4", "%", 0) { it[4] - 125.0 },
        )
        multi(
            0x66, 5, "MAF (расширенный)",
            PidOut("A", "MAF, датчик A", "г/с", 2) { if (bit(it, 0, 0)) ab(it, 1) / 32.0 else null },
            PidOut("B", "MAF, датчик B", "г/с", 2) { if (bit(it, 0, 1)) ab(it, 3) / 32.0 else null },
        )
        multi(
            0x67, 3, "Температура ОЖ (расширенная)",
            PidOut("1", "Температура ОЖ, датчик 1", "°C", 0) { if (bit(it, 0, 0)) it[1] - 40.0 else null },
            PidOut("2", "Температура ОЖ, датчик 2", "°C", 0) { if (bit(it, 0, 1)) it[2] - 40.0 else null },
        )
        multi(
            0x68, 7, "Температура воздуха (расширенная)",
            *Array(6) { k -> PidOut("$k", "Темп. воздуха, датчик ${k + 1}", "°C", 0) { if (bit(it, 0, k)) it[k + 1] - 40.0 else null } },
        )
        multi(
            0x78, 9, "Температура выхлопа Б1",
            *Array(4) { k -> PidOut("$k", "Темп. выхлопа Б1 Д${k + 1}", "°C", 0) { if (bit(it, 0, k)) ab(it, 1 + 2 * k) / 10.0 - 40 else null } },
        )
        multi(
            0x79, 9, "Температура выхлопа Б2",
            *Array(4) { k -> PidOut("$k", "Темп. выхлопа Б2 Д${k + 1}", "°C", 0) { if (bit(it, 0, k)) ab(it, 1 + 2 * k) / 10.0 - 40 else null } },
        )
        multi(
            0x7F, 13, "Время работы двигателя",
            PidOut("T", "Моторесурс, всего", "ч", 1) { u32(it, 1) / 3600.0 },
            PidOut("I", "Моторесурс, на ХХ", "ч", 1) { u32(it, 5) / 3600.0 },
        )
        num(0x8E, 1, "Момент трения", "%", 0) { it[0] - 125.0 }
        multi(
            0x9D, 4, "Расход топлива (г/с)",
            PidOut("E", "Расход топлива двигателем", "г/с", 2) { ab(it) / 50.0 },
            PidOut("V", "Расход топлива автомобилем", "г/с", 2) { ab(it, 2) / 50.0 },
        )
        num(0x9E, 2, "Расход выхлопных газов", "кг/ч", 1) { ab(it) / 5.0 }
        num(0xA2, 2, "Цикловая подача топлива", "мг/такт", 2) { ab(it) / 32.0 }
        multi(
            0xA4, 4, "Передача",
            PidOut("G", "Передача", "", 0) { (it[1] shr 4).toDouble() },
            PidOut("R", "Передаточное число", "", 3) { if (bit(it, 0, 1)) ab(it, 2) / 1000.0 else null },
        )
        num(0xA6, 4, "Одометр", "км", 1) { u32(it, 0) / 10.0 }
    }

    fun name(pid: Int) = byPid[pid]?.name ?: "PID %02X".format(pid)

    /**
     * Splits a multi-PID reply ([data] starts with 0x41) into (pid, bytes). The table length is
     * checked against what follows: the next byte must be another requested PID or the end.
     * PIDs 55–58 are two bytes by J1979, but a two-bank GM ECM sends one ("56 80 58 80").
     */
    fun splitMulti(data: IntArray, requested: Collection<Int>): List<Pair<Int, IntArray>> {
        val left = requested.toMutableSet()
        val out = mutableListOf<Pair<Int, IntArray>>()
        var i = 1
        while (i < data.size) {
            val pid = data[i]
            if (!left.remove(pid)) break
            val def = byPid[pid] ?: break
            val lens = if (pid in 0x55..0x58) listOf(def.len, 1) else listOf(def.len)
            val len = lens.firstOrNull { l ->
                val end = i + 1 + l
                end == data.size || (end < data.size && data[end] in left)
            } ?: break
            out += pid to data.copyOfRange(i + 1, i + 1 + len)
            i += 1 + len
        }
        return out
    }

    /** Decodes one PID's data bytes. Unknown PIDs come back as raw hex so nothing is lost. */
    fun decode(ecu: Int, mode: String, pid: Int, d: IntArray): List<Reading> {
        val base = "%s.%02X".format(mode, pid)
        val def = byPid[pid]
        if (def == null) {
            return listOf(Reading(Reading.key(ecu, base), ecu, "PID %02X (сырые данные)".format(pid), null, hex(d), "", 0))
        }
        val out = mutableListOf<Reading>()
        for (o in def.outs) {
            val v = runCatching { o.f(d) }.getOrNull()
            if (v == null && def.outs.size > 1) continue
            val src = if (o.suffix.isEmpty()) base else "$base.${o.suffix}"
            out += Reading(Reading.key(ecu, src), ecu, o.name, v, if (v == null) hex(d) else null, o.unit, o.decimals)
        }
        def.text?.let { f ->
            val t = runCatching { f(d) }.getOrElse { hex(d) }
            val src = if (def.outs.isEmpty()) base else "$base.S"
            out += Reading(Reading.key(ecu, src), ecu, def.name, null, t, "", 0)
        }
        return out
    }

    fun hex(d: IntArray) = d.joinToString(" ") { "%02X".format(it) }

    fun fuelSystem(v: Int) = when (v) {
        0 -> "—"
        1 -> "разомкнутый (не прогрет)"
        2 -> "замкнутый (по O2)"
        4 -> "разомкнутый (нагрузка/торможение)"
        8 -> "разомкнутый (неисправность)"
        16 -> "замкнутый, неисправность O2"
        else -> "0x%02X".format(v)
    }

    private fun secondaryAir(v: Int) = when (v) {
        1 -> "до катализатора"
        2 -> "после катализатора"
        4 -> "в атмосферу/выкл."
        8 -> "по запросу диагностики"
        else -> "0x%02X".format(v)
    }

    private fun obdStandard(v: Int) = when (v) {
        1 -> "OBD-II (CARB)"
        2 -> "OBD (EPA)"
        3 -> "OBD + OBD-II"
        4 -> "OBD-I"
        5 -> "не OBD"
        6 -> "EOBD (Европа)"
        7 -> "EOBD + OBD-II"
        8 -> "EOBD + OBD"
        9 -> "EOBD, OBD, OBD-II"
        10 -> "JOBD"
        11 -> "JOBD + OBD-II"
        12 -> "JOBD + EOBD"
        13 -> "JOBD, EOBD, OBD-II"
        17 -> "EMD"
        18 -> "EMD+"
        19 -> "HD OBD-C"
        20 -> "HD OBD"
        21 -> "WWH OBD"
        23 -> "HD EOBD-I"
        25 -> "HD EOBD-II"
        28 -> "Бразилия OBD-1"
        29 -> "Бразилия OBD-2"
        30 -> "Корея KOBD"
        31 -> "Индия OBD-I"
        32 -> "Индия OBD-II"
        33 -> "HD EOBD-IV"
        else -> "код $v"
    }

    fun fuelType(v: Int) = when (v) {
        0 -> "не указан"
        1 -> "бензин"
        2 -> "метанол"
        3 -> "этанол"
        4 -> "дизель"
        5 -> "пропан (LPG)"
        6 -> "метан (CNG)"
        7 -> "пропан"
        8 -> "электро"
        9 -> "бензин/газ (бензин)"
        10 -> "двухтопл. метанол"
        11 -> "двухтопл. этанол"
        12 -> "двухтопл. LPG"
        13 -> "двухтопл. CNG"
        14 -> "двухтопл. пропан"
        15 -> "двухтопл. электро"
        16 -> "двухтопл. бензин+электро"
        17 -> "гибрид бензин"
        18 -> "гибрид этанол"
        19 -> "гибрид дизель"
        20 -> "гибрид электро"
        else -> "код $v"
    }
}
