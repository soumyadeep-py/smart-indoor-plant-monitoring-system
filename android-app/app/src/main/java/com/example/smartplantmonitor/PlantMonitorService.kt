package com.example.smartplantmonitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

object MonitorServiceContract {
    const val ACTION_START = "com.example.smartplantmonitor.action.START"
    const val ACTION_STOP = "com.example.smartplantmonitor.action.STOP"
    const val ACTION_WRITE_CONFIG = "com.example.smartplantmonitor.action.WRITE_CONFIG"
    const val ACTION_SYNC_STATE = "com.example.smartplantmonitor.action.SYNC_STATE"

    const val ACTION_STATE = "com.example.smartplantmonitor.event.STATE"
    const val ACTION_TELEMETRY = "com.example.smartplantmonitor.event.TELEMETRY"
    const val ACTION_CONFIG = "com.example.smartplantmonitor.event.CONFIG"
    const val ACTION_MESSAGE = "com.example.smartplantmonitor.event.MESSAGE"

    const val EXTRA_CONNECTION = "connection"
    const val EXTRA_DEVICE_NAME = "device_name"
    const val EXTRA_SCANNING = "scanning"
    const val EXTRA_IS_ALERT = "is_alert"
    const val EXTRA_WIFI = "wifi"
    const val EXTRA_ALERT_REASONS = "alert_reasons"
    const val EXTRA_KEY = "key"
    const val EXTRA_VALUE = "value"
    const val EXTRA_MESSAGE = "message"
}

private const val SERVICE_CHANNEL_ID = "plant-monitor-service"
private const val ALERT_CHANNEL_ID = "plant-alerts"
private const val SERVICE_NOTIFICATION_ID = 2001
private const val ALERT_NOTIFICATION_ID = 1001

class PlantMonitorService : Service() {
    private lateinit var bleManager: PlantBleManager
    private var latestState = PlantBleState()
    private var latestConfig = PlantConfig()
    private var latestTelemetry: PlantTelemetry? = null
    private var lastNotifiedAlertKeys = emptySet<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        bleManager = PlantBleManager(applicationContext) { event ->
            handleBleEvent(event)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            MonitorServiceContract.ACTION_STOP -> {
                bleManager.disconnect()
                latestTelemetry = null
                lastNotifiedAlertKeys = emptySet()
                stopForegroundCompat()
                stopSelf()
            }
            MonitorServiceContract.ACTION_WRITE_CONFIG -> {
                val key = intent.getStringExtra(MonitorServiceContract.EXTRA_KEY)
                val value = intent.getStringExtra(MonitorServiceContract.EXTRA_VALUE)
                if (key != null && value != null) bleManager.writeConfig(key, value)
            }
            MonitorServiceContract.ACTION_SYNC_STATE -> {
                broadcastState()
                broadcastConfig(latestConfig)
                latestTelemetry?.let(::broadcastTelemetry)
            }
            else -> bleManager.startScan()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::bleManager.isInitialized) bleManager.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleBleEvent(event: PlantBleEvent) {
        when (event) {
            is PlantBleEvent.State -> {
                if (event.value.connection == BleConnectionState.DISCONNECTED) {
                    latestTelemetry = null
                    lastNotifiedAlertKeys = emptySet()
                    latestState = event.value.copy(telemetry = null)
                } else {
                    latestState = event.value
                }
                broadcastState()
            }
            is PlantBleEvent.Config -> {
                latestConfig = event.value
                broadcastConfig(event.value)
            }
            is PlantBleEvent.Message -> broadcastMessage(event.value)
            is PlantBleEvent.Telemetry -> {
                latestTelemetry = event.value
                latestState = latestState.copy(
                    telemetry = event.value,
                    connection = BleConnectionState.CONNECTED,
                    isScanning = false,
                    config = latestConfig
                )
                broadcastTelemetry(event.value)
                broadcastState()
                handleAlertNotification(event)
            }
        }
    }

    private fun handleAlertNotification(event: PlantBleEvent.Telemetry) {
        val alerts = alertIssues(event.value, latestConfig)
        val currentKeys = alerts.map { "${it.sensor}:${it.shortLabel}" }.toSet()
        if (currentKeys.isEmpty()) {
            lastNotifiedAlertKeys = emptySet()
            return
        }
        if (!event.alertEvent) return

        val newAlerts = alerts.filter { "${it.sensor}:${it.shortLabel}" !in lastNotifiedAlertKeys }
        if (newAlerts.isNotEmpty()) {
            notifyAlert(newAlerts)
            lastNotifiedAlertKeys = currentKeys
        }
    }

    private fun notifyAlert(alerts: List<PlantAlert>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return

        val content = alerts.joinToString("\n") { "• ${it.message}" }
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, ALERT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Smart Plant alert")
            .setContentText(content)
            .setStyle(android.app.Notification.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun ensureForeground() {
        val notification = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildServiceNotification(): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, SERVICE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Smart Plant Monitor")
            .setContentText("Monitoring plant sensors in the background")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(android.app.Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Background monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Smart Plant Monitor connected to the ESP32."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Plant alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts from the Smart Plant Monitor"
                enableVibration(true)
            }
        )
    }

    private fun broadcastState() {
        sendMonitorBroadcast(Intent(MonitorServiceContract.ACTION_STATE).apply {
            putExtra(MonitorServiceContract.EXTRA_CONNECTION, latestState.connection.name)
            putExtra(MonitorServiceContract.EXTRA_DEVICE_NAME, latestState.deviceName)
            putExtra(MonitorServiceContract.EXTRA_SCANNING, latestState.isScanning)
        })
    }

    private fun broadcastTelemetry(telemetry: PlantTelemetry) {
        sendMonitorBroadcast(Intent(MonitorServiceContract.ACTION_TELEMETRY).apply {
            putReading("soil", telemetry.soil)
            putReading("temperature", telemetry.temperature)
            putReading("humidity", telemetry.humidity)
            putReading("light", telemetry.light)
            putExtra(MonitorServiceContract.EXTRA_IS_ALERT, telemetry.isAlert)
            putExtra(MonitorServiceContract.EXTRA_WIFI, telemetry.wifiEnabled)
            telemetry.alertReasons?.let {
                putExtra(MonitorServiceContract.EXTRA_ALERT_REASONS, it)
            }
        })
    }

    private fun Intent.putReading(prefix: String, reading: SensorReading) {
        putExtra("${prefix}_value", reading.value ?: Float.NaN)
        putExtra("${prefix}_state", reading.state.name)
    }

    private fun broadcastConfig(config: PlantConfig) {
        sendMonitorBroadcast(Intent(MonitorServiceContract.ACTION_CONFIG).apply {
            putExtra("config_soilDry", config.soilDry)
            putExtra("config_soilWet", config.soilWet)
            putExtra("config_tempScale", config.tempScale)
            putExtra("config_tempOffset", config.tempOffset)
            putExtra("config_humidityScale", config.humidityScale)
            putExtra("config_humidityOffset", config.humidityOffset)
            putExtra("config_lightScale", config.lightScale)
            putExtra("config_lightOffset", config.lightOffset)
            putExtra("config_soilLow", config.soilLow)
            putExtra("config_tempLow", config.tempLow)
            putExtra("config_tempHigh", config.tempHigh)
            putExtra("config_humidityLow", config.humidityLow)
            putExtra("config_humidityHigh", config.humidityHigh)
            putExtra("config_lightLow", config.lightLow)
            putExtra("config_lightHigh", config.lightHigh)
        })
    }

    private fun broadcastMessage(message: String) {
        sendMonitorBroadcast(
            Intent(MonitorServiceContract.ACTION_MESSAGE)
                .putExtra(MonitorServiceContract.EXTRA_MESSAGE, message)
        )
    }

    private fun sendMonitorBroadcast(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }
}
