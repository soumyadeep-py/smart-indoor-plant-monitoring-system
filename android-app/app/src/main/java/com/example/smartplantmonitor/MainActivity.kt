package com.example.smartplantmonitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.smartplantmonitor.ui.theme.SmartPlantMonitorTheme
import kotlinx.coroutines.delay
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var appState by mutableStateOf(PlantBleState())
    private var message by mutableStateOf<String?>(null)
    private var receiverRegistered = false

    private val monitorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let(::handleMonitorIntent)
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bluetoothReady = permissions[Manifest.permission.BLUETOOTH_SCAN] != false &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] != false
        if (bluetoothReady) startMonitorService()
        else showMessage("Nearby devices permission is needed to connect.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerMonitorReceiver()
        setContent {
            SmartPlantMonitorTheme(dynamicColor = false, darkTheme = true) {
                PlantApp(
                    state = appState,
                    message = message,
                    onConnect = ::requestPermissionsAndScan,
                    onDisconnect = ::disconnectMonitor,
                    onSaveLimits = ::saveLimitFields,
                    onSaveCalibration = ::saveCalibrationFields,
                    onSaveCalibrationLimit = ::saveCalibrationLimit,
                    onDismissMessage = { message = null }
                )
            }
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(monitorReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerMonitorReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(MonitorServiceContract.ACTION_STATE)
            addAction(MonitorServiceContract.ACTION_TELEMETRY)
            addAction(MonitorServiceContract.ACTION_CONFIG)
            addAction(MonitorServiceContract.ACTION_MESSAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(monitorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(monitorReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun requestPermissionsAndScan() {
        val required = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }

        if (required.isEmpty()) startMonitorService()
        else permissionsLauncher.launch(required.toTypedArray())
    }

    private fun startMonitorService() {
        val intent = Intent(this, PlantMonitorService::class.java)
            .setAction(MonitorServiceContract.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun disconnectMonitor() {
        startService(
            Intent(this, PlantMonitorService::class.java)
                .setAction(MonitorServiceContract.ACTION_STOP)
        )
    }

    private fun sendMonitorCommand(action: String, key: String, value: String) {
        startService(
            Intent(this, PlantMonitorService::class.java)
                .setAction(action)
                .putExtra(MonitorServiceContract.EXTRA_KEY, key)
                .putExtra(MonitorServiceContract.EXTRA_VALUE, value)
        )
    }

    private fun handleMonitorIntent(intent: Intent) {
        when (intent.action) {
            MonitorServiceContract.ACTION_STATE -> {
                val connection = runCatching {
                    BleConnectionState.valueOf(
                        intent.getStringExtra(MonitorServiceContract.EXTRA_CONNECTION)
                            ?: BleConnectionState.DISCONNECTED.name
                    )
                }.getOrDefault(BleConnectionState.DISCONNECTED)
                val wasConnected = appState.connection == BleConnectionState.CONNECTED
                appState = appState.copy(
                    connection = connection,
                    deviceName = intent.getStringExtra(MonitorServiceContract.EXTRA_DEVICE_NAME),
                    isScanning = intent.getBooleanExtra(MonitorServiceContract.EXTRA_SCANNING, false),
                    telemetry = if (connection == BleConnectionState.CONNECTED) appState.telemetry else null
                )
                if (!wasConnected && connection == BleConnectionState.CONNECTED) {
                    startService(
                        Intent(this, PlantMonitorService::class.java)
                            .setAction(MonitorServiceContract.ACTION_SYNC_STATE)
                    )
                }
            }
            MonitorServiceContract.ACTION_TELEMETRY -> {
                val wasConnected = appState.connection == BleConnectionState.CONNECTED
                val telemetry = PlantTelemetry(
                    soil = readSensorReading(intent, "soil"),
                    temperature = readSensorReading(intent, "temperature"),
                    humidity = readSensorReading(intent, "humidity"),
                    light = readSensorReading(intent, "light"),
                    isAlert = intent.getBooleanExtra(MonitorServiceContract.EXTRA_IS_ALERT, false),
                    wifiEnabled = intent.getBooleanExtra(MonitorServiceContract.EXTRA_WIFI, false),
                    alertReasons = if (intent.hasExtra(MonitorServiceContract.EXTRA_ALERT_REASONS)) {
                        intent.getIntExtra(MonitorServiceContract.EXTRA_ALERT_REASONS, 0)
                    } else {
                        null
                    }
                )
                appState = appState.copy(
                    telemetry = telemetry,
                    connection = BleConnectionState.CONNECTED,
                    isScanning = false
                )
                if (!wasConnected) {
                    startService(
                        Intent(this, PlantMonitorService::class.java)
                            .setAction(MonitorServiceContract.ACTION_SYNC_STATE)
                    )
                }
            }
            MonitorServiceContract.ACTION_CONFIG -> {
                appState = appState.copy(config = readConfig(intent))
            }
            MonitorServiceContract.ACTION_MESSAGE -> {
                intent.getStringExtra(MonitorServiceContract.EXTRA_MESSAGE)?.let(::showMessage)
            }
        }
    }

    private fun readSensorReading(intent: Intent, prefix: String): SensorReading {
        val rawValue = intent.getFloatExtra("${prefix}_value", Float.NaN)
        val state = runCatching {
            SensorState.valueOf(
                intent.getStringExtra("${prefix}_state") ?: SensorState.UNKNOWN.name
            )
        }.getOrDefault(SensorState.UNKNOWN)
        return SensorReading(rawValue.takeUnless { it.isNaN() }, state)
    }

    private fun readConfig(intent: Intent): PlantConfig = PlantConfig(
        soilDry = intent.getFloatExtra("config_soilDry", 3000f),
        soilWet = intent.getFloatExtra("config_soilWet", 1500f),
        tempScale = intent.getFloatExtra("config_tempScale", 1f),
        tempOffset = intent.getFloatExtra("config_tempOffset", 0f),
        humidityScale = intent.getFloatExtra("config_humidityScale", 1f),
        humidityOffset = intent.getFloatExtra("config_humidityOffset", 0f),
        lightScale = intent.getFloatExtra("config_lightScale", 1f),
        lightOffset = intent.getFloatExtra("config_lightOffset", 0f),
        soilLow = intent.getFloatExtra("config_soilLow", 30f),
        tempLow = intent.getFloatExtra("config_tempLow", 18f),
        tempHigh = intent.getFloatExtra("config_tempHigh", 32f),
        humidityLow = intent.getFloatExtra("config_humidityLow", 35f),
        humidityHigh = intent.getFloatExtra("config_humidityHigh", 85f),
        lightLow = intent.getFloatExtra("config_lightLow", 100f),
        lightHigh = intent.getFloatExtra("config_lightHigh", 2000f)
    )

    private fun saveCalibrationFields(fields: Map<String, String>) {
        val parsed = fields.mapValues { it.value.trim().toFloatOrNull() }
        if (parsed.values.any { it == null }) {
            showMessage("Enter a number in every calibration field.")
            return
        }
        if ("soildry" in parsed && "soilwet" in parsed) {
            val dry = parsed["soildry"]!!
            val wet = parsed["soilwet"]!!
            if (kotlin.math.abs(dry - wet) < 10f) {
                showMessage("Dry and wet soil readings should differ by at least 10 ADC counts.")
                return
            }
        }
        fields.forEach { (key, value) ->
            sendMonitorCommand(MonitorServiceContract.ACTION_WRITE_CONFIG, key, value)
        }
        showMessage("Calibration values sent to the monitor.")
    }

    private fun saveLimitFields(fields: Map<String, String>) {
        val parsed = fields.mapValues { it.value.trim().toFloatOrNull() }
        if (parsed.values.any { it == null }) {
            showMessage("Enter a number in every alert limit field.")
            return
        }
        val tempLow = parsed["templow"]!!
        val tempHigh = parsed["temphigh"]!!
        val humidityLow = parsed["humlow"]!!
        val humidityHigh = parsed["humhigh"]!!
        val lightLow = parsed["lightlow"]!!
        val lightHigh = parsed["lighthigh"]!!
        if (tempLow >= tempHigh || humidityLow >= humidityHigh || lightLow >= lightHigh) {
            showMessage("Each lower limit must be less than its upper limit.")
            return
        }
        fields.forEach { (key, value) ->
            sendMonitorCommand(MonitorServiceContract.ACTION_WRITE_CONFIG, key, value)
        }
        showMessage("Alert limits sent to the monitor.")
    }

    private fun saveCalibrationLimit(key: String, value: String): Boolean {
        val parsed = value.trim().toFloatOrNull()
        if (parsed == null || !parsed.isFinite()) {
            showMessage("The calibrated value is not a valid number.")
            return false
        }
        val config = appState.config
        val valid = when (key) {
            "soillow" -> parsed in 0f..100f
            "templow" -> parsed in -40f..85f && parsed < config.tempHigh
            "temphigh" -> parsed in -40f..85f && parsed > config.tempLow
            "humlow" -> parsed in 0f..100f && parsed < config.humidityHigh
            "humhigh" -> parsed in 0f..100f && parsed > config.humidityLow
            "lightlow" -> parsed >= 0f && parsed < config.lightHigh
            "lighthigh" -> parsed >= 0f && parsed > config.lightLow
            else -> false
        }
        if (!valid) {
            showMessage("The calibrated limit must stay inside a safe range and preserve the low/high order.")
            return false
        }
        sendMonitorCommand(MonitorServiceContract.ACTION_WRITE_CONFIG, key, value)
        showMessage("${key.limitLabel()} calibration sent to the monitor.")
        return true
    }

    private fun showMessage(value: String) {
        message = value
    }
}

@Composable
private fun PlantApp(
    state: PlantBleState,
    message: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSaveLimits: (Map<String, String>) -> Unit,
    onSaveCalibration: (Map<String, String>) -> Unit,
    onSaveCalibrationLimit: (String, String) -> Boolean,
    onDismissMessage: () -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            delay(3_500)
            onDismissMessage()
        }
    }
    Scaffold(
        containerColor = AppColors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { AppHeader(state, onConnect, onDisconnect) },
        bottomBar = {
            NavigationBar(containerColor = AppColors.surface) {
                listOf("Monitor", "Calibrate", "Alert limits").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = { tab = index },
                        icon = { Text(if (index == 0) "◉" else if (index == 1) "◇" else "!", fontSize = 18.sp) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            0 -> OverviewScreen(state, onConnect, Modifier.padding(padding))
            1 -> CalibrationScreen(state, onSaveCalibration, onSaveCalibrationLimit, Modifier.padding(padding))
            else -> AlertLimitsScreen(state, onSaveLimits, Modifier.padding(padding))
        }
    }
}

@Composable
private fun AppHeader(state: PlantBleState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Surface(color = AppColors.background) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SMART PLANT", color = AppColors.mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("Monitor", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                ConnectionPill(state.connection)
            }
            Spacer(Modifier.height(12.dp))
            if (state.connection == BleConnectionState.DISCONNECTED) {
                Button(onClick = onConnect, colors = ButtonDefaults.buttonColors(containerColor = AppColors.mint), modifier = Modifier.fillMaxWidth()) {
                    Text("Find ESP32 monitor", color = AppColors.background, fontWeight = FontWeight.Bold)
                }
            } else if (state.connection == BleConnectionState.SCANNING || state.connection == BleConnectionState.CONNECTING) {
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Search again") }
            } else {
                OutlinedButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect ${state.deviceName ?: "monitor"}") }
            }
        }
    }
}

@Composable
private fun ConnectionPill(connection: BleConnectionState) {
    val connected = connection == BleConnectionState.CONNECTED
    Surface(color = if (connected) AppColors.green.copy(alpha = .18f) else AppColors.surface2, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(if (connected) AppColors.green else AppColors.amber))
            Spacer(Modifier.width(6.dp))
            Text(connection.label, color = if (connected) AppColors.green else AppColors.amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OverviewScreen(state: PlantBleState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val connected = state.connection == BleConnectionState.CONNECTED
    val telemetry = if (connected) state.telemetry else null
    val alerts = telemetry?.let { alertIssues(it, state.config) }.orEmpty()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { if (telemetry?.isAlert == true || alerts.isNotEmpty()) AlertBanner(alerts) else SafeBanner(state.connection) }
        item { SectionLabel("LIVE READINGS", "Updated automatically over Bluetooth") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SensorCard("Soil moisture", telemetry?.soil, "%", AppColors.green, alerts.firstOrNull { it.sensor == "soil" }, Modifier.weight(1f))
                SensorCard("Temperature", telemetry?.temperature, "°C", AppColors.orange, alerts.firstOrNull { it.sensor == "temperature" }, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SensorCard("Humidity", telemetry?.humidity, "%", AppColors.blue, alerts.firstOrNull { it.sensor == "humidity" }, Modifier.weight(1f))
                SensorCard("Light", telemetry?.light, "lx", AppColors.yellow, alerts.firstOrNull { it.sensor == "light" }, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.surface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Connection details", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    DetailRow("Device", state.deviceName ?: "No device connected")
                    DetailRow("Wi-Fi on ESP32", if (!connected) "--" else if (telemetry?.wifiEnabled == true) "Enabled" else "Off")
                    DetailRow("Sensor cycle", if (!connected) "Waiting for connection" else if (telemetry == null) "Waiting for data" else "Streaming every 2 seconds")
                    if (state.connection == BleConnectionState.DISCONNECTED) {
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = onConnect) { Text("Connect to start monitoring") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertBanner(alerts: List<PlantAlert>) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.red.copy(alpha = .18f)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("!", color = AppColors.red, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Plant needs attention", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (alerts.isEmpty()) {
                    Text("One or more readings crossed an alert limit.", color = AppColors.muted)
                } else {
                    alerts.forEach { alert ->
                        Text(alert.message, color = AppColors.muted, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeBanner(connection: BleConnectionState) {
    val connected = connection == BleConnectionState.CONNECTED
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.green.copy(alpha = .13f)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = AppColors.green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(if (connected) "Plant is in a safe range" else "Ready to connect", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(if (connected) "No active sensor alerts right now." else "Connect to read live sensor values.", color = AppColors.muted)
            }
        }
    }
}

@Composable
private fun SensorCard(title: String, reading: SensorReading?, unit: String, accent: Color, alert: PlantAlert?, modifier: Modifier = Modifier) {
    val hasSensorError = reading?.state == SensorState.FAULT || reading?.state == SensorState.NOT_FOUND
    Card(
        colors = CardDefaults.cardColors(containerColor = if (alert != null) AppColors.alertSurface else AppColors.surface),
        border = alert?.let { BorderStroke(2.dp, AppColors.red) },
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(15.dp)) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(if (alert != null) AppColors.red else accent))
            Spacer(Modifier.height(10.dp))
            Text(title, color = AppColors.muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            val liveValue = reading?.value
            if (liveValue != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatValue(liveValue), color = if (alert != null) AppColors.red else Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(3.dp))
                    Text(unit, color = if (alert != null) AppColors.red else accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                }
            } else {
                Text("--", color = if (alert != null) AppColors.red else Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (alert != null) "ALERT · ${alert.shortLabel}" else reading?.state?.label ?: "WAITING",
                color = if (alert != null || hasSensorError) AppColors.red else accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CalibrationScreen(
    state: PlantBleState,
    onSave: (Map<String, String>) -> Unit,
    onSaveLimit: (String, String) -> Boolean,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val connected = state.connection == BleConnectionState.CONNECTED && state.telemetry != null
    val latestTelemetry by rememberUpdatedState(if (connected) state.telemetry else null)
    var activeTargetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var samples by remember { mutableStateOf<List<Float>>(emptyList()) }
    var average by remember { mutableStateOf<Float?>(null) }
    var adjustedAverage by remember { mutableStateOf<Float?>(null) }
    var samplingError by remember { mutableStateOf(false) }
    val activeTarget = activeTargetKey?.let(GuidedCalibrationTarget::fromKey)

    LaunchedEffect(activeTargetKey, connected) {
        samples = emptyList()
        average = null
        adjustedAverage = null
        samplingError = false
        val target = activeTarget ?: return@LaunchedEffect
        if (!connected) {
            samplingError = true
            return@LaunchedEffect
        }
        val collected = mutableListOf<Float>()
        repeat(3) { index ->
            if (index > 0) delay(1_000)
            var reading: Float? = null
            var attempts = 0
            while (reading == null && attempts < 40) {
                reading = latestTelemetry?.let { readCalibrationValue(target, it) }
                if (reading == null) {
                    delay(250)
                    attempts++
                }
            }
            if (reading == null) {
                samplingError = true
                return@LaunchedEffect
            }
            collected += reading
            samples = collected.toList()
        }
        val result = collected.average().toFloat()
        average = result
        adjustedAverage = result
    }

    var soilDry by remember(config.soilDry, connected) { mutableStateOf(if (connected) formatInput(config.soilDry) else "--") }
    var soilWet by remember(config.soilWet, connected) { mutableStateOf(if (connected) formatInput(config.soilWet) else "--") }
    var tempScale by remember(config.tempScale, connected) { mutableStateOf(if (connected) formatInput(config.tempScale) else "--") }
    var tempOffset by remember(config.tempOffset, connected) { mutableStateOf(if (connected) formatInput(config.tempOffset) else "--") }
    var humidityScale by remember(config.humidityScale, connected) { mutableStateOf(if (connected) formatInput(config.humidityScale) else "--") }
    var humidityOffset by remember(config.humidityOffset, connected) { mutableStateOf(if (connected) formatInput(config.humidityOffset) else "--") }
    var lightScale by remember(config.lightScale, connected) { mutableStateOf(if (connected) formatInput(config.lightScale) else "--") }
    var lightOffset by remember(config.lightOffset, connected) { mutableStateOf(if (connected) formatInput(config.lightOffset) else "--") }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("Guided calibration", "Choose a limit, keep the sensor steady, and the app will average three live readings before saving it to the ESP32.") }
        item {
            GuidedCalibrationCard(
                target = activeTarget,
                samples = samples,
                average = adjustedAverage,
                samplingError = samplingError,
                hasTelemetry = connected && latestTelemetry != null,
                config = config,
                onAdjust = { direction ->
                    adjustedAverage = adjustedAverage?.let { adjustCalibrationValue(activeTarget!!, it, direction) }
                },
                onSave = {
                    val target = activeTarget
                    val value = adjustedAverage
                    if (target != null && value != null) {
                        if (onSaveLimit(target.key, formatCalibrationValue(target, value))) {
                            activeTargetKey = null
                        }
                    }
                },
                onCancel = { activeTargetKey = null }
            )
        }
        item { SectionLabel("CHOOSE A LIMIT", "The phone uses the same live sensor stream as the monitor.") }
        items(GuidedCalibrationTarget.values().toList(), key = { it.key }) { target ->
            CalibrationTargetCard(
                target = target,
                currentValue = if (connected) currentCalibrationLimit(target, config) else Float.NaN,
                enabled = connected,
                onStart = { activeTargetKey = target.key }
            )
        }
        item { SectionLabel("MANUAL SENSOR CORRECTION", "Use these only when comparing against a trusted reference meter.") }
        item {
            CalibrationCard("Soil moisture", "Use the raw ADC value shown by the firmware: record a dry sensor and a fully wet sensor.") {
                NumberField("Dry raw reading", soilDry, { soilDry = it }, "ADC", enabled = connected)
                NumberField("Wet raw reading", soilWet, { soilWet = it }, "ADC", enabled = connected)
                SaveButton("Save soil calibration", enabled = connected) { onSave(linkedMapOf("soildry" to soilDry, "soilwet" to soilWet)) }
            }
        }
        item {
            CalibrationCard("Temperature correction", "Corrected = raw × scale + offset. Start with scale 1.0 and offset 0.0.") {
                CorrectionFields(tempScale, tempOffset, { tempScale = it }, { tempOffset = it }, enabled = connected)
                SaveButton("Save temperature correction", enabled = connected) { onSave(linkedMapOf("tempscale" to tempScale, "tempoffset" to tempOffset)) }
            }
        }
        item {
            CalibrationCard("Humidity correction", "Compare the sensor with a trusted reference before changing these values.") {
                CorrectionFields(humidityScale, humidityOffset, { humidityScale = it }, { humidityOffset = it }, enabled = connected)
                SaveButton("Save humidity correction", enabled = connected) { onSave(linkedMapOf("humscale" to humidityScale, "humoffset" to humidityOffset)) }
            }
        }
        item {
            CalibrationCard("Light correction", "Correct the BH1750 reading when your reference meter shows a consistent difference.") {
                CorrectionFields(lightScale, lightOffset, { lightScale = it }, { lightOffset = it }, enabled = connected)
                SaveButton("Save light correction", enabled = connected) { onSave(linkedMapOf("lightscale" to lightScale, "lightoffset" to lightOffset)) }
            }
        }
    }
}

private enum class GuidedCalibrationTarget(
    val key: String,
    val label: String,
    val description: String,
    val unit: String,
    val decimals: Int,
    val isHigh: Boolean
) {
    SOIL_LOW("soillow", "Soil moisture minimum", "Set the point below which the plant needs water.", "%", 0, false),
    TEMP_LOW("templow", "Temperature minimum", "Set the cold limit for this plant.", "°C", 1, false),
    TEMP_HIGH("temphigh", "Temperature maximum", "Set the heat limit for this plant.", "°C", 1, true),
    HUMIDITY_LOW("humlow", "Humidity minimum", "Set the dry-air limit around the plant.", "%", 1, false),
    HUMIDITY_HIGH("humhigh", "Humidity maximum", "Set the overly-humid limit.", "%", 1, true),
    LIGHT_LOW("lightlow", "Light minimum", "Set the point below which the plant needs more light.", "lux", 0, false),
    LIGHT_HIGH("lighthigh", "Light maximum", "Set the point above which the light is too intense.", "lux", 0, true);

    companion object {
        fun fromKey(key: String): GuidedCalibrationTarget? = values().firstOrNull { it.key == key }
    }
}

private fun readCalibrationValue(target: GuidedCalibrationTarget, telemetry: PlantTelemetry): Float? = when (target) {
    GuidedCalibrationTarget.SOIL_LOW -> telemetry.soil.value
    GuidedCalibrationTarget.TEMP_LOW, GuidedCalibrationTarget.TEMP_HIGH -> telemetry.temperature.value
    GuidedCalibrationTarget.HUMIDITY_LOW, GuidedCalibrationTarget.HUMIDITY_HIGH -> telemetry.humidity.value
    GuidedCalibrationTarget.LIGHT_LOW, GuidedCalibrationTarget.LIGHT_HIGH -> telemetry.light.value
}

private fun currentCalibrationLimit(target: GuidedCalibrationTarget, config: PlantConfig): Float = when (target) {
    GuidedCalibrationTarget.SOIL_LOW -> config.soilLow
    GuidedCalibrationTarget.TEMP_LOW -> config.tempLow
    GuidedCalibrationTarget.TEMP_HIGH -> config.tempHigh
    GuidedCalibrationTarget.HUMIDITY_LOW -> config.humidityLow
    GuidedCalibrationTarget.HUMIDITY_HIGH -> config.humidityHigh
    GuidedCalibrationTarget.LIGHT_LOW -> config.lightLow
    GuidedCalibrationTarget.LIGHT_HIGH -> config.lightHigh
}

private fun adjustCalibrationValue(target: GuidedCalibrationTarget, value: Float, direction: Int): Float {
    val step = if (target.decimals == 1) 0.1f else 1f
    val adjusted = value + direction * step
    return when (target) {
        GuidedCalibrationTarget.SOIL_LOW -> adjusted.coerceIn(0f, 100f)
        GuidedCalibrationTarget.TEMP_LOW, GuidedCalibrationTarget.TEMP_HIGH -> adjusted.coerceIn(-40f, 85f)
        GuidedCalibrationTarget.HUMIDITY_LOW, GuidedCalibrationTarget.HUMIDITY_HIGH -> adjusted.coerceIn(0f, 100f)
        GuidedCalibrationTarget.LIGHT_LOW, GuidedCalibrationTarget.LIGHT_HIGH -> adjusted.coerceAtLeast(0f)
    }
}

private fun formatCalibrationValue(target: GuidedCalibrationTarget, value: Float): String =
    if (value.isNaN() || value.isInfinite()) "--"
    else String.format(Locale.US, "%.${target.decimals}f", value)

private fun String.limitLabel(): String = when (this) {
    "soillow" -> "Soil moisture"
    "templow", "temphigh" -> "Temperature"
    "humlow", "humhigh" -> "Humidity"
    "lightlow", "lighthigh" -> "Light"
    else -> "Sensor"
}

@Composable
private fun GuidedCalibrationCard(
    target: GuidedCalibrationTarget?,
    samples: List<Float>,
    average: Float?,
    samplingError: Boolean,
    hasTelemetry: Boolean,
    config: PlantConfig,
    onAdjust: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.surface2), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("Calibration session", color = AppColors.mint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(6.dp))
            if (target == null) {
                Text("No limit selected", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Tap a limit below to take three readings from the connected sensor.", color = AppColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                if (!hasTelemetry) {
                    Spacer(Modifier.height(8.dp))
                    Text("Connect to the ESP32 to begin.", color = AppColors.amber, fontSize = 12.sp)
                }
            } else {
                Text(target.label, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(target.description, color = AppColors.muted, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Saved limit: ${formatCalibrationValue(target, if (hasTelemetry) currentCalibrationLimit(target, config) else Float.NaN)} ${target.unit}",
                    color = AppColors.mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                when {
                    samplingError -> {
                        Text("Could not get three live readings. Keep the monitor connected and try again.", color = AppColors.red, fontSize = 13.sp)
                        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Close session") }
                    }
                    average == null -> {
                        Text("Taking reading ${samples.size + 1} of 3…", color = AppColors.orange, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text("Keep the sensor steady. One reading is taken every second.", color = AppColors.muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        CalibrationProgress(samples.size)
                    }
                    else -> {
                        Text("Three-reading average", color = AppColors.muted, fontSize = 12.sp)
                        Text("${formatCalibrationValue(target, average)} ${target.unit}", color = AppColors.mint, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text("Adjust in ${if (target.decimals == 1) "0.1" else "1"} ${target.unit} steps if needed.", color = AppColors.muted, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { onAdjust(-1) }, modifier = Modifier.weight(1f)) { Text("− ${if (target.decimals == 1) "0.1" else "1"}") }
                            OutlinedButton(onClick = { onAdjust(1) }, modifier = Modifier.weight(1f)) { Text("+ ${if (target.decimals == 1) "0.1" else "1"}") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = AppColors.mint), modifier = Modifier.weight(1f)) {
                                Text("Save limit", color = AppColors.background, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalibrationProgress(count: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(3) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (index < count) AppColors.mint else AppColors.surface)
            )
        }
    }
}

@Composable
private fun CalibrationTargetCard(target: GuidedCalibrationTarget, currentValue: Float, enabled: Boolean, onStart: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.surface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(target.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                Text("CURRENT LIMIT", color = AppColors.mint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text("${formatCalibrationValue(target, currentValue)} ${target.unit}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(onClick = onStart, enabled = enabled) { Text(if (enabled) "Measure" else "Connect") }
        }
    }
}

@Composable
private fun AlertLimitsScreen(state: PlantBleState, onSave: (Map<String, String>) -> Unit, modifier: Modifier = Modifier) {
    val config = state.config
    val connected = state.connection == BleConnectionState.CONNECTED && state.telemetry != null
    var soilLow by remember(config.soilLow, connected) { mutableStateOf(if (connected) formatInput(config.soilLow) else "--") }
    var tempLow by remember(config.tempLow, connected) { mutableStateOf(if (connected) formatInput(config.tempLow) else "--") }
    var tempHigh by remember(config.tempHigh, connected) { mutableStateOf(if (connected) formatInput(config.tempHigh) else "--") }
    var humidityLow by remember(config.humidityLow, connected) { mutableStateOf(if (connected) formatInput(config.humidityLow) else "--") }
    var humidityHigh by remember(config.humidityHigh, connected) { mutableStateOf(if (connected) formatInput(config.humidityHigh) else "--") }
    var lightLow by remember(config.lightLow, connected) { mutableStateOf(if (connected) formatInput(config.lightLow) else "--") }
    var lightHigh by remember(config.lightHigh, connected) { mutableStateOf(if (connected) formatInput(config.lightHigh) else "--") }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("Alert limits", "The ESP32 enters alert mode when a live value leaves these ranges. A small hysteresis band prevents alert flicker.") }
        item {
            LimitCard("Soil moisture", "Alert when moisture falls below this value.") {
                NumberField("Low limit", soilLow, { soilLow = it }, "%", enabled = connected)
            }
        }
        item {
            LimitCard("Temperature", "Alert below the low limit or above the high limit.") {
                LimitFields(tempLow, tempHigh, { tempLow = it }, { tempHigh = it }, "°C", enabled = connected)
            }
        }
        item {
            LimitCard("Humidity", "Alert below the low limit or above the high limit.") {
                LimitFields(humidityLow, humidityHigh, { humidityLow = it }, { humidityHigh = it }, "%", enabled = connected)
            }
        }
        item {
            LimitCard("Light", "Alert below the low limit or above the high limit.") {
                LimitFields(lightLow, lightHigh, { lightLow = it }, { lightHigh = it }, "lux", enabled = connected)
            }
        }
        item {
            SaveButton("Save all alert limits", enabled = connected) {
                onSave(linkedMapOf("soillow" to soilLow, "templow" to tempLow, "temphigh" to tempHigh, "humlow" to humidityLow, "humhigh" to humidityHigh, "lightlow" to lightLow, "lighthigh" to lightHigh))
            }
        }
    }
}

@Composable
private fun PageIntro(title: String, description: String) {
    Column(Modifier.padding(bottom = 3.dp)) {
        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(description, color = AppColors.muted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun CalibrationCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.surface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(description, color = AppColors.muted, fontSize = 12.sp, lineHeight = 17.sp)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun LimitCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) = CalibrationCard(title, description, content)

@Composable
private fun CorrectionFields(scale: String, offset: String, onScale: (String) -> Unit, onOffset: (String) -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField("Scale", scale, onScale, "×", Modifier.weight(1f), enabled)
        NumberField("Offset", offset, onOffset, "", Modifier.weight(1f), enabled)
    }
}

@Composable
private fun LimitFields(low: String, high: String, onLow: (String) -> Unit, onHigh: (String) -> Unit, unit: String, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField("Low", low, onLow, unit, Modifier.weight(1f), enabled)
        NumberField("High", high, onHigh, unit, Modifier.weight(1f), enabled)
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, unit: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SaveButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Button(onClick = onClick, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = AppColors.mint), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = AppColors.background, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String) {
    Column {
        Text(title, color = AppColors.mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Text(subtitle, color = AppColors.muted, fontSize = 12.sp)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = AppColors.muted, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatValue(value: Float): String = String.format(Locale.US, "%.1f", value)
private fun formatInput(value: Float): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private object AppColors {
    val background = Color(0xFF0B1512)
    val surface = Color(0xFF14231E)
    val surface2 = Color(0xFF1D3029)
    val mint = Color(0xFF9FE870)
    val green = Color(0xFF66D18F)
    val orange = Color(0xFFFFA65C)
    val blue = Color(0xFF73C6F4)
    val yellow = Color(0xFFFFD166)
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF6B6B)
    val alertSurface = Color(0xFF351C1D)
    val muted = Color(0xFFA8B7B0)
}
