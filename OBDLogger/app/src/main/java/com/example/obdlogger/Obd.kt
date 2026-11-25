package com.example.obdlogger

/** A live-data parameter (Mode 01 PID) and how to turn raw bytes into a value. */
data class PidDef(
    val id: String,
    val name: String,
    val unit: String,
    val decode: (IntArray) -> Double
)

object Pids {
    // Standard SAE J1979 PIDs. Add more here and they appear automatically if the car supports them.
    val all = listOf(
        PidDef("04", "Engine load", "%") { it[0] * 100.0 / 255 },
        PidDef("05", "Coolant temp", "°C") { it[0] - 40.0 },
        PidDef("06", "Short fuel trim B1", "%") { (it[0] - 128) * 100.0 / 128 },
        PidDef("07", "Long fuel trim B1", "%") { (it[0] - 128) * 100.0 / 128 },
        PidDef("0B", "Intake manifold pressure", "kPa") { it[0].toDouble() },
        PidDef("0C", "Engine RPM", "rpm") { (256 * it[0] + it[1]) / 4.0 },
        PidDef("0D", "Speed", "km/h") { it[0].toDouble() },
        PidDef("0F", "Intake air temp", "°C") { it[0] - 40.0 },
        PidDef("10", "MAF rate", "g/s") { (256 * it[0] + it[1]) / 100.0 },
        PidDef("11", "Throttle position", "%") { it[0] * 100.0 / 255 },
        PidDef("2F", "Fuel level", "%") { it[0] * 100.0 / 255 },
        PidDef("42", "Module voltage", "V") { (256 * it[0] + it[1]) / 1000.0 },
        PidDef("46", "Ambient temp", "°C") { it[0] - 40.0 },
        PidDef("5C", "Oil temp", "°C") { it[0] - 40.0 },
        PidDef("5E", "Engine fuel rate", "L/h") { (256 * it[0] + it[1]) / 20.0 },
    )
    val byId = all.associateBy { it.id }

    // One-off vehicle info, read on demand rather than polled continuously.
    val info = listOf(
        PidDef("1F", "Engine run time", "s") { (256 * it[0] + it[1]).toDouble() },
        PidDef("21", "Distance driven with MIL on", "km") { (256 * it[0] + it[1]).toDouble() },
        PidDef("31", "Distance since codes cleared", "km") { (256 * it[0] + it[1]).toDouble() },
        PidDef("33", "Barometric pressure", "kPa") { it[0].toDouble() },
    )
}

object ObdParser {
    private val linePrefix = Regex("^[0-9A-F]{1,2}:")

    /** Keeps only lines that are pure hex (drops OK, SEARCHING..., NO DATA, etc). */
    fun hexLines(raw: String): List<String> =
        raw.split('\r', '\n')
            .map { it.trim().uppercase().replace(linePrefix, "").replace(" ", "") }
            .filter { l -> l.isNotEmpty() && l.all { it in '0'..'9' || it in 'A'..'F' } }

    /** Data bytes following the "41 <pid>" header, or null if the reply isn't valid. */
    fun pidBytes(raw: String, pid: String): IntArray? = modeBytes(raw, "41$pid")

    /** Data bytes after any reply header such as "4105" (mode 01) or "420500" (mode 02 freeze frame). */
    fun modeBytes(raw: String, head: String): IntArray? {
        for (l in hexLines(raw)) {
            val i = l.indexOf(head)
            if (i >= 0) {
                val bytes = l.substring(i + head.length).chunked(2)
                    .filter { it.length == 2 }.map { it.toInt(16) }
                return bytes.toIntArray()
            }
        }
        return null
    }

    /** Decodes a "supported PIDs" bitmask reply into PID numbers. */
    fun supportedFrom(bytes: IntArray, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (i in 0 until 32) {
            val byteIdx = i / 8
            val bit = 7 - (i % 8)
            if (bytes.size > byteIdx && ((bytes[byteIdx] shr bit) and 1) == 1) out += base + i + 1
        }
        return out
    }

    /** Parses a Mode 03 reply into codes like P0133. */
    fun dtcs(raw: String, header: String = "43"): List<String> { // 43 stored, 47 pending, 4A permanent
        val lines = hexLines(raw)
        val first = lines.indexOfFirst { it.startsWith(header) }
        if (first < 0) return emptyList()
        var body = lines[first].substring(2) + lines.drop(first + 1).joinToString("")
        // CAN replies carry a leading count byte, making the byte count odd.
        if ((body.length / 2) % 2 == 1) body = body.substring(2)
        return body.chunked(4)
            .filter { it.length == 4 && it != "0000" }
            .map { decodeDtc(it) }
    }

    fun decodeDtc(h: String): String {
        val n = h[0].digitToInt(16)
        val letter = "PCBU"[n shr 2]
        val d1 = n and 3
        return "$letter$d1${h.substring(1)}"
    }

    /** Parses the Mode 09 PID 02 reply into a 17-character VIN, or null. */
    fun vin(raw: String): String? {
        val lines = hexLines(raw).filter { it.length > 3 } // drop the "014" length line
        val bytes = if (lines.isNotEmpty() && lines.all { it.startsWith("4902") }) {
            lines.joinToString("") { it.substring(6) }
        } else {
            val j = lines.joinToString("")
            val i = j.indexOf("4902")
            if (i < 0) return null
            j.substring(i + 6)
        }
        val s = bytes.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .filter { it in 48..57 || it in 65..90 }.map { it.toChar() }.joinToString("")
        return if (s.length >= 17) s.takeLast(17) else null
    }

    /** Parses a header-on (ATH1) CAN reply into (responding id, reassembled payload). */
    fun canReply(raw: String): Pair<String, IntArray>? {
        val frames = hexLines(raw).filter { it.length >= 6 && it[0] == '7' }
        if (frames.isEmpty()) return null
        val rx = frames.first().substring(0, 3)
        val payload = mutableListOf<Int>()
        for (f in frames.filter { it.startsWith(rx) }) {
            val b = f.substring(3).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
            if (b.isEmpty()) continue
            when (b[0] shr 4) {
                0 -> payload += b.drop(1).take(b[0] and 15) // single frame
                1 -> payload += b.drop(2)                    // first frame
                2 -> payload += b.drop(1)                    // consecutive frame
            }
        }
        return rx to payload.toIntArray()
    }

    /** UDS ReadDTCInformation (19 02) reply -> codes like "C0035-00", or null if the module doesn't support it. */
    fun udsDtcs(p: IntArray): List<String>? {
        if (p.size < 3 || p[0] != 0x59 || p[1] != 0x02) return null
        val out = mutableListOf<String>()
        var i = 3
        while (i + 3 < p.size) {
            out += decodeDtc("%02X%02X".format(p[i], p[i + 1])) + "-%02X".format(p[i + 2])
            i += 4
        }
        return out
    }
}
