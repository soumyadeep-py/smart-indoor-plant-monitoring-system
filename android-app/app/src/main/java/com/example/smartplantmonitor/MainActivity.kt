package com.example.smartplantmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import com.example.smartplantmonitor.ui.theme.SmartPlantMonitorTheme
import kotlinx.coroutines.delay
import java.util.Locale

private const val ALERT_CHANNEL_ID = "plant-alerts"

class MainActivity : ComponentActivity() {
    private var appState by mutableStateOf(PlantBleState())
    private var message by mutableStateOf<String?>(null)
    private lateinit var bleManager: PlantBleManager
    private var lastNotifiedAlertAt = 0L

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val bluetoothReady = permissions[Manifest.permission.BLUETOOTH_SCAN] != false &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] != false
        if (bluetoothReady) bleManager.startScan() else showMessage("Nearby devices permission is needed to connect.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createAlertChannel()
        bleManager = PlantBleManager(applicationContext) { event ->
            runOnUiThread { handleBleEvent(event) }
        }
        setContent {
            SmartPlantMonitorTheme(dynamicColor = false, darkTheme = true) {
                PlantApp(
                    state = appState,
                    message = message,
                    onConnect = ::requestPermissionsAndScan,
                    onDisconnect = bleManager::disconnect,
                    onSaveLimits = ::saveLimitFields,
                    onSaveCalibration = ::saveCalibrationFields,
                    onDismissMessage = { message = null }
                )
            }
        }
    }

    override fun onDestroy() {
        if (::bleManager.isInitialized) bleManager.close()
        super.onDestroy()
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

        if (required.isEmpty()) bleManager.startScan()
        else permissionsLauncher.launch(required.toTypedArray())
    }

    private fun handleBleEvent(event: PlantBleEvent) {
        when (event) {
            is PlantBleEvent.State -> appState = event.value
            is PlantBleEvent.Config -> appState = appState.copy(config = event.value)
            is PlantBleEvent.Message -> showMessage(event.value)
            is PlantBleEvent.Telemetry -> {
                val wasAlert = appState.telemetry?.isAlert == true
                appState = appState.copy(
                    telemetry = event.value,
                    connection = BleConnectionState.CONNECTED,
                    isScanning = false
                )
                if (event.alertEvent && (event.value.isAlert || !wasAlert)) notifyAlert(event.value)
            }
        }
    }

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
        fields.forEach { (key, value) -> bleManager.writeConfig(key, value) }
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
        fields.forEach { (key, value) -> bleManager.writeConfig(key, value) }
        showMessage("Alert limits sent to the monitor.")
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Plant alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts from the Smart Plant Monitor"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notifyAlert(telemetry: PlantTelemetry) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        val now = System.currentTimeMillis()
        if (now - lastNotifiedAlertAt < 30_000L) return
        lastNotifiedAlertAt = now
        val issues = buildList {
            if (telemetry.soil.value != null) add("Soil ${formatValue(telemetry.soil.value)}%")
            if (telemetry.temperature.value != null) add("Temp ${formatValue(telemetry.temperature.value)}°C")
            if (telemetry.humidity.value != null) add("Humidity ${formatValue(telemetry.humidity.value)}%")
            if (telemetry.light.value != null) add("Light ${formatValue(telemetry.light.value)} lx")
        }
        val content = if (issues.isEmpty()) "A sensor needs attention." else issues.joinToString(" • ")
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(com.example.smartplantmonitor.R.mipmap.ic_launcher)
            .setContentTitle("Smart Plant alert")
            .setContentText(content)
            .setStyle(android.app.Notification.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .build()
        getSystemService(NotificationManager::class.java).notify(1001, notification)
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
            1 -> CalibrationScreen(state, onSaveCalibration, Modifier.padding(padding))
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
    val telemetry = state.telemetry
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { if (telemetry?.isAlert == true) AlertBanner() else SafeBanner(state.connection) }
        item { SectionLabel("LIVE READINGS", "Updated automatically over Bluetooth") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SensorCard("Soil moisture", telemetry?.soil, "%", AppColors.green, Modifier.weight(1f))
                SensorCard("Temperature", telemetry?.temperature, "°C", AppColors.orange, Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                SensorCard("Humidity", telemetry?.humidity, "%", AppColors.blue, Modifier.weight(1f))
                SensorCard("Light", telemetry?.light, "lx", AppColors.yellow, Modifier.weight(1f))
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.surface), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("Connection details", color = Color.White, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    DetailRow("Device", state.deviceName ?: "No device connected")
                    DetailRow("Wi-Fi on ESP32", if (telemetry?.wifiEnabled == true) "Enabled" else "Off")
                    DetailRow("Sensor cycle", if (telemetry == null) "Waiting for data" else "Streaming every 2 seconds")
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
private fun AlertBanner() {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.red.copy(alpha = .18f)), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("!", color = AppColors.red, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Plant needs attention", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("One or more readings crossed an alert limit.", color = AppColors.muted)
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
private fun SensorCard(title: String, reading: SensorReading?, unit: String, accent: Color, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.surface), shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(15.dp)) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(accent))
            Spacer(Modifier.height(10.dp))
            Text(title, color = AppColors.muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(reading?.value?.let(::formatValue) ?: "—", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(3.dp))
                Text(unit, color = accent, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(reading?.state?.label ?: "WAITING", color = if (reading?.state == SensorState.FAULT || reading?.state == SensorState.NOT_FOUND) AppColors.red else accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CalibrationScreen(state: PlantBleState, onSave: (Map<String, String>) -> Unit, modifier: Modifier = Modifier) {
    val config = state.config
    var soilDry by remember(config.soilDry) { mutableStateOf(formatInput(config.soilDry)) }
    var soilWet by remember(config.soilWet) { mutableStateOf(formatInput(config.soilWet)) }
    var tempScale by remember(config.tempScale) { mutableStateOf(formatInput(config.tempScale)) }
    var tempOffset by remember(config.tempOffset) { mutableStateOf(formatInput(config.tempOffset)) }
    var humidityScale by remember(config.humidityScale) { mutableStateOf(formatInput(config.humidityScale)) }
    var humidityOffset by remember(config.humidityOffset) { mutableStateOf(formatInput(config.humidityOffset)) }
    var lightScale by remember(config.lightScale) { mutableStateOf(formatInput(config.lightScale)) }
    var lightOffset by remember(config.lightOffset) { mutableStateOf(formatInput(config.lightOffset)) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("Calibration", "Tune the board’s stored correction values. Changes are saved inside the ESP32 and survive reboot.") }
        item {
            CalibrationCard("Soil moisture", "Use the raw ADC value shown by the firmware: record a dry sensor and a fully wet sensor.") {
                NumberField("Dry raw reading", soilDry, { soilDry = it }, "ADC")
                NumberField("Wet raw reading", soilWet, { soilWet = it }, "ADC")
                SaveButton("Save soil calibration") { onSave(linkedMapOf("soildry" to soilDry, "soilwet" to soilWet)) }
            }
        }
        item {
            CalibrationCard("Temperature correction", "Corrected = raw × scale + offset. Start with scale 1.0 and offset 0.0.") {
                CorrectionFields(tempScale, tempOffset, { tempScale = it }, { tempOffset = it })
                SaveButton("Save temperature correction") { onSave(linkedMapOf("tempscale" to tempScale, "tempoffset" to tempOffset)) }
            }
        }
        item {
            CalibrationCard("Humidity correction", "Compare the sensor with a trusted reference before changing these values.") {
                CorrectionFields(humidityScale, humidityOffset, { humidityScale = it }, { humidityOffset = it })
                SaveButton("Save humidity correction") { onSave(linkedMapOf("humscale" to humidityScale, "humoffset" to humidityOffset)) }
            }
        }
        item {
            CalibrationCard("Light correction", "Correct the BH1750 reading when your reference meter shows a consistent difference.") {
                CorrectionFields(lightScale, lightOffset, { lightScale = it }, { lightOffset = it })
                SaveButton("Save light correction") { onSave(linkedMapOf("lightscale" to lightScale, "lightoffset" to lightOffset)) }
            }
        }
    }
}

@Composable
private fun AlertLimitsScreen(state: PlantBleState, onSave: (Map<String, String>) -> Unit, modifier: Modifier = Modifier) {
    val config = state.config
    var soilLow by remember(config.soilLow) { mutableStateOf(formatInput(config.soilLow)) }
    var tempLow by remember(config.tempLow) { mutableStateOf(formatInput(config.tempLow)) }
    var tempHigh by remember(config.tempHigh) { mutableStateOf(formatInput(config.tempHigh)) }
    var humidityLow by remember(config.humidityLow) { mutableStateOf(formatInput(config.humidityLow)) }
    var humidityHigh by remember(config.humidityHigh) { mutableStateOf(formatInput(config.humidityHigh)) }
    var lightLow by remember(config.lightLow) { mutableStateOf(formatInput(config.lightLow)) }
    var lightHigh by remember(config.lightHigh) { mutableStateOf(formatInput(config.lightHigh)) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageIntro("Alert limits", "The ESP32 enters alert mode when a live value leaves these ranges. A small hysteresis band prevents alert flicker.") }
        item {
            LimitCard("Soil moisture", "Alert when moisture falls below this value.") {
                NumberField("Low limit", soilLow, { soilLow = it }, "%")
            }
        }
        item {
            LimitCard("Temperature", "Alert below the low limit or above the high limit.") {
                LimitFields(tempLow, tempHigh, { tempLow = it }, { tempHigh = it }, "°C")
            }
        }
        item {
            LimitCard("Humidity", "Alert below the low limit or above the high limit.") {
                LimitFields(humidityLow, humidityHigh, { humidityLow = it }, { humidityHigh = it }, "%")
            }
        }
        item {
            LimitCard("Light", "Alert below the low limit or above the high limit.") {
                LimitFields(lightLow, lightHigh, { lightLow = it }, { lightHigh = it }, "lux")
            }
        }
        item {
            SaveButton("Save all alert limits") {
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
private fun CorrectionFields(scale: String, offset: String, onScale: (String) -> Unit, onOffset: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField("Scale", scale, onScale, "×", Modifier.weight(1f))
        NumberField("Offset", offset, onOffset, "", Modifier.weight(1f))
    }
}

@Composable
private fun LimitFields(low: String, high: String, onLow: (String) -> Unit, onHigh: (String) -> Unit, unit: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        NumberField("Low", low, onLow, unit, Modifier.weight(1f))
        NumberField("High", high, onHigh, unit, Modifier.weight(1f))
    }
}

@Composable
private fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, unit: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        suffix = if (unit.isNotEmpty()) ({ Text(unit) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SaveButton(label: String, onClick: () -> Unit) {
    Spacer(Modifier.height(12.dp))
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = AppColors.mint), modifier = Modifier.fillMaxWidth()) {
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
    val muted = Color(0xFFA8B7B0)
}
