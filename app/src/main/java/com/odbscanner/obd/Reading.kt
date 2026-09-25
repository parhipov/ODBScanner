package com.odbscanner.obd

/**
 * One live value. [key] = "7E8:01.0C" (ECU header : source), multi-output PIDs add a suffix ("7E8:01.14.V").
 * Computed values use ECU 0 ("000:calc.lph").
 */
data class Reading(
    val key: String,
    val ecu: Int,
    val name: String,
    val value: Double?,
    val text: String?,
    val unit: String,
    val decimals: Int = 1,
    val min: Double? = value,
    val max: Double? = value,
    val time: Long = System.currentTimeMillis(),
) {
    val source get() = key.substringAfter(':')

    fun display(): String = when {
        value != null -> fmt(value, decimals)
        text != null -> text
        else -> "—"
    }

    fun merged(old: Reading?): Reading {
        if (old == null || value == null) return this
        return copy(
            min = listOfNotNull(old.min, value).minOrNull(),
            max = listOfNotNull(old.max, value).maxOrNull(),
        )
    }

    companion object {
        fun key(ecu: Int, source: String) = "%03X:%s".format(ecu, source)
        fun fmt(v: Double, decimals: Int) = if (decimals <= 0) Math.round(v).toString() else "%.${decimals}f".format(v)
    }
}

fun ecuName(header: Int): String = when (header) {
    0 -> "Расчёт"
    0x7E8 -> "ECM (двигатель)"
    0x7E9 -> "ЭБУ 7E9"
    0x7EA -> "TCM (АКПП)"
    0x7EB -> "ЭБУ 7EB"
    else -> "ЭБУ %03X".format(header)
}

/** Finds a value by source regardless of ECU, preferring the engine ECU. */
fun Map<String, Reading>.pick(source: String): Reading? =
    this[Reading.key(0x7E8, source)] ?: this[Reading.key(0, source)] ?: values.firstOrNull { it.source == source }
