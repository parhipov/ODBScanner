package com.obdscanner.obd

data class Monitor(val name: String, val available: Boolean, val complete: Boolean)

/** PID 01 / 41 — MIL, DTC count and readiness monitors. */
object Readiness {
    private val SPARK = listOf("Катализатор", "Подогрев катализатора", "EVAP (испарения)", "Вторичный воздух",
        "Кондиционер (хладагент)", "Датчики O2", "Подогрев датчиков O2", "EGR/VVT")
    private val COMPRESSION = listOf("NMHC катализатор", "NOx/SCR", "—", "Наддув", "—", "Датчик выхлопа",
        "Сажевый фильтр", "EGR/VVT")

    fun monitors(d: IntArray): List<Monitor> {
        if (d.size < 4) return emptyList()
        val b = d[1]
        val out = mutableListOf(
            Monitor("Пропуски зажигания", b and 0x01 != 0, b and 0x10 == 0),
            Monitor("Топливная система", b and 0x02 != 0, b and 0x20 == 0),
            Monitor("Компоненты", b and 0x04 != 0, b and 0x40 == 0),
        )
        val names = if (b and 0x08 == 0) SPARK else COMPRESSION
        for (i in 0..7) {
            if (names[i] == "—") continue
            out += Monitor(names[i], (d[2] shr i) and 1 == 1, (d[3] shr i) and 1 == 0)
        }
        return out
    }

    fun summary(d: IntArray, thisCycle: Boolean = false): String {
        val m = monitors(d)
        val notReady = m.filter { it.available && !it.complete }.map { it.name }
        val head = if (thisCycle) "" else "Check: ${if (d[0] and 0x80 != 0) "ГОРИТ" else "нет"}, ошибок: ${d[0] and 0x7F}; "
        return head + if (notReady.isEmpty()) "все мониторы готовы" else "не готовы: " + notReady.joinToString(", ")
    }
}
