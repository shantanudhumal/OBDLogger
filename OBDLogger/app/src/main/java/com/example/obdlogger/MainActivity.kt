package com.example.obdlogger

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface { App() }
            }
        }
    }
}

private val fmt = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
private fun fmtTime(ms: Long) = fmt.format(Date(ms))

@SuppressLint("MissingPermission")
private fun bonded(ctx: Context): List<BluetoothDevice> =
    ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() ?: emptyList()

@SuppressLint("MissingPermission")
private fun devName(d: BluetoothDevice): String = d.name ?: d.address

@Composable
fun App(vm: MainVm = viewModel()) {
    val profile by vm.profile.collectAsState()
    if (profile == null) CarScreen(vm, firstRun = true) else MainTabs(vm)
}

@Composable
fun CarScreen(vm: MainVm, firstRun: Boolean) {
    val saved by vm.profile.collectAsState()
    var make by remember { mutableStateOf(saved?.make ?: "") }
    var model by remember { mutableStateOf(saved?.model ?: "") }
    var year by remember { mutableStateOf(saved?.year?.toString() ?: "") }
    var disp by remember { mutableStateOf(saved?.displacementL?.toString() ?: "") }
    var fuel by remember { mutableStateOf(saved?.fuel ?: "Petrol") }
    var price by remember { mutableStateOf(saved?.fuelPrice?.takeIf { it > 0 }?.toString() ?: "") }
    val valid = make.isNotBlank() && model.isNotBlank() && year.toIntOrNull() != null &&
        (disp.toDoubleOrNull() ?: 0.0) > 0

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(if (firstRun) "Tell us about your car" else "Your car", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Used to estimate fuel consumption, running cost and CO₂ from the data your car reports.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(make, { make = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Make (e.g. Hyundai)") })
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Model (e.g. i20)") })
        OutlinedTextField(year, { year = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Year") })
        OutlinedTextField(disp, { disp = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Engine size in litres (e.g. 1.2)") })
        Text("Fuel type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Petrol", "Diesel").forEach {
                FilterChip(selected = fuel == it, onClick = { fuel = it }, label = { Text(it) })
            }
        }
        OutlinedTextField(price, { price = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Fuel price per litre (optional)") })
        Button(enabled = valid, onClick = {
            vm.saveProfile(
                CarProfile(make.trim(), model.trim(), year.toInt(), fuel, disp.toDouble(), price.toDoubleOrNull() ?: 0.0)
            )
        }) { Text(if (firstRun) "Continue" else "Save") }
    }
}

@Composable
fun MainTabs(vm: MainVm) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Connect", "Live", "History", "Diag", "Car")
    Scaffold(bottomBar = {
        NavigationBar {
            tabs.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ConnectScreen(vm)
                1 -> LiveScreen(vm)
                2 -> HistoryScreen(vm)
                3 -> DiagScreen(vm)
                else -> CarScreen(vm, firstRun = false)
            }
        }
    }
}

@Composable
fun ConnectScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val status by vm.status.collectAsState()
    val connected by vm.connected.collectAsState()
    var devices by remember { mutableStateOf(listOf<BluetoothDevice>()) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) devices = bonded(ctx)
    }
    fun load() {
        val needs = Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        if (needs) permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) else devices = bonded(ctx)
    }
    LaunchedEffect(Unit) { load() }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Status: $status", style = MaterialTheme.typography.titleMedium)
        Text(
            "Pair your ELM327 in Android Bluetooth settings first (PIN is usually 1234 or 0000), " +
                "turn the car ignition on, then pick it below.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (connected) Button(onClick = { vm.disconnect() }) { Text("Disconnect") }
            OutlinedButton(onClick = { load() }) { Text("Refresh devices") }
            OutlinedButton(onClick = { vm.connect(null, "Demo") }, enabled = !connected) { Text("Demo mode") }
        }
        Text("Paired devices", style = MaterialTheme.typography.titleSmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices) { d ->
                Card(Modifier.fillMaxWidth().clickable(enabled = !connected) { vm.connect(d, devName(d)) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(devName(d), style = MaterialTheme.typography.titleMedium)
                        Text(d.address, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveScreen(vm: MainVm) {
    val defs by vm.supported.collectAsState()
    val live by vm.live.collectAsState()
    val connected by vm.connected.collectAsState()
    val profile by vm.profile.collectAsState()
    if (!connected && defs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("Not connected. Go to the Connect tab.") }
        return
    }
    val lph = profile?.let { Fuel.lPerHour(it.fuel, it.displacementL, live) }
    val spd = live["0D"]
    LazyVerticalGrid(
        GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Fuel consumption (estimate)", style = MaterialTheme.typography.labelMedium)
                    when {
                        lph == null -> Text("Not available: car reports neither MAF nor MAP data")
                        spd != null && spd > 5 -> Text(
                            "%.1f L/100 km  ·  %.1f L/h".format(lph / spd * 100, lph), fontSize = 24.sp
                        )
                        else -> Text("%.1f L/h".format(lph), fontSize = 24.sp)
                    }
                }
            }
        }
        items(defs) { d ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(d.name, style = MaterialTheme.typography.labelMedium)
                    Text(live[d.id]?.let { "%.1f".format(it) } ?: "—", fontSize = 30.sp)
                    Text(d.unit, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(vm: MainVm) {
    val ctx = LocalContext.current
    val trips by vm.trips.collectAsState()
    var selected by remember { mutableStateOf<Trip?>(null) }
    var exportId by remember { mutableLongStateOf(0L) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportTo(ctx, uri, exportId)
    }

    val sel = selected
    if (sel != null) {
        TripDetail(vm, sel, onBack = { selected = null })
        return
    }
    if (trips.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text("No history yet. Connect to log your first trip.") }
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(trips) { t ->
            Card(Modifier.fillMaxWidth().clickable { selected = t }) {
                Column(Modifier.padding(12.dp)) {
                    Text(fmtTime(t.startMs), style = MaterialTheme.typography.titleMedium)
                    val mins = t.endMs?.let { (it - t.startMs) / 60000 }
                    Text("${t.source} · " + (mins?.let { "$it min" } ?: "in progress"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            exportId = t.id
                            exporter.launch("obd_trip_${t.id}.csv")
                        }) { Text("Export CSV") }
                        TextButton(onClick = { vm.deleteTrip(t.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
fun TripDetail(vm: MainVm, trip: Trip, onBack: () -> Unit) {
    var pids by remember { mutableStateOf(listOf<String>()) }
    var pid by remember { mutableStateOf<String?>(null) }
    var pts by remember { mutableStateOf(listOf<Pair<Long, Double>>()) }

    LaunchedEffect(trip.id) {
        pids = vm.pidsIn(trip.id)
        pid = pids.firstOrNull()
    }
    LaunchedEffect(pid) { pid?.let { pts = vm.series(trip.id, it) } }
    var stats by remember { mutableStateOf<TripStats?>(null) }
    LaunchedEffect(trip.id) { stats = vm.stats(trip) }

    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Back") }
        Text(fmtTime(trip.startMs), style = MaterialTheme.typography.titleMedium)
        if (trip.car.isNotBlank()) Text(trip.car, style = MaterialTheme.typography.bodySmall)
        stats?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Distance %.1f km in %.0f min".format(s.distanceKm, s.durationMin))
                    Text("Avg moving speed " + (s.avgMovingKmh?.let { "%.0f km/h".format(it) } ?: "n/a") +
                        " · max %.0f km/h".format(s.maxKmh))
                    Text("Idling %.0f min".format(s.idleMin))
                    if (s.fuelL == null) Text("Fuel: not enough data to estimate")
                    else {
                        Text("Fuel used ≈ %.2f L".format(s.fuelL))
                        s.lPer100?.let { Text("Consumption ≈ %.1f L/100 km (%.1f km/L)".format(it, 100 / it)) }
                        s.cost?.let { Text("Fuel cost ≈ %.2f".format(it)) }
                        s.co2Kg?.let { Text("CO₂ ≈ %.1f kg".format(it)) }
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pids) { p ->
                FilterChip(selected = p == pid, onClick = { pid = p },
                    label = { Text(Pids.byId[p]?.name ?: p) })
            }
        }
        val def = pid?.let { Pids.byId[it] }
        if (pts.isNotEmpty() && def != null) {
            val vs = pts.map { it.second }
            Text("min %.1f · avg %.1f · max %.1f %s".format(vs.min(), vs.average(), vs.max(), def.unit))
        }
        LineChart(pts, Modifier.fillMaxWidth().height(260.dp))
    }
}

@Composable
fun LineChart(pts: List<Pair<Long, Double>>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 2f)
        if (pts.size < 2) return@Canvas
        val t0 = pts.first().first
        val spanT = (pts.last().first - t0).coerceAtLeast(1)
        val minV = pts.minOf { it.second }
        val spanV = (pts.maxOf { it.second } - minV).coerceAtLeast(1e-6)
        val path = Path()
        pts.forEachIndexed { i, p ->
            val x = (p.first - t0).toFloat() / spanT * size.width
            val y = size.height - ((p.second - minV) / spanV).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
}

private fun codes(l: List<String>?) = when {
    l == null -> "not read yet"
    l.isEmpty() -> "none"
    else -> l.joinToString(", ")
}

@Composable
fun DiagScreen(vm: MainVm) {
    val connected by vm.connected.collectAsState()
    val vin by vm.vin.collectAsState()
    val statusLines by vm.statusLines.collectAsState()
    val stored by vm.activeCodes.collectAsState()
    val pending by vm.pendingCodes.collectAsState()
    val permanent by vm.permanentCodes.collectAsState()
    val freeze by vm.freezeFrame.collectAsState()
    val modules by vm.modules.collectAsState()
    val scanMsg by vm.scanMsg.collectAsState()
    val log by vm.dtcLog.collectAsState()
    val term by vm.termLog.collectAsState()
    val note by vm.note.collectAsState()
    var header by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!connected) Text("Connect to the car (Connect tab) to use these tools. Saved code history is at the bottom.")
        if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.primary)

        Section("Vehicle")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.readVin() }, enabled = connected) { Text("Read VIN") }
            Button(onClick = { vm.readStatus() }, enabled = connected) { Text("Read status") }
        }
        Text("VIN: ${vin ?: "not read yet"}")
        statusLines.forEach { Text(it) }

        Section("Fault codes (engine/emissions)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.readFaults() }, enabled = connected) { Text("Read codes") }
            OutlinedButton(onClick = { vm.clearFaults() }, enabled = connected) { Text("Clear codes") }
        }
        Text("Stored: ${codes(stored)}")
        Text("Pending: ${codes(pending)}")
        Text("Permanent: ${codes(permanent)}")

        Section("Freeze frame")
        Text("Snapshot of engine data taken when the first fault was recorded.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { vm.readFreezeFrame() }, enabled = connected) { Text("Read freeze frame") }
        freeze.forEach { Text(it) }

        Section("All modules (ABS, airbag, gearbox…)")
        Text(
            "Asks every control module on the car for its fault codes. Works on most 2008+ cars with CAN. " +
                "Quick scan checks engine and gearbox; full scan checks every address and takes about a minute. " +
                "Module names other than engine/gearbox vary by make, so they show as addresses.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.scanModules(false) }, enabled = connected) { Text("Quick scan") }
            OutlinedButton(onClick = { vm.scanModules(true) }, enabled = connected) { Text("Full scan") }
        }
        if (scanMsg.isNotEmpty()) Text(scanMsg, style = MaterialTheme.typography.bodySmall)
        modules.forEach { Text(it) }

        Section("Advanced terminal")
        Text(
            "Send raw commands, for example 22F190 to a module for make-specific data. Read-only commands are safe. " +
                "Commands that write, reset or actuate can change how your car behaves, so only use them if you know what they do.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(header, { header = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Module address (optional, e.g. 7E0)") })
        OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Command (e.g. 0100 or ATRV)") })
        Button(onClick = { vm.sendRaw(header, cmd) }, enabled = connected) { Text("Send") }
        if (term.isNotEmpty()) Text(term, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

        Section("Code history")
        if (log.isEmpty()) Text("Nothing saved yet.")
        log.take(30).forEach { Text("${fmtTime(it.ts)}   ${it.code}") }
    }
}
