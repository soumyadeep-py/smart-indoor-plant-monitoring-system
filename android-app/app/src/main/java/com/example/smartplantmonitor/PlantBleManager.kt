package com.example.smartplantmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.UUID

data class SensorReading(
    val value: Float?,
    val state: SensorState
)

enum class SensorState(val label: String) {
    RESTING("RESTING"),
    ACTIVE("ACTIVE"),
    NOT_FOUND("NOT FOUND"),
    FAULT("FAULT"),
    UNKNOWN("UNKNOWN")
}

data class PlantTelemetry(
    val soil: SensorReading,
    val temperature: SensorReading,
    val humidity: SensorReading,
    val light: SensorReading,
    val isAlert: Boolean,
    val wifiEnabled: Boolean,
    val receivedAt: Long = System.currentTimeMillis()
)

data class PlantConfig(
    val soilDry: Float = 3000f,
    val soilWet: Float = 1500f,
    val tempScale: Float = 1f,
    val tempOffset: Float = 0f,
    val humidityScale: Float = 1f,
    val humidityOffset: Float = 0f,
    val lightScale: Float = 1f,
    val lightOffset: Float = 0f,
    val soilLow: Float = 30f,
    val tempLow: Float = 18f,
    val tempHigh: Float = 32f,
    val humidityLow: Float = 35f,
    val humidityHigh: Float = 85f,
    val lightLow: Float = 100f,
    val lightHigh: Float = 2000f
)

enum class BleConnectionState(val label: String) {
    DISCONNECTED("NOT CONNECTED"),
    SCANNING("SEARCHING"),
    CONNECTING("CONNECTING"),
    CONNECTED("CONNECTED")
}

data class PlantBleState(
    val connection: BleConnectionState = BleConnectionState.DISCONNECTED,
    val deviceName: String? = null,
    val isScanning: Boolean = false,
    val telemetry: PlantTelemetry? = null,
    val config: PlantConfig = PlantConfig(),
    val message: String? = null
)

sealed interface PlantBleEvent {
    data class State(val value: PlantBleState) : PlantBleEvent
    data class Telemetry(val value: PlantTelemetry, val alertEvent: Boolean) : PlantBleEvent
    data class Config(val value: PlantConfig) : PlantBleEvent
    data class Message(val value: String) : PlantBleEvent
}

/**
 * Small BLE client for the ESP32 protocol in code/code.ino.
 * It deliberately keeps all BLE protocol knowledge in one place so the UI
 * only has to deal with readings and settings.
 */
class PlantBleManager(
    private val context: Context,
    private val onEvent: (PlantBleEvent) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7d8d2f10-2d7f-4d82-8dd7-5a5c6e001000")
        val TELEMETRY_UUID: UUID = UUID.fromString("7d8d2f10-2d7f-4d82-8dd7-5a5c6e001001")
        val CONFIG_UUID: UUID = UUID.fromString("7d8d2f10-2d7f-4d82-8dd7-5a5c6e001002")
        val ALERT_UUID: UUID = UUID.fromString("7d8d2f10-2d7f-4d82-8dd7-5a5c6e001003")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val SCAN_TIME_MS = 12_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var telemetryCharacteristic: BluetoothGattCharacteristic? = null
    private var configCharacteristic: BluetoothGattCharacteristic? = null
    private var alertCharacteristic: BluetoothGattCharacteristic? = null
    private var latestTelemetry: PlantTelemetry? = null
    private var latestConfig = PlantConfig()
    private var descriptorsPending = ArrayDeque<BluetoothGattDescriptor>()
    private var configCommands = ArrayDeque<String>()
    private var configWriteInFlight = false
    private var lastAlertState = false

    private val scanStop = Runnable { stopScan() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            if (name == "Smart Plant Monitor") {
                stopScan()
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            emitMessage("Bluetooth search failed ($errorCode).")
            emitConnection(BleConnectionState.DISCONNECTED, false)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothGatt.STATE_DISCONNECTED) {
                    closeGatt()
                    emitMessage(if (status == BluetoothGatt.GATT_SUCCESS) "Disconnected from the plant monitor." else "Bluetooth connection failed ($status).")
                    emitConnection(BleConnectionState.DISCONNECTED, false)
                    return@post
                }
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    emitConnection(BleConnectionState.CONNECTED, false)
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitMessage("The plant monitor services could not be loaded.")
                    return@post
                }
                val service = gatt.getService(SERVICE_UUID)
                telemetryCharacteristic = service?.getCharacteristic(TELEMETRY_UUID)
                configCharacteristic = service?.getCharacteristic(CONFIG_UUID)
                alertCharacteristic = service?.getCharacteristic(ALERT_UUID)
                if (service == null || telemetryCharacteristic == null || configCharacteristic == null || alertCharacteristic == null) {
                    emitMessage("This device is not a compatible Smart Plant Monitor.")
                    return@post
                }
                descriptorsPending.clear()
                listOfNotNull(telemetryCharacteristic, configCharacteristic, alertCharacteristic).forEach { characteristic ->
                    gatt.setCharacteristicNotification(characteristic, true)
                    characteristic.getDescriptor(CCCD_UUID)?.let(descriptorsPending::add)
                }
                writeNextDescriptor()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            mainHandler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitMessage("Could not enable sensor updates.")
                    return@post
                }
                writeNextDescriptor()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristic(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristic(characteristic, value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            mainHandler.post {
                configWriteInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitMessage("The setting could not be sent to the monitor.")
                }
                writeNextConfigCommand()
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == TELEMETRY_UUID) {
                handleCharacteristic(characteristic, characteristic.value ?: byteArrayOf())
            }
        }
    }

    fun startScan() {
        if (!canScan()) {
            emitMessage("Allow Nearby devices permission before searching.")
            return
        }
        val localAdapter = adapter
        if (localAdapter == null || !localAdapter.isEnabled) {
            emitMessage("Turn on Bluetooth to find the plant monitor.")
            return
        }
        stopScan()
        scanner = localAdapter.bluetoothLeScanner
        emitConnection(BleConnectionState.SCANNING, true)
        // The ESP32 advertises its device name; filtering by name also works with
        // firmware builds that do not place the custom service UUID in the ADV packet.
        val filter = ScanFilter.Builder().setDeviceName("Smart Plant Monitor").build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        mainHandler.postDelayed(scanStop, SCAN_TIME_MS)
    }

    fun stopScan() {
        mainHandler.removeCallbacks(scanStop)
        if (canScan()) {
            scanner?.stopScan(scanCallback)
        }
        scanner = null
        if (currentConnection() == BleConnectionState.SCANNING) {
            emitConnection(BleConnectionState.DISCONNECTED, false)
        }
    }

    fun disconnect() {
        stopScan()
        if (canConnect()) {
            gatt?.disconnect()
        }
        closeGatt()
        emitConnection(BleConnectionState.DISCONNECTED, false)
    }

    fun writeConfig(key: String, value: String) {
        if (gatt == null || configCharacteristic == null) {
            emitMessage("Connect to the plant monitor before saving settings.")
            return
        }
        configCommands.addLast("$key=$value")
        writeNextConfigCommand()
    }

    fun close() {
        disconnect()
    }

    private fun connect(device: BluetoothDevice) {
        if (!canConnect()) {
            emitMessage("Allow Nearby devices permission before connecting.")
            return
        }
        closeGatt()
        emitConnection(BleConnectionState.CONNECTING, false, device.name ?: "Smart Plant Monitor")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
    }

    private fun writeNextDescriptor() {
        val localGatt = gatt ?: return
        if (descriptorsPending.isEmpty()) {
            requestAllConfig()
            return
        }
        val descriptor = descriptorsPending.removeFirst()
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (!localGatt.writeDescriptor(descriptor)) {
            emitMessage("Could not subscribe to monitor updates.")
        }
    }

    private fun requestAllConfig() {
        listOf(
            "soildry", "soilwet", "tempscale", "tempoffset", "humscale", "humoffset",
            "lightscale", "lightoffset", "soillow", "templow", "temphigh", "humlow",
            "humhigh", "lightlow", "lighthigh"
        ).forEach { key -> configCommands.addLast("get=$key") }
        writeNextConfigCommand()
    }

    private fun writeNextConfigCommand() {
        val localGatt = gatt ?: return
        val characteristic = configCharacteristic ?: return
        if (configWriteInFlight || configCommands.isEmpty()) return
        configWriteInFlight = true
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = configCommands.removeFirst().toByteArray(Charsets.UTF_8)
        if (!localGatt.writeCharacteristic(characteristic)) {
            configWriteInFlight = false
            emitMessage("The monitor rejected a settings request.")
        }
    }

    private fun handleCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        mainHandler.post {
            when (characteristic.uuid) {
                TELEMETRY_UUID, ALERT_UUID -> {
                    parseTelemetry(value)?.let { telemetry ->
                        val alertEvent = characteristic.uuid == ALERT_UUID || (!lastAlertState && telemetry.isAlert)
                        lastAlertState = telemetry.isAlert
                        latestTelemetry = telemetry
                        onEvent(PlantBleEvent.Telemetry(telemetry, alertEvent))
                    }
                }
                CONFIG_UUID -> parseConfigResponse(value.toString(Charsets.UTF_8))
            }
        }
    }

    private fun parseTelemetry(value: ByteArray): PlantTelemetry? {
        if (value.size < 20 || value[0].toInt() != 1) return null
        val flags = value[1].toInt() and 0xFF
        fun floatAt(offset: Int): Float? {
            val number = ByteBuffer.wrap(value, offset, 4).order(ByteOrder.LITTLE_ENDIAN).float
            return number.takeUnless { it.isNaN() || it.isInfinite() }
        }
        fun stateAt(shift: Int): SensorState = when ((flags ushr shift) and 0x03) {
            0 -> SensorState.RESTING
            1 -> SensorState.ACTIVE
            2 -> SensorState.NOT_FOUND
            else -> SensorState.FAULT
        }
        return PlantTelemetry(
            soil = SensorReading(floatAt(4), stateAt(2)),
            temperature = SensorReading(floatAt(8), stateAt(4)),
            humidity = SensorReading(floatAt(12), stateAt(4)),
            light = SensorReading(floatAt(16), stateAt(6)),
            isAlert = flags and 0x01 != 0,
            wifiEnabled = flags and 0x02 != 0
        )
    }

    private fun parseConfigResponse(raw: String) {
        val response = raw.trim()
        if (response.startsWith("ERR:")) {
            emitMessage("Monitor rejected the setting: ${response.removePrefix("ERR:")}.")
            return
        }
        val data = response.removePrefix("OK:")
        val separator = data.indexOf('=')
        if (separator <= 0) return
        val key = data.substring(0, separator).trim().lowercase()
        val value = data.substring(separator + 1).trim().toFloatOrNull() ?: return
        latestConfig = when (key) {
            "soildry" -> latestConfig.copy(soilDry = value)
            "soilwet" -> latestConfig.copy(soilWet = value)
            "tempscale" -> latestConfig.copy(tempScale = value)
            "tempoffset" -> latestConfig.copy(tempOffset = value)
            "humscale" -> latestConfig.copy(humidityScale = value)
            "humoffset" -> latestConfig.copy(humidityOffset = value)
            "lightscale" -> latestConfig.copy(lightScale = value)
            "lightoffset" -> latestConfig.copy(lightOffset = value)
            "soillow" -> latestConfig.copy(soilLow = value)
            "templow" -> latestConfig.copy(tempLow = value)
            "temphigh" -> latestConfig.copy(tempHigh = value)
            "humlow" -> latestConfig.copy(humidityLow = value)
            "humhigh" -> latestConfig.copy(humidityHigh = value)
            "lightlow" -> latestConfig.copy(lightLow = value)
            "lighthigh" -> latestConfig.copy(lightHigh = value)
            else -> latestConfig
        }
        onEvent(PlantBleEvent.Config(latestConfig))
    }

    private fun closeGatt() {
        configCommands.clear()
        configWriteInFlight = false
        descriptorsPending.clear()
        telemetryCharacteristic = null
        configCharacteristic = null
        alertCharacteristic = null
        gatt?.close()
        gatt = null
    }

    private fun currentConnection(): BleConnectionState = if (gatt == null) BleConnectionState.DISCONNECTED else BleConnectionState.CONNECTED

    private fun emitConnection(connection: BleConnectionState, scanning: Boolean, name: String? = null) {
        val current = PlantBleState(
            connection = connection,
            deviceName = name,
            isScanning = scanning,
            telemetry = latestTelemetry,
            config = latestConfig
        )
        onEvent(PlantBleEvent.State(current))
    }

    private fun emitMessage(message: String) = onEvent(PlantBleEvent.Message(message))

    private fun hasBluetoothPermission(permission: String): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun canScan(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasBluetoothPermission(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasBluetoothPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun canConnect(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        hasBluetoothPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasBluetoothPermission(Manifest.permission.BLUETOOTH)
    }
}
