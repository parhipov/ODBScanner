package com.odbscanner.obd

/**
 * Make by the VIN's manufacturer code (WMI, first 3 characters). GM and VAG have their own
 * module steps; the rest get the standard OBD addresses with UDS/KWP identification and DTCs.
 */
enum class Make(val title: String) {
    GM("GM"),
    VAG("Volkswagen (VAG)"),
    TOYOTA("Toyota"),
    LADA("Lada"),
    HYUNDAI("Hyundai / Kia"),
    OTHER("не определена");

    companion object {
        private val WMI = listOf(
            // Chevrolet/Cadillac/Buick/GMC (USA, Canada, Mexico), GM Korea, Holden, Opel/Vauxhall, SAIC-GM, GM Russia.
            GM to listOf("1G", "2G", "3G", "KL", "6G", "W0L", "W0V", "LSG", "XUF", "XUU"),
            // VW (Germany, Russia/Kaluga, Brazil, Mexico, USA, South Africa, China), Audi, Skoda, Seat.
            VAG to listOf("WVW", "WVG", "WV1", "WV2", "XW8", "9BW", "3VW", "1VW", "AAV", "LSV", "LFV", "WAU", "TRU", "TMB", "VSS"),
            // Japan, USA, Canada, UK, Turkey, France, South Africa, Russia (St Petersburg).
            TOYOTA to listOf("JT", "4T", "5T", "2T", "SB1", "NMT", "VNK", "AHT", "XW7"),
            // AvtoVAZ.
            LADA to listOf("XTA"),
            // Hyundai Korea, Russia (HMMR, TagAZ), USA, India, Turkey, Czechia; Kia Korea, Russia (Avtotor), Slovakia.
            HYUNDAI to listOf("KMH", "KMF", "Z94", "X7M", "5NP", "5NM", "MAL", "NLH", "TMA", "KNA", "KNC", "KND", "KNE", "XWE", "U5Y", "U6Y"),
        )

        fun fromVin(vin: String?): Make {
            val v = vin?.trim()?.uppercase()?.takeIf { it.length >= 3 } ?: return OTHER
            return WMI.firstOrNull { (_, codes) -> codes.any { v.startsWith(it) } }?.first ?: OTHER
        }
    }
}
