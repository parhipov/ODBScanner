package com.obdscanner.gm

import com.obdscanner.obd.PollRate

/**
 * GM enhanced parameters (Mode $22) with known decoding. Each one is probed after connecting;
 * only those that answer get polled.
 *
 * Sources (collected 2026-09): Holden VE/VF extended PIDs (same HFV6 + 6L gearbox era),
 * GM Class 2 PID lists, OBDb Chevrolet/Cadillac, live tests on GM C1XX. Confidence:
 *   OK  — several sources agree / seen on a live car
 *   ?   — single source or conflicting formulas: compare against a known value before trusting
 * Read rate (`every`): FAST — follows the pedal and shifts, MEDIUM (default), SLOW — counters and wear.
 */
object GmKnown {
    private const val ECM = 0x7E0
    private const val TCM = 0x7E2
    private const val OK = "OK"
    private const val MAYBE = "?"

    private fun ab(d: IntArray) = d[0] * 256 + d[1]
    private fun s16(d: IntArray) = ab(d).let { if (it >= 32768) it - 65536 else it }

    private const val FAST = PollRate.FAST
    private const val SLOW = PollRate.SLOW

    private fun did(req: Int, did: Int, name: String, unit: String, dec: Int, conf: String, group: String, every: Long = PollRate.MEDIUM, f: (IntArray) -> Double?) =
        GmDid(req, "22", did, name, unit, dec, conf, group, every, f)

    val all: List<GmDid> = buildList {
        // ---- TCM (6L50)
        add(did(TCM, 0x1940, "Температура масла АКПП", "°C", 0, OK, "main") { it[0] - 40.0 })
        add(did(TCM, 0x295A, "Темп. масла АКПП (фильтр.)", "°C", 0, MAYBE, "other") { it[0] - 40.0 })
        add(did(TCM, 0x1991, "Проскальзывание гидротрансформатора", "об/мин", 0, OK, "main", every = FAST) { s16(it) / 8.0 })
        // ×0.125 checked on the car: 580 at idle in P with engine 615 and TCC slip 30.
        add(did(TCM, 0x1941, "Обороты входного вала АКПП", "об/мин", 0, OK, "main", every = FAST) { ab(it) * 0.125 })
        add(did(TCM, 0x1942, "Обороты выходного вала АКПП", "об/мин", 0, MAYBE, "main", every = FAST) { ab(it) * 0.125 })
        add(did(TCM, 0x199A, "Передача АКПП", "", 0, MAYBE, "main", every = FAST) { it[0].toDouble() })
        add(did(TCM, 0x199E, "Ток соленоида давления (факт)", "А", 2, MAYBE, "other", every = FAST) { it[0] * 0.0195 })
        add(did(TCM, 0x199F, "Ток соленоида давления (задан.)", "А", 2, MAYBE, "other", every = FAST) { it[0] * 0.0195 })

        // ---- ECM (HFV6 2.8 LP1)
        add(did(ECM, 0x1154, "Температура масла двигателя", "°C", 0, OK, "main") { it[0] - 40.0 })
        add(did(ECM, 0x1470, "Давление масла", "кПа", 0, MAYBE, "main") { it[0] * 4.0 })
        add(did(ECM, 0x119F, "Остаток ресурса масла", "%", 0, MAYBE, "main", every = SLOW) { it[0] * 100.0 / 255 })
        add(did(ECM, 0x11A6, "Откат зажигания по детонации", "°", 1, MAYBE, "fuel", every = FAST) { it[0] * 22.5 / 256 })
        add(did(ECM, 0x125D, "Откат по детонации 2", "°", 1, MAYBE, "fuel", every = FAST) { it[0] * 22.5 / 256 })
        add(did(ECM, 0x125E, "Счётчик детонации", "", 0, MAYBE, "fuel") { it[0].toDouble() })
        add(did(ECM, 0x12D9, "Суммарный откат по детонации", "°", 1, MAYBE, "fuel", every = FAST) { it[0] * 0.45 })
        add(did(ECM, 0x119E, "Заданное соотношение воздух/топливо", "AFR", 1, MAYBE, "fuel", every = FAST) { it[0] / 10.0 })
        add(did(ECM, 0x1141, "Напряжение зажигания (IGN1)", "В", 1, OK, "other", every = SLOW) { it[0] / 10.0 })
        add(did(ECM, 0x1161, "Температура за бортом (ECM)", "°C", 0, MAYBE, "other", every = SLOW) { it[0] - 40.0 })
        add(did(ECM, 0x11A1, "Время работы двигателя (ECM)", "с", 0, OK, "other", every = SLOW) { ab(it).toDouble() })
        // Forum formula (A·2.02 psi) gave 394 psi on the car — unknown scaling, keep the raw byte.
        add(did(ECM, 0x1564, "Датчик давления кондиционера (сырое)", "", 0, MAYBE, "other") { it[0].toDouble() })
        // Torque Pro lists (GM trucks): "AC Hi Side Pressure" = A·1.83 − 15 psi. Not yet seen on the car.
        add(did(ECM, 0x1144, "Давление кондиционера (выс. сторона)", "кПа", 0, MAYBE, "other") { (it[0] * 1.83 - 15) * 6.895 })
        // Current misfires — note GM's odd order: 1206 = cyl 1, 1205 = cyl 2.
        for ((d, cyl) in listOf(0x1206 to 1, 0x1205 to 2, 0x1207 to 3, 0x1208 to 4, 0x11EA to 5, 0x11EB to 6)) {
            add(did(ECM, d, "Пропуски сейчас, цил. $cyl", "", 0, OK, "fuel") { it[0].toDouble() })
        }
        for ((d, cyl) in listOf(0x1201 to 1, 0x1202 to 2, 0x1203 to 3, 0x1204 to 4, 0x11F8 to 5, 0x11F9 to 6)) {
            add(did(ECM, d, "Пропуски история, цил. $cyl", "", 0, MAYBE, "fuel", every = SLOW) { ab(it).toDouble() })
        }
        for (cyl in 1..6) {
            add(did(ECM, 0x1192 + cyl, "Длительность впрыска, цил. $cyl", "мс", 2, MAYBE, "fuel", every = SLOW) { ab(it) / 65.535 })
        }
        for (cyl in 1..6) {
            add(did(ECM, 0x162E + cyl, "Баланс цилиндра $cyl", "", 2, MAYBE, "fuel", every = SLOW) { (ab(it) - 32768) * 0.015625 })
        }

        // ---- Candidates from OBDb Chevrolet-Traverse (3.6 LLT, same HFV6 family). Not yet seen on
        // the car: logged to compare against known values; several are newer-ECM only.
        add(did(TCM, 0x1B30, "Передача АКПП (OBDb)", "", 0, MAYBE, "other") { it[0] - 3.0 })
        add(did(ECM, 0x13AF, "Вентилятор охлаждения (команда)", "%", 0, MAYBE, "other") { it[0] * 100.0 / 255 })
        add(did(ECM, 0x3812, "Вентилятор охлаждения (команда 2)", "%", 0, MAYBE, "other") { it[0] * 100.0 / 255 })
        add(did(ECM, 0x1200, "Пропуски всего", "", 0, MAYBE, "other") { it[0].toDouble() })
        add(did(ECM, 0x12C3, "Впрыск, банк 1", "мс", 2, MAYBE, "other") { ab(it) * 0.015 })
        add(did(ECM, 0x12C4, "Впрыск, банк 2", "мс", 2, MAYBE, "other") { ab(it) * 0.015 })
        add(did(ECM, 0x153E, "Температура масла двигателя (2)", "°C", 0, MAYBE, "other") { it[0] - 40.0 })
        add(did(ECM, 0x36A7, "Ресурс воздушного фильтра", "%", 0, MAYBE, "other", every = SLOW) { it[0].toDouble() })
        for ((d, wheel) in listOf(0x248E to "ПЛ", 0x248F to "ПП", 0x2490 to "ЗП", 0x2491 to "ЗЛ")) {
            add(did(ECM, d, "Давление в шине $wheel", "кПа", 0, MAYBE, "other", every = SLOW) { it[0] * 6.895 })
        }
    }

    fun responseFor(req: Int) = if (req in 0x7E0..0x7E7) req + 8 else req + 0x400
}
