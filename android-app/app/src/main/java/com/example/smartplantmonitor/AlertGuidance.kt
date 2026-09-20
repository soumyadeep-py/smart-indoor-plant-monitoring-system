package com.example.smartplantmonitor

import java.util.Locale

const val ALERT_REASON_SOIL = 1 shl 0
const val ALERT_REASON_TEMP_LOW = 1 shl 1
const val ALERT_REASON_TEMP_HIGH = 1 shl 2
const val ALERT_REASON_HUMIDITY_LOW = 1 shl 3
const val ALERT_REASON_HUMIDITY_HIGH = 1 shl 4
const val ALERT_REASON_LIGHT_LOW = 1 shl 5
const val ALERT_REASON_LIGHT_HIGH = 1 shl 6

data class PlantAlert(
    val sensor: String,
    val shortLabel: String,
    val message: String
)

private fun displayValue(value: Float): String =
    String.format(Locale.US, "%.1f", value)

private fun PlantTelemetry.hasReason(
    reason: Int,
    fallback: Boolean
): Boolean = alertReasons?.let { it and reason != 0 } ?: fallback

/**
 * Builds care advice from the ESP32's authoritative alert-reason mask.
 * Older firmware without that mask falls back to the limits cached by the app.
 */
fun alertIssues(
    telemetry: PlantTelemetry,
    config: PlantConfig
): List<PlantAlert> = buildList {
    telemetry.soil.value?.let { value ->
        if (telemetry.hasReason(ALERT_REASON_SOIL, value < config.soilLow)) {
            add(
                PlantAlert(
                    sensor = "soil",
                    shortLabel = "TOO LOW",
                    message = "Soil moisture is low (${displayValue(value)}%). Please water the plant slowly and recheck in a few minutes."
                )
            )
        }
    }

    telemetry.temperature.value?.let { value ->
        when {
            telemetry.hasReason(ALERT_REASON_TEMP_LOW, value < config.tempLow) -> add(
                PlantAlert(
                    sensor = "temperature",
                    shortLabel = "TOO LOW",
                    message = "Temperature is too low (${displayValue(value)}°C). Move the plant to a warmer, draft-free spot."
                )
            )
            telemetry.hasReason(ALERT_REASON_TEMP_HIGH, value > config.tempHigh) -> add(
                PlantAlert(
                    sensor = "temperature",
                    shortLabel = "TOO HIGH",
                    message = "Temperature is too high (${displayValue(value)}°C). Move the plant away from heat or harsh sun and improve airflow."
                )
            )
        }
    }

    telemetry.humidity.value?.let { value ->
        when {
            telemetry.hasReason(ALERT_REASON_HUMIDITY_LOW, value < config.humidityLow) -> add(
                PlantAlert(
                    sensor = "humidity",
                    shortLabel = "TOO LOW",
                    message = "Humidity is low (${displayValue(value)}%). Group the plant with others or use a humidifier; avoid soaking the leaves."
                )
            )
            telemetry.hasReason(ALERT_REASON_HUMIDITY_HIGH, value > config.humidityHigh) -> add(
                PlantAlert(
                    sensor = "humidity",
                    shortLabel = "TOO HIGH",
                    message = "Humidity is too high (${displayValue(value)}%). Increase airflow and let the topsoil dry to reduce fungal risk."
                )
            )
        }
    }

    telemetry.light.value?.let { value ->
        when {
            telemetry.hasReason(ALERT_REASON_LIGHT_LOW, value < config.lightLow) -> add(
                PlantAlert(
                    sensor = "light",
                    shortLabel = "TOO LOW",
                    message = "Light is too low (${displayValue(value)} lux). Move the plant closer to bright, indirect light."
                )
            )
            telemetry.hasReason(ALERT_REASON_LIGHT_HIGH, value > config.lightHigh) -> add(
                PlantAlert(
                    sensor = "light",
                    shortLabel = "TOO HIGH",
                    message = "Light is too strong (${displayValue(value)} lux). Move the plant out of harsh direct sun or use a sheer curtain."
                )
            )
        }
    }
}
