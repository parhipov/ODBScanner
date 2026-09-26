package com.obdscanner.obd

/** Mode 09 vehicle information. */
object Mode09 {
    fun name(infoType: Int) = when (infoType) {
        0x02 -> "VIN"
        0x04 -> "Калибровка (CALID)"
        0x06 -> "Контрольная сумма калибровки (CVN)"
        0x08 -> "Счётчики мониторов (бензин)"
        0x0A -> "Имя ЭБУ"
        0x0B -> "Счётчики мониторов (дизель)"
        0x0D -> "ESN"
        else -> "Info %02X".format(infoType)
    }

    private val IPT_SPARK = listOf("Условий OBD", "Запусков двигателя", "Катализатор Б1 выполн.", "Катализатор Б1 условий",
        "Катализатор Б2 выполн.", "Катализатор Б2 условий", "O2 Б1 выполн.", "O2 Б1 условий", "O2 Б2 выполн.",
        "O2 Б2 условий", "EGR/VVT выполн.", "EGR/VVT условий", "Втор. воздух выполн.", "Втор. воздух условий",
        "EVAP выполн.", "EVAP условий", "2-й O2 Б1 выполн.", "2-й O2 Б1 условий", "2-й O2 Б2 выполн.", "2-й O2 Б2 условий")

    /** [49, type, count, payload...] → human text. */
    fun decode(data: IntArray): String {
        if (data.size < 3) return Pids.hex(data)
        val type = data[1]
        val payload = data.copyOfRange(2, data.size)
        return when (type) {
            0x02, 0x04, 0x0A, 0x0D -> {
                // First byte is the item count on CAN; strings are NUL-padded.
                val chunk = if (type == 0x04) 16 else if (type == 0x0A) 20 else payload.size - 1
                payload.drop(1).chunked(chunk.coerceAtLeast(1))
                    .map { part -> part.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" | ")
            }
            0x06 -> payload.drop(1).chunked(4).joinToString(" | ") { p -> p.joinToString("") { "%02X".format(it) } }
            0x08, 0x0B -> {
                val vals = payload.drop(1).chunked(2).filter { it.size == 2 }.map { it[0] * 256 + it[1] }
                vals.mapIndexed { i, v -> "${if (type == 0x08) IPT_SPARK.getOrElse(i) { "#$i" } else "#$i"}: $v" }
                    .joinToString("\n")
            }
            else -> Pids.hex(payload)
        }
    }
}
