package com.obdscanner.obd

/**
 * How often a value is worth reading. Logs are analysed afterwards, so the rate follows how fast a
 * value changes and how much the analysis needs it — not which screen is open. The open screen only
 * lifts its slow values to [MEDIUM], so no tile sits still for half a minute.
 */
object PollRate {
    const val FAST = 1000L
    const val MEDIUM = 5000L
    const val SLOW = 30000L

    fun onScreen(period: Long) = minOf(period, MEDIUM)

    /** Mode 01 that follows the pedal, the mixture and the O2 sensors. */
    private val FAST_01 = setOf(0x04, 0x06, 0x08, 0x0B, 0x0C, 0x0D, 0x0E, 0x10, 0x11, 0x43, 0x44, 0x45, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x5E) +
        (0x14..0x1B) + (0x24..0x2B) + (0x34..0x3B)

    /** Counters, levels and the weather. */
    private val SLOW_01 = setOf(0x13, 0x1C, 0x1D, 0x1E, 0x1F, 0x21, 0x2F, 0x30, 0x31, 0x33, 0x41, 0x46, 0x4D, 0x4E, 0x4F, 0x50, 0x51)

    fun of01(pid: Int) = when (pid) {
        in FAST_01 -> FAST
        in SLOW_01 -> SLOW
        else -> MEDIUM
    }

    /**
     * What to read now: items whose period ran out, most overdue first, at most [budget]. Over budget
     * every period stretches by the same factor. "Due" at 80% of the period, so a FAST value doesn't
     * skip every other ~0.9 s cycle.
     */
    fun <T> due(items: List<T>, last: MutableMap<String, Long>, now: Long, budget: Int, key: (T) -> String, period: (T) -> Long): List<T> =
        items.map { it to (now - (last[key(it)] ?: 0L)).toDouble() / period(it) }
            .filter { it.second >= 0.8 }
            .sortedByDescending { it.second }
            .take(budget)
            .map { it.first }
            .onEach { last[key(it)] = now }
}
