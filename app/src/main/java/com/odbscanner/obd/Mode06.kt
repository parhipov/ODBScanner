package com.odbscanner.obd

/** One on-board monitor test result (Mode 06, CAN format). */
data class TestResult(
    val ecu: Int,
    val mid: Int,
    val tid: Int,
    val uasid: Int,
    val rawValue: Int,
    val rawMin: Int,
    val rawMax: Int,
) {
    private val scale = Mode06.scaling(uasid)
    val value get() = scale.apply(rawValue)
    val min get() = scale.apply(rawMin)
    val max get() = scale.apply(rawMax)
    val unit get() = scale.unit
    /** All zeros = the ECU hasn't run this test since the last clear (common for catalyst/EVAP). */
    val notRun get() = rawValue == 0 && rawMin == 0 && rawMax == 0
    val status get() = when { notRun -> "не выполнялся"; passed -> "OK"; else -> "НЕ ПРОЙДЕН" }
    val passed get() = if (scale.signed) sgn(rawValue) in sgn(rawMin)..sgn(rawMax) else rawValue in rawMin..rawMax
    val midName get() = Mode06.midName(mid)
    val tidName get() = Mode06.tidName(mid, tid)
    val misfireCylinder get() = if (mid in 0xA2..0xAD) mid - 0xA1 else null

    private fun sgn(v: Int) = if (v >= 32768) v - 65536 else v
}

class Scaling(val factor: Double, val unit: String, val offset: Double = 0.0, val signed: Boolean = false) {
    fun apply(raw: Int): Double {
        val v = if (signed && raw >= 32768) raw - 65536 else raw
        return v * factor + offset
    }
}

object Mode06 {
    /** Unit And Scaling IDs (SAE J1979 / ISO 15031-5). Unknown ones fall back to raw counts. */
    private val UAS = mapOf(
        0x01 to Scaling(1.0, ""), 0x02 to Scaling(0.1, ""), 0x03 to Scaling(0.01, ""), 0x04 to Scaling(0.001, ""),
        0x05 to Scaling(0.0000305, ""), 0x06 to Scaling(0.000305, ""),
        0x07 to Scaling(0.25, "об/мин"), 0x08 to Scaling(0.01, "км/ч"), 0x09 to Scaling(1.0, "км/ч"),
        0x0A to Scaling(0.000122, "В"), 0x0B to Scaling(0.001, "В"), 0x0C to Scaling(0.01, "В"),
        0x0D to Scaling(0.00390625, "мА"), 0x0E to Scaling(0.001, "А"), 0x0F to Scaling(0.01, "А"),
        0x10 to Scaling(1.0, "мс"), 0x11 to Scaling(100.0, "мс"), 0x12 to Scaling(1.0, "с"),
        0x13 to Scaling(1.0, "мОм"), 0x14 to Scaling(1.0, "Ом"), 0x15 to Scaling(1.0, "кОм"),
        0x16 to Scaling(0.1, "°C", -40.0), 0x17 to Scaling(0.01, "кПа"), 0x18 to Scaling(0.0117, "кПа"),
        0x19 to Scaling(0.079, "кПа"), 0x1A to Scaling(1.0, "кПа"), 0x1B to Scaling(10.0, "кПа"),
        0x1C to Scaling(0.01, "°"), 0x1D to Scaling(0.5, "°"), 0x1E to Scaling(0.0000305, "λ"),
        0x1F to Scaling(0.05, "AFR"), 0x20 to Scaling(0.0039062, ""), 0x21 to Scaling(0.001, "Гц"),
        0x22 to Scaling(1.0, "Гц"), 0x23 to Scaling(1000.0, "Гц"), 0x24 to Scaling(1.0, "шт"),
        0x25 to Scaling(1.0, "км"), 0x26 to Scaling(0.1, "мВ/мс"), 0x27 to Scaling(0.01, "г/с"),
        0x28 to Scaling(1.0, "г/с"), 0x29 to Scaling(0.25, "Па/с"), 0x2A to Scaling(0.001, "кг/ч"),
        0x2B to Scaling(1.0, "перекл."), 0x2C to Scaling(0.01, "г/цил"), 0x2D to Scaling(0.01, "мг/такт"),
        0x2E to Scaling(1.0, "да/нет"), 0x2F to Scaling(0.01, "%"), 0x30 to Scaling(0.001526, "%"),
        0x31 to Scaling(0.001, "л"), 0x32 to Scaling(0.0007747, "мм"), 0x33 to Scaling(0.00024414, "λ"),
        0x34 to Scaling(1.0, "мин"), 0x35 to Scaling(10.0, "мс"), 0x36 to Scaling(0.01, "г"),
        0x37 to Scaling(0.1, "г"), 0x38 to Scaling(1.0, "г"), 0x39 to Scaling(0.01, "%", -327.68),
        0x3A to Scaling(0.001, "г"), 0x3B to Scaling(0.0001, "г"), 0x3C to Scaling(0.1, "мкс"),
        0x3D to Scaling(0.01, "мА"), 0x3E to Scaling(0.00006103516, "мм²"), 0x3F to Scaling(0.01, "л"),
        0x40 to Scaling(1.0, "ppm"), 0x41 to Scaling(0.01, "мкА"),
        0x81 to Scaling(1.0, "", signed = true), 0x82 to Scaling(0.1, "", signed = true),
        0x83 to Scaling(0.01, "", signed = true), 0x84 to Scaling(0.001, "", signed = true),
        0x85 to Scaling(0.0000305, "", signed = true), 0x86 to Scaling(0.000305, "", signed = true),
        0x8A to Scaling(0.000122, "В", signed = true), 0x8B to Scaling(0.001, "В", signed = true),
        0x8C to Scaling(0.01, "В", signed = true), 0x8D to Scaling(0.00390625, "мА", signed = true),
        0x8E to Scaling(0.001, "А", signed = true), 0x90 to Scaling(1.0, "мс", signed = true),
        0x96 to Scaling(0.1, "°C", signed = true), 0x9C to Scaling(0.01, "°", signed = true),
        0x9D to Scaling(0.5, "°", signed = true), 0xA8 to Scaling(1.0, "г/с", signed = true),
        0xA9 to Scaling(0.25, "Па/с", signed = true), 0xAF to Scaling(0.01, "%", signed = true),
        0xB0 to Scaling(0.003052, "%", signed = true), 0xB1 to Scaling(2.0, "мВ/с", signed = true),
        0xFD to Scaling(0.001, "кПа", signed = true), 0xFE to Scaling(0.25, "Па", signed = true),
    )

    fun scaling(uasid: Int) = UAS[uasid] ?: Scaling(1.0, "raw")

    fun midName(mid: Int): String = when (mid) {
        in 0x01..0x10 -> "Датчик O2 " + listOf("Б1Д1", "Б1Д2", "Б1Д3", "Б1Д4", "Б2Д1", "Б2Д2", "Б2Д3", "Б2Д4",
            "Б3Д1", "Б3Д2", "Б3Д3", "Б3Д4", "Б4Д1", "Б4Д2", "Б4Д3", "Б4Д4")[mid - 1]
        0x21 -> "Катализатор Б1"
        0x22 -> "Катализатор Б2"
        0x23 -> "Катализатор Б3"
        0x24 -> "Катализатор Б4"
        0x31 -> "EGR Б1"
        0x32 -> "EGR Б2"
        0x35 -> "VVT Б1"
        0x36 -> "VVT Б2"
        0x39 -> "EVAP (утечка 0.150\")"
        0x3A -> "EVAP (утечка 0.090\")"
        0x3B -> "EVAP (утечка 0.040\")"
        0x3C -> "EVAP (утечка 0.020\")"
        0x3D -> "Поток продувки EVAP"
        in 0x41..0x50 -> "Подогрев O2 " + listOf("Б1Д1", "Б1Д2", "Б1Д3", "Б1Д4", "Б2Д1", "Б2Д2", "Б2Д3", "Б2Д4",
            "Б3Д1", "Б3Д2", "Б3Д3", "Б3Д4", "Б4Д1", "Б4Д2", "Б4Д3", "Б4Д4")[mid - 0x41]
        0x61 -> "Подогрев катализатора Б1"
        0x62 -> "Подогрев катализатора Б2"
        0x71 -> "Вторичный воздух 1"
        0x72 -> "Вторичный воздух 2"
        0x81 -> "Топливная система Б1"
        0x82 -> "Топливная система Б2"
        0x83 -> "Топливная система Б3"
        0x84 -> "Топливная система Б4"
        0xA1 -> "Пропуски зажигания (общее)"
        in 0xA2..0xAD -> "Пропуски, цилиндр ${mid - 0xA1}"
        0xB0, 0xB1 -> "Сажевый фильтр"
        else -> "MID %02X".format(mid)
    }

    fun tidName(mid: Int, tid: Int): String {
        if (mid in 0xA1..0xAD) return when (tid) {
            0x0B -> "Пропуски: среднее за 10 циклов"
            0x0C -> "Пропуски: последний/текущий цикл"
            else -> "TID %02X".format(tid)
        }
        if (mid in 0x01..0x10) return when (tid) {
            0x01 -> "Порог богато→бедно"
            0x02 -> "Порог бедно→богато"
            0x03 -> "Низкое напряжение (для времени)"
            0x04 -> "Высокое напряжение (для времени)"
            0x05 -> "Время богато→бедно"
            0x06 -> "Время бедно→богато"
            0x07 -> "Мин. напряжение"
            0x08 -> "Макс. напряжение"
            0x09 -> "Время между переходами"
            0x0A -> "Период"
            else -> "TID %02X (GM)".format(tid)
        }
        return if (tid >= 0x80) "TID %02X (GM)".format(tid) else "TID %02X".format(tid)
    }

    /** Reply to "06 MID": [46, (MID TID UAS vH vL minH minL maxH maxL)*]. */
    fun parse(data: IntArray, ecu: Int): List<TestResult> {
        val out = mutableListOf<TestResult>()
        var i = 1
        while (i + 8 < data.size) {
            out += TestResult(
                ecu, data[i], data[i + 1], data[i + 2],
                data[i + 3] * 256 + data[i + 4], data[i + 5] * 256 + data[i + 6], data[i + 7] * 256 + data[i + 8],
            )
            i += 9
        }
        return out
    }
}
