package com.obdscanner.obd

/**
 * Identification of a non-GM ECU: UDS \$22 F1xx (ISO 14229, VW names in brackets) and KWP2000
 * \$1A (ISO 14230-3 identification options). Read only. Whatever service the ECU rejects (NRC 11)
 * is skipped, so a UDS ECU costs one \$1A request and vice versa.
 */
object EcuIdent {
    class Item(val service: String, val did: Int, val name: String)

    val ALL = listOf(
        Item("22", 0xF187, "Номер детали (VW)"),
        Item("22", 0xF189, "Версия ПО"),
        Item("22", 0xF191, "Номер железа"),
        Item("22", 0xF1A3, "Версия железа"),
        Item("22", 0xF197, "Название системы"),
        Item("22", 0xF18C, "Серийный номер"),
        Item("22", 0xF190, "VIN"),
        Item("22", 0xF199, "Дата программирования"),
        Item("22", 0xF18A, "Поставщик"),
        Item("22", 0xF19E, "ODX-файл (ASAM)"),
        Item("22", 0xF1A2, "Версия ODX-файла"),
        Item("22", 0xF1AA, "Имя блока в диагностике (VW)"),
        Item("22", 0x0600, "Кодирование (VW)"),
        Item("1A", 0x87, "Номер детали производителя"),
        Item("1A", 0x88, "Номер ПО"),
        Item("1A", 0x89, "Версия ПО"),
        Item("1A", 0x8A, "Поставщик"),
        Item("1A", 0x8C, "Серийный номер"),
        Item("1A", 0x90, "VIN"),
        Item("1A", 0x91, "Номер железа"),
        Item("1A", 0x92, "Номер железа поставщика"),
        Item("1A", 0x94, "Номер ПО поставщика"),
        Item("1A", 0x97, "Название системы"),
        Item("1A", 0x99, "Дата программирования"),
        Item("1A", 0x9B, "Идентификация VAG (номер, ПО, кодирование)"),
        Item("1A", 0x86, "Идентификация производителя"),
    )
}

/** Any make without its own address list: only the standard OBD ids (7E0–7E7 → +8). */
object ObdModules {
    val candidates: List<Pair<Int, Int>> = (0x7E0..0x7E7).map { it to it + 8 }

    /** Tester present (UDS and KWP on CAN), then the UDS VIN, then the KWP VIN. */
    val PROBES = listOf("3E00", "22F190", "1A90")

    fun name(req: Int): String = when (req) {
        0x7E0 -> "Двигатель (7E0)"
        else -> "ЭБУ %03X".format(req)
    }
}
