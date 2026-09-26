package com.obdscanner.vag

/**
 * VW diagnostic addresses on the OBD CAN. UDS modules (roughly 2012+) answer on request + 0x6A
 * (gateway 710 → 77A); the engine and gearbox also sit on the standard 7E0/7E1. Older KWP modules
 * talk VW TP2.0 only, which an ELM327 can't do — on such cars only 7E0/7E1 will be found.
 */
object VagModules {
    val candidates: List<Pair<Int, Int>> =
        (0x7E0..0x7E7).map { it to it + 8 } + (0x700..0x775).map { it to it + 0x6A }

    /** Probe order: tester present (any UDS module must answer), then the VW part number. */
    val PROBES = listOf("3E00", "22F187")

    // Only the engine/gearbox ids are standard; the rest are from VW UDS address lists, not checked on this car.
    fun name(req: Int): String = when (req) {
        0x7E0 -> "01 Двигатель"
        0x7E1 -> "02 КПП"
        0x710 -> "19 Шлюз (?)"
        0x712 -> "44 Усилитель руля (?)"
        0x713 -> "03 ABS (?)"
        0x714 -> "17 Приборка (?)"
        0x715 -> "15 Подушки (?)"
        0x70E -> "09 Бортовая электроника (?)"
        0x746 -> "08 Климат (?)"
        else -> "%03X".format(req)
    }
}
