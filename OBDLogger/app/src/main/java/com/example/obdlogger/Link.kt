package com.example.obdlogger

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import kotlin.math.sin

/** One command at a time; the mutex stops live polling and fault-code reads from interleaving. */
abstract class ObdLink {
    private val mutex = Mutex()
    suspend fun send(cmd: String, timeoutMs: Long = 4000): String =
        mutex.withLock { raw(cmd, timeoutMs) }

    /** Holds the link exclusively for several commands (e.g. changing the target module), so live polling can't interleave. */
    suspend fun <T> session(block: suspend (suspend (String, Long) -> String) -> T): T =
        mutex.withLock { block { c, t -> raw(c, t) } }

    protected abstract suspend fun raw(cmd: String, timeoutMs: Long): String
    abstract fun close()
}

class BtLink(private val socket: BluetoothSocket) : ObdLink() {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override suspend fun raw(cmd: String, timeoutMs: Long): String = withContext(Dispatchers.IO) {
        while (input.available() > 0) input.read() // flush stale bytes
        output.write((cmd + "\r").toByteArray())
        output.flush()
        val sb = StringBuilder()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (input.available() > 0) {
                val c = input.read()
                if (c < 0 || c.toChar() == '>') break
                sb.append(c.toChar())
            } else delay(10)
        }
        sb.toString().trim()
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }
}

private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

@SuppressLint("MissingPermission")
suspend fun connectBluetooth(device: BluetoothDevice): ObdLink = withContext(Dispatchers.IO) {
    var socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
    try {
        socket.connect()
    } catch (e: IOException) {
        // Many ELM327 clones only connect through this fallback channel.
        try { socket.close() } catch (_: IOException) {}
        socket = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            .invoke(device, 1) as BluetoothSocket
        socket.connect()
    }
    BtLink(socket)
}

/** Configures the adapter and returns the set of supported live-data PIDs (hex strings). */
suspend fun initElm(link: ObdLink): Set<String> {
    link.send("ATZ", 3000)
    for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) link.send(c)
    val supported = mutableSetOf<String>()
    var base = 0
    while (base <= 0x40) {
        val pid = "%02X".format(base)
        val reply = link.send("01$pid", 10000) // first call triggers protocol search
        val bytes = ObdParser.pidBytes(reply, pid)
            ?: if (base == 0) throw IOException("Car not responding. Is the ignition on?") else break
        val s = ObdParser.supportedFrom(bytes, base)
        supported += s.map { "%02X".format(it) }
        if ((base + 0x20) !in s) break
        base += 0x20
    }
    return supported
}

/** Fake car so you can test the app without hardware. */
class SimLink : ObdLink() {
    private var t = 0.0
    private var hdr = "7DF"

    override suspend fun raw(cmd: String, timeoutMs: Long): String {
        delay(30)
        t += 0.05
        if (cmd.startsWith("ATSH")) { hdr = cmd.substring(4); return "OK" }
        if (cmd == "ATDPN") return "A6"
        if (cmd.startsWith("AT")) return "OK"
        if (cmd.startsWith("02") && cmd.length == 6 && cmd.endsWith("00")) {
            val p = cmd.substring(2, 4)
            if (p == "02") return "42 02 00 01 33"
            val r = pid(p)
            return if (r.startsWith("41")) "42 $p 00" + r.removePrefix("41 $p") else r
        }
        return when (cmd) {
            "0101" -> "41 01 82 07 E5 00"
            "07" -> "47 01 01 71 00 00 00"
            "0A" -> "4A 00"
            "0902" -> "014\r0: 49 02 01 4D 41 33\r1: 45 57 31 32 33 34 35\r2: 36 37 38 39 30 41 42"
            "1902FF" -> when (hdr) {
                "7E0" -> "7E8 07 59 02 FF 01 33 00 08"
                "7E1", "7B0" -> "7" + (if (hdr == "7E1") "E9" else "B8") + " 03 59 02 FF"
                else -> "NO DATA"
            }
            "0100" -> "41 00 1E 3B 80 01"
            "0120" -> "41 20 00 02 00 00"
            "0140" -> "NO DATA"
            "03" -> "43 01 01 33 00 00 00"
            "04" -> "44"
            else -> if (cmd.startsWith("01")) pid(cmd.substring(2)) else "?"
        }
    }

    private fun pid(p: String): String {
        val s = sin(t)
        fun one(v: Double) = "41 $p %02X".format(v.toInt().coerceIn(0, 255))
        fun two(v: Int) = "41 $p %02X %02X".format((v shr 8) and 255, v and 255)
        return when (p) {
            "04" -> one((35 + 20 * s) * 2.55)
            "05" -> one(130 + 2 * s)
            "06" -> one(128 + 5 * s)
            "07" -> one(131.0)
            "0B" -> one(35 + 10 * s)
            "0C" -> two(((1800 + 1200 * s) * 4).toInt())
            "0D" -> one(60 + 40 * s)
            "0F" -> one(70.0)
            "10" -> two(((8 + 5 * s) * 100).toInt())
            "11" -> one((20 + 15 * s) * 2.55)
            "2F" -> one(65 * 2.55)
            "1F" -> two(754)
            "21" -> two(12)
            "31" -> two(1450)
            "33" -> one(101.0)
            else -> "NO DATA"
        }
    }

    override fun close() {}
}
