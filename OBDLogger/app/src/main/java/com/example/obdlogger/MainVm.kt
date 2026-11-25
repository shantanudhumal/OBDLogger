package com.example.obdlogger

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainVm(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("car", Context.MODE_PRIVATE)

    // ---- car profile ----
    val profile = MutableStateFlow(loadProfile())

    private fun loadProfile(): CarProfile? {
        if (!prefs.contains("make")) return null
        return CarProfile(
            make = prefs.getString("make", "") ?: "",
            model = prefs.getString("model", "") ?: "",
            year = prefs.getInt("year", 0),
            fuel = prefs.getString("fuel", "Petrol") ?: "Petrol",
            displacementL = prefs.getFloat("disp", 0f).toDouble(),
            fuelPrice = prefs.getFloat("price", 0f).toDouble()
        )
    }

    fun saveProfile(p: CarProfile) {
        prefs.edit().putString("make", p.make).putString("model", p.model).putInt("year", p.year)
            .putString("fuel", p.fuel).putFloat("disp", p.displacementL.toFloat())
            .putFloat("price", p.fuelPrice.toFloat()).apply()
        profile.value = p
    }

    suspend fun stats(trip: Trip): TripStats = withContext(Dispatchers.Default) {
        TripStats.compute(trip, dao.allSamples(trip.id))
    }

    // ---- connection + live logging ----
    val status = MutableStateFlow("Disconnected")
    val connected = MutableStateFlow(false)
    val live = MutableStateFlow<Map<String, Double>>(emptyMap())
    val supported = MutableStateFlow<List<PidDef>>(emptyList())
    val trips = dao.trips().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val dtcLog = dao.dtcs().stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- diagnostics ----
    val vin = MutableStateFlow(prefs.getString("vin", null))
    val statusLines = MutableStateFlow<List<String>>(emptyList())
    val activeCodes = MutableStateFlow<List<String>?>(null)
    val pendingCodes = MutableStateFlow<List<String>?>(null)
    val permanentCodes = MutableStateFlow<List<String>?>(null)
    val freezeFrame = MutableStateFlow<List<String>>(emptyList())
    val modules = MutableStateFlow<List<String>>(emptyList())
    val scanMsg = MutableStateFlow("")
    val termLog = MutableStateFlow("")
    val note = MutableStateFlow("")

    private var link: ObdLink? = null
    private var job: Job? = null

    /** device == null starts demo mode with simulated data. */
    fun connect(device: BluetoothDevice?, deviceName: String) {
        job?.cancel()
        job = viewModelScope.launch {
            var trip: Trip? = null
            try {
                status.value = "Connecting…"
                val l = if (device == null) SimLink() else connectBluetooth(device)
                link = l
                status.value = "Initialising adapter…"
                val sup = initElm(l)
                val defs = Pids.all.filter { it.id in sup }
                if (defs.isEmpty()) error("Car reports no supported live data")
                supported.value = defs
                live.value = emptyMap()

                val car = profile.value
                val t = Trip(
                    startMs = System.currentTimeMillis(), source = deviceName,
                    car = car?.label ?: "", fuelType = car?.fuel ?: "Petrol",
                    displacementL = car?.displacementL ?: 0.0, fuelPrice = car?.fuelPrice ?: 0.0
                )
                trip = t.copy(id = dao.insertTrip(t))
                connected.value = true
                status.value = "Connected, logging to history"

                while (isActive) {
                    val batch = mutableListOf<Sample>()
                    for (d in defs) {
                        val reply = l.send("01" + d.id)
                        val bytes = ObdParser.pidBytes(reply, d.id) ?: continue
                        val v = runCatching { d.decode(bytes) }.getOrNull() ?: continue
                        batch += Sample(tripId = trip.id, ts = System.currentTimeMillis(), pid = d.id, value = v)
                        live.update { it + (d.id to v) }
                    }
                    dao.insertSamples(batch)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                withContext(NonCancellable) {
                    link?.close()
                    link = null
                    connected.value = false
                    trip?.let { dao.updateTrip(it.copy(endMs = System.currentTimeMillis())) }
                    if (!status.value.startsWith("Error")) status.value = "Disconnected"
                }
            }
        }
    }

    fun disconnect() { job?.cancel() }

    /** Runs a diagnostics action, reporting failures instead of crashing. */
    private fun diag(block: suspend (ObdLink) -> Unit) {
        val l = link ?: return
        viewModelScope.launch {
            note.value = ""
            try { block(l) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { note.value = "Error: ${e.message}" }
        }
    }

    fun readVin() = diag { l ->
        val v = ObdParser.vin(l.send("0902", 8000))
        if (v == null) note.value = "This car doesn't report a VIN over OBD-II."
        else { vin.value = v; prefs.edit().putString("vin", v).apply() }
    }

    fun readStatus() = diag { l ->
        val lines = mutableListOf<String>()
        ObdParser.pidBytes(l.send("0101"), "01")?.let {
            lines += "Check-engine light (MIL): " + if ((it[0] and 0x80) != 0) "ON" else "off"
            lines += "Stored fault codes: ${it[0] and 0x7F}"
        }
        for (d in Pids.info) {
            ObdParser.pidBytes(l.send("01" + d.id), d.id)?.let { b ->
                runCatching { lines += "${d.name}: %.0f ${d.unit}".format(d.decode(b)) }
            }
        }
        statusLines.value = if (lines.isEmpty()) listOf("No status reported by this car.") else lines
    }

    fun readFaults() = diag { l ->
        val stored = ObdParser.dtcs(l.send("03", 8000), "43")
        val pending = ObdParser.dtcs(l.send("07", 8000), "47")
        val permanent = ObdParser.dtcs(l.send("0A", 8000), "4A")
        activeCodes.value = stored
        pendingCodes.value = pending
        permanentCodes.value = permanent
        val now = System.currentTimeMillis()
        stored.forEach { dao.insertDtc(DtcLog(ts = now, code = it)) }
        pending.forEach { dao.insertDtc(DtcLog(ts = now, code = "$it (pending)")) }
    }

    fun clearFaults() = diag { l ->
        l.send("04", 8000)
        activeCodes.value = null; pendingCodes.value = null; permanentCodes.value = null
        statusLines.value = emptyList(); freezeFrame.value = emptyList()
        note.value = "Codes cleared. Permanent codes clear themselves after the fault is fixed and the car has been driven."
    }

    fun readFreezeFrame() = diag { l ->
        val lines = mutableListOf<String>()
        val trig = ObdParser.modeBytes(l.send("020200", 5000), "420200")
        val code = trig?.takeIf { it.size >= 2 }?.let { ObdParser.decodeDtc("%02X%02X".format(it[0], it[1])) }
        if (code == null || code == "P0000") {
            freezeFrame.value = listOf("No freeze frame stored.")
            return@diag
        }
        lines += "Captured when $code was set:"
        for (d in supported.value) {
            val b = ObdParser.modeBytes(l.send("02${d.id}00", 3000), "42${d.id}00") ?: continue
            runCatching { lines += "${d.name}: %.1f ${d.unit}".format(d.decode(b)) }
        }
        freezeFrame.value = lines
    }

    /** Asks every module on the CAN bus for its stored fault codes (UDS service 19), e.g. ABS, airbag, gearbox. */
    fun scanModules(full: Boolean) = diag { l ->
        modules.value = emptyList()
        val out = mutableListOf<String>()
        l.session { s ->
            val proto = s("ATDPN", 2000).trim().uppercase()
            if (!(proto.endsWith("6") || proto.endsWith("8"))) {
                out += "Module scan needs 11-bit CAN. This car uses protocol \"$proto\", which isn't supported here."
                return@session
            }
            s("ATH1", 1000); s("ATST20", 1000)
            try {
                val ids = if (full) 0x700..0x7E7 else 0x7E0..0x7E7
                for (id in ids) {
                    if (id == 0x7DF) continue
                    scanMsg.value = "Scanning %03X…".format(id)
                    s("ATSH%03X".format(id), 1000)
                    val (rx, payload) = ObdParser.canReply(s("1902FF", 1500)) ?: continue
                    val label = when (id) { 0x7E0 -> " Engine"; 0x7E1 -> " Transmission"; else -> " Module" }
                    val codes = ObdParser.udsDtcs(payload)
                    out += "%03X%s (replies as %s): ".format(id, label, rx) + when {
                        codes == null -> "responds, but doesn't offer fault codes this way"
                        codes.isEmpty() -> "no fault codes"
                        else -> codes.joinToString(", ")
                    }
                    modules.value = out.toList()
                }
            } finally {
                withContext(NonCancellable) {
                    s("ATH0", 1000); s("ATST32", 1000); s("ATSH7DF", 1000)
                }
            }
        }
        modules.value = if (out.isEmpty()) listOf("No modules answered.") else out.toList()
        scanMsg.value = "Scan finished"
    }

    /** Sends one raw command, optionally to a specific module address. For advanced use. */
    fun sendRaw(header: String, cmd: String) = diag { l ->
        val h = header.trim().uppercase().filter { it.isLetterOrDigit() }
        val c = cmd.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return@diag
        var reply = ""
        l.session { s ->
            try {
                if (h.isNotEmpty()) s("ATSH$h", 1000)
                reply = s(c, 8000)
            } finally {
                withContext(NonCancellable) {
                    if (h.isNotEmpty()) s("ATSH7DF", 1000)
                    for (x in listOf("ATE0", "ATL0", "ATS0", "ATH0")) s(x, 1000)
                }
            }
        }
        termLog.update { it + (if (h.isNotEmpty()) "[$h] " else "") + "> $c\n" + reply.ifBlank { "(no reply)" } + "\n\n" }
    }

    // ---- history ----
    suspend fun pidsIn(tripId: Long) = dao.pidsIn(tripId)
    suspend fun series(tripId: Long, pid: String) = dao.series(tripId, pid).map { it.ts to it.value }

    fun deleteTrip(id: Long) {
        viewModelScope.launch { dao.deleteSamples(id); dao.deleteTrip(id) }
    }

    fun exportTo(ctx: Context, uri: Uri, tripId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = dao.allSamples(tripId)
            ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { w ->
                w.write("timestamp_ms,pid,name,value,unit\n")
                rows.forEach {
                    val d = Pids.byId[it.pid]
                    w.write("${it.ts},${it.pid},${d?.name},${it.value},${d?.unit}\n")
                }
            }
        }
    }
}
