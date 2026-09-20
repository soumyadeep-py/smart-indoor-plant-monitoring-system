#include <WiFi.h>
#include <WebServer.h>
#include <Wire.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <Adafruit_AHTX0.h>
#include <BH1750.h>
#include <Adafruit_GFX.h>
#include <Fonts/FreeSans9pt7b.h>
#include <Fonts/FreeSansBold9pt7b.h>
#include <Fonts/FreeSansBold18pt7b.h>
#include <Adafruit_SSD1306.h>
// ============================================================
//                      PIN DEFINITIONS
// ============================================================
#define SOIL_PIN        34
#define I2C_SDA        21
#define I2C_SCL        22
#define BUTTON_PIN     27
#define OLED_LEFT_PIN  32
#define OLED_RIGHT_PIN 4
#define OLED_UP_PIN    16
#define OLED_DOWN_PIN  17
#define RGB_R_PIN      25
#define RGB_G_PIN      26
#define RGB_B_PIN      33
#define BUZZER_PIN     14
// ============================================================
//                         OLED
// ============================================================
#define SCREEN_WIDTH   128
#define SCREEN_HEIGHT   64
#define OLED_RESET      -1
#define OLED_ADDRESS    0x3C
Adafruit_SSD1306 display(
  SCREEN_WIDTH,
  SCREEN_HEIGHT,
  &Wire,
  OLED_RESET
);
bool oledDetected = false;
// ============================================================
//                         SENSORS
// ============================================================
Adafruit_AHTX0 aht;
BH1750 bh1750;
bool ahtDetected  = false;
bool bhDetected   = false;
// ============================================================
//                          WEB SERVER
// ============================================================
WebServer server(80);
const char* AP_SSID     = "SmartPlant-ESP32";
const char* AP_PASSWORD = "plant1234";
bool wifiActive = false;
Preferences calibrationPreferences;
// ============================================================
//                     WIFI TIMEOUT
// ============================================================
const unsigned long WIFI_NO_CLIENT_TIMEOUT = 60000UL;
unsigned long wifiDisconnectTime = 0;
// ============================================================
//                     SENSOR STATES
// ============================================================
enum SensorID {
  SOIL_SENSOR,
  AHT_SENSOR,
  LIGHT_SENSOR
};
enum SensorState {
  SENSOR_RESTING,
  SENSOR_ACTIVE,
  SENSOR_NOT_FOUND,
  SENSOR_FAULT
};
extern SensorState soilState;
extern SensorState ahtState;
extern SensorState lightState;
// ============================================================
//                     SYSTEM MODES
// ============================================================
enum SystemMode {
  NORMAL_MODE,
  ALERT_MODE
};
enum StartupPhase {
  STARTUP_BOOT,
  STARTUP_SENSOR_CHECK,
  STARTUP_READY
};
SystemMode systemMode = NORMAL_MODE;
StartupPhase startupPhase = STARTUP_BOOT;
bool startupSensorCheckPassed = false;
unsigned long startupCheckLastAttempt = 0;
unsigned long startupWarningLastBeep = 0;
unsigned long startupResultDisplayUntil = 0;
unsigned long sensorRecoveryLastAttempt = 0;
unsigned long startupSplashStart = 0;
uint8_t startupSplashIndex = 0;
const uint8_t STARTUP_SPLASH_COUNT = 3;
const unsigned long STARTUP_SPLASH_TIME = 2000UL;
const unsigned long STARTUP_SENSOR_CHECK_INTERVAL = 1000UL;
const unsigned long STARTUP_WARNING_REPEAT_TIME = 1500UL;
const unsigned long STARTUP_RESULT_DISPLAY_TIME = 2000UL;
const unsigned long SENSOR_RECOVERY_INTERVAL = 1000UL;
// ============================================================
//                  NORMAL SENSOR CYCLING
// ============================================================
//
// One sensor:
//
// ACTIVE  = 3 seconds
// RESTING = 5 seconds
//
// Sequence:
//
// Soil    → 3s ACTIVE → 5s REST
// AHT21B  → 3s ACTIVE → 5s REST
// BH1750  → 3s ACTIVE → 5s REST
//
// Full cycle = 24 seconds
// ============================================================
const unsigned long SENSOR_ACTIVE_TIME = 3000UL;
const unsigned long SENSOR_REST_TIME   = 5000UL;
const unsigned long SENSOR_READ_INTERVAL = 1000UL;
SensorID currentSensor = SOIL_SENSOR;
unsigned long sensorCycleStart = 0;
unsigned long lastSensorRead   = 0;
// ============================================================
//                       SENSOR DATA
// ============================================================
float soilPercent = NAN;
float rawTemperature = NAN;
float rawHumidity    = NAN;
float rawLightLux    = NAN;
float temperature = NAN;
float humidity    = NAN;
float lightLux = NAN;
// ============================================================
//                     SOIL CALIBRATION
// ============================================================
//
// CHANGE THESE AFTER TESTING YOUR SENSOR.
//
// Usually:
//
// Higher ADC → drier soil
// Lower ADC  → wetter soil
//
// Measure:
// 1. Dry soil
// 2. Wet soil
//
// Then replace these values.
// ============================================================
float soilDryRaw = 3000.0;
float soilWetRaw = 1500.0;
const float SOIL_PERCENT_SCALE = 1.0;
const float SOIL_PERCENT_OFFSET = 0.0;
// ============================================================
//             OTHER SENSOR CALIBRATION SETTINGS
// ============================================================
// Use: corrected value = raw value * SCALE + OFFSET.
// Keep SCALE at 1.0 and OFFSET at 0.0 when no correction is needed.
// Compare with a trusted reference before changing these values.
//
// AHT21B temperature and humidity correction.
float tempScale = 1.0;
float tempOffset = 0.0;
float humidityScale = 1.0;
float humidityOffset = 0.0;
// BH1750 light correction.
float lightScale = 1.0;
float lightOffset = 0.0;
// ============================================================
//                       THRESHOLDS
// ============================================================
// These are example values.
// Adjust according to your plant.
//
// HYSTERESIS:
//
// ALERT threshold and CLEAR threshold are different.
//
// Example:
// Soil < 30%  → ALERT
// Soil > 35%  → clear alert
// ============================================================
// ---------------- SOIL ----------------
float SOIL_ALERT_LOW = 30.0;
float SOIL_CLEAR_LOW = 35.0;
// ---------------- TEMPERATURE ----------------
float TEMP_ALERT_HIGH = 32.0;
float TEMP_ALERT_LOW = 18.0;
float TEMP_CLEAR_HIGH = 30.0;
float TEMP_CLEAR_LOW = 20.0;
// ---------------- HUMIDITY ----------------
float HUM_ALERT_LOW  = 35.0;
float HUM_ALERT_HIGH = 85.0;
float HUM_CLEAR_LOW  = 40.0;
float HUM_CLEAR_HIGH = 80.0;
// ---------------- LIGHT ----------------
float LIGHT_ALERT_LOW = 100.0;
float LIGHT_ALERT_HIGH = 2000.0;
float LIGHT_CLEAR_LOW = 150.0;
float LIGHT_CLEAR_HIGH = 1800.0;
// ============================================================
//                        BLE APP PROTOCOL
// ============================================================
//
// The ESP32 remains a BLE peripheral while Wi-Fi is off.  The
// Android app can subscribe to the telemetry and alert
// characteristics, and write one short "key=value" setting at a
// time to the configuration characteristic.
//
// Telemetry and alert notifications are always 20 bytes, so they
// work even before the phone negotiates a larger BLE MTU:
//
//   byte 0     protocol version (2)
//   byte 1     flags: bit 0 ALERT, bit 1 Wi-Fi, bits 2-3 soil
//              state, bits 4-5 AHT state, bits 6-7 light state
//   byte 2-3   current alert reasons (uint16, little-endian)
//   byte 4-7   soil moisture percent (float, little-endian)
//   byte 8-11  temperature in C (float, little-endian)
//   byte 12-15 humidity percent (float, little-endian)
//   byte 16-19 light in lux (float, little-endian)
//
// The Android app must create the visible Android notification when
// it receives a notification on BLE_ALERT_UUID. A BLE peripheral is
// not permitted to create an Android system notification by itself.
// ============================================================
const char* BLE_DEVICE_NAME = "Smart Plant Monitor";
const char* BLE_SERVICE_UUID = "7d8d2f10-2d7f-4d82-8dd7-5a5c6e001000";
const char* BLE_TELEMETRY_UUID = "7d8d2f10-2d7f-4d82-8dd7-5a5c6e001001";
const char* BLE_CONFIG_UUID = "7d8d2f10-2d7f-4d82-8dd7-5a5c6e001002";
const char* BLE_ALERT_UUID = "7d8d2f10-2d7f-4d82-8dd7-5a5c6e001003";
BLEServer* bleServer = nullptr;
BLECharacteristic* bleTelemetryCharacteristic = nullptr;
BLECharacteristic* bleConfigCharacteristic = nullptr;
BLECharacteristic* bleAlertCharacteristic = nullptr;
bool bleClientConnected = false;
bool bleAlertPending = false;
unsigned long lastBLENotification = 0;
const unsigned long BLE_NOTIFY_INTERVAL = 2000UL;
void updateAlertClearLimits();
uint16_t currentAlertReasons();
void printCalibrationAndAlertSettings();
void updateBLEConfigurationValue(const String& value);
bool applyBLEConfiguration(const String& command, String& response);
void bleTask();
// ============================================================
//                        OLED SLIDESHOW
// ============================================================
const unsigned long OLED_SLIDE_TIME = 4000UL;
const unsigned long OLED_FRAME_TIME = 40UL;
const unsigned long OLED_TRANSITION_TIME = 500UL;
const unsigned long OLED_STATUS_REFRESH_TIME = 250UL;
const unsigned long OLED_APP_REFRESH_TIME = 500UL;
unsigned long oledLastChange = 0;
unsigned long oledLastFrame = 0;
unsigned long oledTransitionStart = 0;
unsigned long oledLastStatusRefresh = 0;
unsigned long oledLastAppRefresh = 0;
int oledPage = 0;
const int OLED_PAGE_COUNT = 6;
const int CALIBRATION_PAGE = 6;
bool oledTransitionActive = false;
bool oledTransitionFromLeft = false;
bool oledWasShowingStatusScreen = false;
bool oledAppMenuOpen = false;
const char* OLED_APP_NAMES[OLED_PAGE_COUNT] = {
  "SlideShow",
  "Soil Moisture",
  "Air Temp",
  "Air Humidity",
  "Light",
  "Wi-Fi Status"
};
const uint8_t SLIDESHOW_CONTRAST = 45;
bool slideshowRunning = true;
// ============================================================
//                    SENSOR CALIBRATION APP
// ============================================================
const unsigned long CALIBRATION_SAMPLE_INTERVAL = 1000UL;
enum CalibrationState {
  CAL_IDLE,
  CAL_SAMPLING,
  CAL_EDITING
};
CalibrationState calibrationState = CAL_IDLE;
uint8_t calibrationTarget = 0; // 0..3: soil, temperature, humidity, light
bool calibrationTargetMax = false;
float calibrationSamples[3] = {0, 0, 0};
uint8_t calibrationSampleCount = 0;
float calibrationAverage = NAN;
unsigned long calibrationLastSample = 0;
unsigned long calibrationIntroUntil = 0;
bool calibrationIntroActive() {
  return calibrationIntroUntil != 0 &&
         (long)(calibrationIntroUntil - millis()) > 0;
}
uint8_t oledOutgoingFrame[
  SCREEN_WIDTH * SCREEN_HEIGHT / 8
];
// ============================================================
//                          BUZZER
// ============================================================
bool buzzerActive = false;
unsigned long buzzerStart = 0;
unsigned long buzzerDuration = 0;
const unsigned long BUZZER_BEEP_TIME = 150UL;
// ============================================================
//                          BUTTON
// ============================================================
unsigned long lastButtonChange = 0;
unsigned long oledButtonChange[4] = {0, 0, 0, 0};
const unsigned long DEBOUNCE_TIME = 50UL;
const unsigned long OLED_MANUAL_OVERRIDE_TIME = 600000UL;
const uint8_t OLED_MIN_CONTRAST = 20;
const uint8_t OLED_MAX_CONTRAST = 255;
const float OLED_BRIGHTNESS_REFERENCE_LUX = 65535.0;
uint8_t oledContrast = 128;
unsigned long oledManualOverrideStart = 0;
bool oledManualOverrideActive = false;
void setOLEDContrast();
void updateAutomaticOLEDContrast();
// ============================================================
//                     UTILITY FUNCTIONS
// ============================================================
String sensorStateToString(SensorState state) {
  switch (state) {
    case SENSOR_ACTIVE:
      return "ACTIVE";
    case SENSOR_RESTING:
      return "RESTING";
    case SENSOR_NOT_FOUND:
      return "NOT FOUND";
    case SENSOR_FAULT:
      return "FAULT";
  }
  return "UNKNOWN";
}
// ============================================================
//                         RGB LED
// ============================================================
void setRGB(
  bool red,
  bool green,
  bool blue
) {
  digitalWrite(
    RGB_R_PIN,
    red ? LOW : HIGH
  );
  digitalWrite(
    RGB_G_PIN,
    green ? LOW : HIGH
  );
  digitalWrite(
    RGB_B_PIN,
    blue ? LOW : HIGH
  );
}
bool hasSensorProblem() {
  return
    soilState == SENSOR_NOT_FOUND ||
    soilState == SENSOR_FAULT ||
    !ahtDetected ||
    ahtState == SENSOR_NOT_FOUND ||
    ahtState == SENSOR_FAULT ||
    !bhDetected ||
    lightState == SENSOR_NOT_FOUND ||
    lightState == SENSOR_FAULT;
}
bool allSensorsHealthy() {
  bool soilOk =
    soilState != SENSOR_NOT_FOUND &&
    soilState != SENSOR_FAULT &&
    !isnan(soilPercent);
  bool ahtOk =
    ahtDetected &&
    ahtState != SENSOR_NOT_FOUND &&
    ahtState != SENSOR_FAULT &&
    !isnan(temperature) &&
    !isnan(humidity);
  bool lightOk =
    bhDetected &&
    lightState != SENSOR_NOT_FOUND &&
    lightState != SENSOR_FAULT &&
    !isnan(lightLux);
  return soilOk && ahtOk && lightOk;
}
void updateRGB() {
  // Startup validation has priority before normal operation
  if (startupPhase == STARTUP_SENSOR_CHECK) {
    if (startupSensorCheckPassed) {
      setRGB(false, true, false);
    } else {
      setRGB(false, false, true);
    }
    return;
  }
  // A connection problem takes priority over threshold alerts.
  if (hasSensorProblem()) {
    setRGB(false, false, true);
    return;
  }
  // ALERT always takes priority: red means something is wrong
  if (systemMode == ALERT_MODE) {
    setRGB(true, false, false);
    return;
  }
  // GREEN only when every required sensor is connected
  // and each one is returning a valid reading.
  if (allSensorsHealthy()) {
    setRGB(false, true, false);
    return;
  }
  // Blue means a sensor needs attention. Red remains reserved for plant alerts.
  setRGB(false, false, true);
}
void startupSensorCheckTask() {
  if (startupPhase != STARTUP_SENSOR_CHECK)
    return;
  if (
    millis() - startupCheckLastAttempt <
    STARTUP_SENSOR_CHECK_INTERVAL
  ) {
    return;
  }
  startupCheckLastAttempt = millis();
  // Retry I2C initialisation too, so a sensor plugged in during boot is found.
  sensorRecoveryLastAttempt = millis() - SENSOR_RECOVERY_INTERVAL;
  sensorRecoveryTask();
  readSoil();
  readAHT();
  readBH1750();
  bool soilOk =
    soilState == SENSOR_ACTIVE &&
    !isnan(soilPercent);
  bool ahtOk =
    ahtDetected &&
    ahtState == SENSOR_ACTIVE &&
    !isnan(temperature) &&
    !isnan(humidity);
  bool lightOk =
    bhDetected &&
    lightState == SENSOR_ACTIVE &&
    !isnan(lightLux);
  startupSensorCheckPassed =
    soilOk && ahtOk && lightOk;
  if (startupSensorCheckPassed) {
    startupResultDisplayUntil = millis() + STARTUP_RESULT_DISPLAY_TIME;
    startupPhase = STARTUP_READY;
    startupHappyBeep();
    Serial.println();
    Serial.println("All sensors OK. Starting normal monitoring.");
    return;
  }
  Serial.println();
  Serial.println("Sensor check failed. Check sensor connection.");
  if (
    millis() - startupWarningLastBeep >=
    STARTUP_WARNING_REPEAT_TIME
  ) {
    startupWarningLastBeep = millis();
    startBeep();
  }
}
// ============================================================
//                          BUZZER
// ============================================================
void startTone(int frequency, unsigned long duration) {
  tone(BUZZER_PIN, frequency);
  buzzerActive = true;
  buzzerStart = millis();
  buzzerDuration = duration;
}
void startBeep() {
  startTone(2000, BUZZER_BEEP_TIME);
}
void startButtonClick() {
  // A short, soft confirmation for every physical button press.
  startTone(1200, 35UL);
}
void updateBuzzer() {
  if (!buzzerActive)
    return;
  if (
    millis() - buzzerStart >=
    buzzerDuration
  ) {
    noTone(BUZZER_PIN);
    buzzerActive = false;
  }
}
// ============================================================
//                      STARTUP BEEP
// ============================================================
void startupBeep() {
  tone(
    BUZZER_PIN,
    1000
  );
  delay(80);
  noTone(
    BUZZER_PIN
  );
}
// A short rising three-note confirmation for a successful sensor check.
void startupHappyBeep() {
  const int notes[] = { 1600, 2100, 2800 };
  for (int index = 0; index < 3; index++) {
    tone(BUZZER_PIN, notes[index]);
    delay(90);
    noTone(BUZZER_PIN);
    delay(45);
  }
}
void showCenteredText(const char* text, int y) {
  int16_t x1, y1;
  uint16_t w, h;
  display.getTextBounds(text, 0, 0, &x1, &y1, &w, &h);
  int16_t x = (SCREEN_WIDTH - w) / 2;
  display.setCursor(x, y);
  display.println(text);
}
void showStartupSplashScreen() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  if (startupSplashIndex == 0) {
    showCenteredText("SMART PLANT", 10);
    showCenteredText("MONITORING", 24);
    showCenteredText("SYSTEM", 38);
  } else if (startupSplashIndex == 1) {
    showCenteredText("CODE BY", 10);
    showCenteredText("SOUMYADEEP SAMANTA", 28);
    startupBeep();
  } else if (startupSplashIndex == 2) {
    showCenteredText("DESIGN BY", 10);
    showCenteredText("TAMAGHNA BASU", 28);
    startupBeep();
  }
  display.display();
}
void startupSplashTask() {
  if (startupPhase != STARTUP_BOOT) {
    return;
  }
  if (startupSplashStart == 0) {
    startupSplashStart = millis();
    showStartupSplashScreen();
    return;
  }
  if (millis() - startupSplashStart >= STARTUP_SPLASH_TIME) {
    startupSplashIndex++;
    startupSplashStart = millis();
    if (startupSplashIndex >= STARTUP_SPLASH_COUNT) {
      startupSplashIndex = 0;
      startupSplashStart = 0;
      startupPhase = STARTUP_SENSOR_CHECK;
      startupSensorCheckPassed = false;
      startupCheckLastAttempt = 0;
      startupWarningLastBeep = 0;
      return;
    }
    showStartupSplashScreen();
  }
}
// ============================================================
//                       SOIL SENSOR
// ============================================================
SensorState soilState = SENSOR_RESTING;
// ------------------------------------------------------------
// Read soil sensor
// ------------------------------------------------------------
void readSoil() {
  int raw = analogRead(
    SOIL_PIN
  );
  Serial.print(
    "Soil RAW: "
  );
  Serial.println(raw);
  // ----------------------------------------------------------
  // Basic invalid-reading detection
  // ----------------------------------------------------------
  if (
    raw < 20 ||
    raw > 4080
  ) {
    soilState = SENSOR_FAULT;
    soilPercent = NAN;
    return;
  }
  // ----------------------------------------------------------
  // Valid reading
  // ----------------------------------------------------------
  soilState = SENSOR_ACTIVE;
  soilPercent =
    (raw - soilDryRaw) * 100.0 /
    (soilWetRaw - soilDryRaw);
  soilPercent = constrain(
    soilPercent * SOIL_PERCENT_SCALE + SOIL_PERCENT_OFFSET,
    0,
    100
  );
  Serial.print(
    "Soil Moisture: "
  );
  Serial.print(
    soilPercent,
    1
  );
  Serial.println("%");
}
void saveCalibrationValue(const char* key, float value) {
  calibrationPreferences.putFloat(key, value);
}
float calibrationLiveValue() {
  if (calibrationTarget == 0) return analogRead(SOIL_PIN);
  if (calibrationTarget == 1) return temperature;
  if (calibrationTarget == 2) return humidity;
  return lightLux;
}
float calibrationStep() {
  return (calibrationTarget == 1 || calibrationTarget == 2) ? 0.1 : 1.0;
}
const char* calibrationSensorName() {
  if (calibrationTarget == 0) return "SOIL";
  if (calibrationTarget == 1) return "TEMP";
  if (calibrationTarget == 2) return "HUMIDITY";
  return "LIGHT";
}
void calibrationBegin() {
  calibrationState = CAL_SAMPLING;
  calibrationSampleCount = 0;
  calibrationAverage = NAN;
  calibrationLastSample = 0;
  startTone(1700, 80);
}
void calibrationSave() {
  float value = calibrationAverage;
  if (calibrationTarget == 1) {
    value = constrain(value, -40.0f, 85.0f);
    if (calibrationTargetMax) value = max(value, TEMP_ALERT_LOW + 0.1f);
    else value = min(value, TEMP_ALERT_HIGH - 0.1f);
  } else if (calibrationTarget == 2) {
    value = constrain(value, 0.0f, 100.0f);
    if (calibrationTargetMax) value = max(value, HUM_ALERT_LOW + 0.1f);
    else value = min(value, HUM_ALERT_HIGH - 0.1f);
  } else if (calibrationTarget == 3) {
    value = max(0.0f, value);
    if (calibrationTargetMax) value = max(value, LIGHT_ALERT_LOW + 1.0f);
    else value = min(value, LIGHT_ALERT_HIGH - 1.0f);
  }
  if (calibrationTarget == 0) {
    if (calibrationTargetMax) {
      soilWetRaw = value;
      saveCalibrationValue("soilWet", soilWetRaw);
    } else {
      soilDryRaw = value;
      saveCalibrationValue("soilDry", soilDryRaw);
    }
  } else if (calibrationTarget == 1) {
    if (calibrationTargetMax) {
      TEMP_ALERT_HIGH = value;
      saveCalibrationValue("tempHigh", value);
    } else {
      TEMP_ALERT_LOW = value;
      saveCalibrationValue("tempLow", value);
    }
  } else if (calibrationTarget == 2) {
    if (calibrationTargetMax) {
      HUM_ALERT_HIGH = value;
      saveCalibrationValue("humHigh", value);
    } else {
      HUM_ALERT_LOW = value;
      saveCalibrationValue("humLow", value);
    }
  } else {
    if (calibrationTargetMax) {
      LIGHT_ALERT_HIGH = value;
      saveCalibrationValue("lightHigh", value);
    } else {
      LIGHT_ALERT_LOW = value;
      saveCalibrationValue("lightLow", value);
    }
  }
  updateAlertClearLimits();
  calibrationState = CAL_IDLE;
  startTone(2600, 120);
}
void calibrationTask() {
  if (calibrationState != CAL_SAMPLING) return;
  if (calibrationLastSample != 0 &&
      millis() - calibrationLastSample < CALIBRATION_SAMPLE_INTERVAL) return;
  float reading = calibrationLiveValue();
  if (isnan(reading)) {
    startTone(450, 100);
    return;
  }
  calibrationLastSample = millis();
  calibrationSamples[calibrationSampleCount++] = reading;
  startTone(1300, 35);
  if (calibrationSampleCount >= 3) {
    calibrationAverage =
      (calibrationSamples[0] + calibrationSamples[1] + calibrationSamples[2]) / 3.0;
    calibrationState = CAL_EDITING;
    startTone(2400, 120);
  }
}
void calibrationAdjust(int direction) {
  if (calibrationState != CAL_EDITING || isnan(calibrationAverage)) return;
  calibrationAverage += direction * calibrationStep();
  if (calibrationTarget == 0) calibrationAverage = constrain(calibrationAverage, 20.0, 4080.0);
  else if (calibrationTarget == 1) calibrationAverage = constrain(calibrationAverage, -40.0, 85.0);
  else if (calibrationTarget == 2) calibrationAverage = constrain(calibrationAverage, 0.0, 100.0);
  else calibrationAverage = max(0.0f, calibrationAverage);
}
void calibrationEnter() {
  oledPage = CALIBRATION_PAGE;
  oledAppMenuOpen = false;
  slideshowRunning = false;
  calibrationIntroUntil = millis() + 2000UL;
  calibrationState = CAL_IDLE;
  calibrationSampleCount = 0;
  calibrationAverage = NAN;
  oledTransitionActive = false;
  oledLastAppRefresh = 0;
  startTone(1800, 80);
  showOLED();
}
void calibrationExit() {
  calibrationIntroUntil = 0;
  calibrationState = CAL_IDLE;
  calibrationSampleCount = 0;
  calibrationAverage = NAN;
  oledPage = 0;
  slideshowRunning = true;
  oledLastChange = millis();
  oledTransitionActive = false;
  oledLastAppRefresh = 0;
  startTone(650, 100);
  showOLED();
}
void calibrationSelectTarget(int direction) {
  int index = calibrationTarget * 2 + (calibrationTargetMax ? 1 : 0);
  index = (index + direction + 8) % 8;
  calibrationTarget = index / 2;
  calibrationTargetMax = (index % 2) == 1;
  startTone(1050, 30);
}
// ============================================================
//                        BLE SUPPORT
// ============================================================
uint8_t sensorStateToBLE(SensorState state) {
  switch (state) {
    case SENSOR_RESTING: return 0;
    case SENSOR_ACTIVE: return 1;
    case SENSOR_NOT_FOUND: return 2;
    case SENSOR_FAULT: return 3;
  }
  return 3;
}
String bleFloat(float value, uint8_t decimals = 1) {
  if (isnan(value)) return "nan";
  return String(value, static_cast<unsigned int>(decimals));
}
void updateAlertClearLimits() {
  // Keep a small hysteresis band when app-configured alert limits
  // change, preventing rapid transitions between normal and alert.
  float tempMiddle = (TEMP_ALERT_LOW + TEMP_ALERT_HIGH) / 2.0;
  float humidityMiddle = (HUM_ALERT_LOW + HUM_ALERT_HIGH) / 2.0;
  float lightMiddle = (LIGHT_ALERT_LOW + LIGHT_ALERT_HIGH) / 2.0;
  float lightGap = max(10.0f, min(200.0f, LIGHT_ALERT_HIGH * 0.10f));
  // Recovery hysteresis: keep the alert stable, but clear it close to
  // the user-configured limits. Light keeps its existing wider band below.
  SOIL_CLEAR_LOW = min(100.0f, SOIL_ALERT_LOW + 2.0f);
  TEMP_CLEAR_LOW = min(tempMiddle, TEMP_ALERT_LOW + 0.5f);
  TEMP_CLEAR_HIGH = max(tempMiddle, TEMP_ALERT_HIGH - 0.5f);
  HUM_CLEAR_LOW = min(humidityMiddle, HUM_ALERT_LOW + 2.0f);
  HUM_CLEAR_HIGH = max(humidityMiddle, HUM_ALERT_HIGH - 2.0f);
  LIGHT_CLEAR_LOW = min(lightMiddle, LIGHT_ALERT_LOW + lightGap);
  LIGHT_CLEAR_HIGH = max(lightMiddle, LIGHT_ALERT_HIGH - lightGap);
}
void printCalibrationAndAlertSettings() {
  Serial.println("Stored calibration and alert settings:");
  Serial.print("  Soil raw (dry/wet): ");
  Serial.print(soilDryRaw, 1);
  Serial.print(" / ");
  Serial.println(soilWetRaw, 1);
  Serial.print("  Temperature scale/offset: ");
  Serial.print(tempScale, 3);
  Serial.print(" / ");
  Serial.println(tempOffset, 2);
  Serial.print("  Humidity scale/offset: ");
  Serial.print(humidityScale, 3);
  Serial.print(" / ");
  Serial.println(humidityOffset, 2);
  Serial.print("  Light scale/offset: ");
  Serial.print(lightScale, 3);
  Serial.print(" / ");
  Serial.println(lightOffset, 2);
  Serial.print("  Alert limits soil/temp/humidity/light: ");
  Serial.print(SOIL_ALERT_LOW, 1);
  Serial.print(" / ");
  Serial.print(TEMP_ALERT_LOW, 1);
  Serial.print("-");
  Serial.print(TEMP_ALERT_HIGH, 1);
  Serial.print(" / ");
  Serial.print(HUM_ALERT_LOW, 1);
  Serial.print("-");
  Serial.print(HUM_ALERT_HIGH, 1);
  Serial.print(" / ");
  Serial.print(LIGHT_ALERT_LOW, 1);
  Serial.print("-");
  Serial.println(LIGHT_ALERT_HIGH, 1);
}
bool bleConfigValueForKey(const String& key, String& response) {
  if (key == "soildry") response = "soilDry=" + bleFloat(soilDryRaw);
  else if (key == "soilwet") response = "soilWet=" + bleFloat(soilWetRaw);
  else if (key == "tempscale") response = "tempScale=" + bleFloat(tempScale, 2);
  else if (key == "tempoffset") response = "tempOffset=" + bleFloat(tempOffset, 1);
  else if (key == "humscale") response = "humScale=" + bleFloat(humidityScale, 2);
  else if (key == "humoffset") response = "humOffset=" + bleFloat(humidityOffset, 1);
  else if (key == "lightscale") response = "lightScale=" + bleFloat(lightScale, 2);
  else if (key == "lightoffset") response = "lightOffset=" + bleFloat(lightOffset, 1);
  else if (key == "soillow") response = "soilLow=" + bleFloat(SOIL_ALERT_LOW);
  else if (key == "templow") response = "tempLow=" + bleFloat(TEMP_ALERT_LOW);
  else if (key == "temphigh") response = "tempHigh=" + bleFloat(TEMP_ALERT_HIGH);
  else if (key == "humlow") response = "humLow=" + bleFloat(HUM_ALERT_LOW);
  else if (key == "humhigh") response = "humHigh=" + bleFloat(HUM_ALERT_HIGH);
  else if (key == "lightlow") response = "lightLow=" + bleFloat(LIGHT_ALERT_LOW);
  else if (key == "lighthigh") response = "lightHigh=" + bleFloat(LIGHT_ALERT_HIGH);
  else return false;
  return true;
}
bool setBLEConfigValue(const String& key, float value, String& response) {
  if (isnan(value) || isinf(value)) {
    response = "ERR:bad-number";
    return false;
  }
  if (key == "soildry") {
    if (value < 20.0 || value > 4080.0 || fabsf(value - soilWetRaw) < 10.0) {
      response = "ERR:soilDry";
      return false;
    }
    soilDryRaw = value;
    saveCalibrationValue("soilDry", value);
  } else if (key == "soilwet") {
    if (value < 20.0 || value > 4080.0 || fabsf(value - soilDryRaw) < 10.0) {
      response = "ERR:soilWet";
      return false;
    }
    soilWetRaw = value;
    saveCalibrationValue("soilWet", value);
  } else if (key == "tempscale") {
    if (value < 0.1 || value > 10.0) { response = "ERR:tempScale"; return false; }
    tempScale = value;
    saveCalibrationValue("tempScale", value);
  } else if (key == "tempoffset") {
    if (value < -50.0 || value > 50.0) { response = "ERR:tempOffset"; return false; }
    tempOffset = value;
    saveCalibrationValue("tempOffset", value);
  } else if (key == "humscale") {
    if (value < 0.1 || value > 10.0) { response = "ERR:humScale"; return false; }
    humidityScale = value;
    saveCalibrationValue("humidityScale", value);
  } else if (key == "humoffset") {
    if (value < -100.0 || value > 100.0) { response = "ERR:humOffset"; return false; }
    humidityOffset = value;
    saveCalibrationValue("humidityOffset", value);
  } else if (key == "lightscale") {
    if (value < 0.1 || value > 10.0) { response = "ERR:lightScale"; return false; }
    lightScale = value;
    saveCalibrationValue("lightScale", value);
  } else if (key == "lightoffset") {
    if (value < -1000.0 || value > 100000.0) { response = "ERR:lightOffset"; return false; }
    lightOffset = value;
    saveCalibrationValue("lightOffset", value);
  } else if (key == "soillow") {
    if (value < 0.0 || value > 100.0) { response = "ERR:soilLow"; return false; }
    SOIL_ALERT_LOW = value;
    // ESP32 Preferences/NVS keys are limited to 15 characters.
    saveCalibrationValue("soilLow", value);
  } else if (key == "templow") {
    if (value < -40.0 || value >= TEMP_ALERT_HIGH) { response = "ERR:tempLow"; return false; }
    TEMP_ALERT_LOW = value;
    saveCalibrationValue("tempLow", value);
  } else if (key == "temphigh") {
    if (value > 85.0 || value <= TEMP_ALERT_LOW) { response = "ERR:tempHigh"; return false; }
    TEMP_ALERT_HIGH = value;
    saveCalibrationValue("tempHigh", value);
  } else if (key == "humlow") {
    if (value < 0.0 || value >= HUM_ALERT_HIGH) { response = "ERR:humLow"; return false; }
    HUM_ALERT_LOW = value;
    saveCalibrationValue("humLow", value);
  } else if (key == "humhigh") {
    if (value > 100.0 || value <= HUM_ALERT_LOW) { response = "ERR:humHigh"; return false; }
    HUM_ALERT_HIGH = value;
    saveCalibrationValue("humHigh", value);
  } else if (key == "lightlow") {
    if (value < 0.0 || value >= LIGHT_ALERT_HIGH) { response = "ERR:lightLow"; return false; }
    LIGHT_ALERT_LOW = value;
    saveCalibrationValue("lightLow", value);
  } else if (key == "lighthigh") {
    if (value > 100000.0 || value <= LIGHT_ALERT_LOW) { response = "ERR:lightHigh"; return false; }
    LIGHT_ALERT_HIGH = value;
    saveCalibrationValue("lightHigh", value);
  } else {
    response = "ERR:unknown-key";
    return false;
  }
  updateAlertClearLimits();
  String currentValue;
  bleConfigValueForKey(key, currentValue);
  response = "OK:" + currentValue;
  return true;
}
bool applyBLEConfiguration(const String& command, String& response) {
  int separator = command.indexOf('=');
  if (separator <= 0 || separator == command.length() - 1) {
    response = "ERR:use-key=value";
    return false;
  }
  String key = command.substring(0, separator);
  String valueText = command.substring(separator + 1);
  key.trim();
  valueText.trim();
  key.toLowerCase();
  if (key == "get") {
    valueText.toLowerCase();
    if (!bleConfigValueForKey(valueText, response)) {
      response = "ERR:read-key";
      return false;
    }
    return true;
  }
  return setBLEConfigValue(key, valueText.toFloat(), response);
}
void updateBLEConfigurationValue(const String& value) {
  if (bleConfigCharacteristic == nullptr) return;
  bleConfigCharacteristic->setValue(value.c_str());
  if (bleClientConnected) bleConfigCharacteristic->notify();
}
void buildBLESensorPacket(uint8_t packet[20]) {
  packet[0] = 2;
  packet[1] =
    (systemMode == ALERT_MODE ? 0x01 : 0x00) |
    (wifiActive ? 0x02 : 0x00) |
    (sensorStateToBLE(soilState) << 2) |
    (sensorStateToBLE(ahtState) << 4) |
    (sensorStateToBLE(lightState) << 6);
  uint16_t alertReasons = currentAlertReasons();
  packet[2] = alertReasons & 0xFF;
  packet[3] = (alertReasons >> 8) & 0xFF;
  memcpy(packet + 4, &soilPercent, sizeof(float));
  memcpy(packet + 8, &temperature, sizeof(float));
  memcpy(packet + 12, &humidity, sizeof(float));
  memcpy(packet + 16, &lightLux, sizeof(float));
}
void publishBLESensorData() {
  if (!bleClientConnected || bleTelemetryCharacteristic == nullptr) return;
  uint8_t packet[20];
  buildBLESensorPacket(packet);
  bleTelemetryCharacteristic->setValue(packet, sizeof(packet));
  bleTelemetryCharacteristic->notify();
}
void sendBLEAlertNotification() {
  if (!bleClientConnected || bleAlertCharacteristic == nullptr) {
    bleAlertPending = true;
    return;
  }
  uint8_t packet[20];
  buildBLESensorPacket(packet);
  bleAlertCharacteristic->setValue(packet, sizeof(packet));
  bleAlertCharacteristic->notify();
  bleAlertPending = false;
  Serial.println("BLE alert notification sent to connected app.");
}
class PlantBLEServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* server) override {
    bleClientConnected = true;
    if (systemMode == ALERT_MODE) bleAlertPending = true;
    Serial.println("BLE phone connected.");
  }
  void onDisconnect(BLEServer* server) override {
    bleClientConnected = false;
    BLEDevice::startAdvertising();
    Serial.println("BLE phone disconnected; advertising restarted.");
  }
};
class PlantBLEConfigCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* characteristic) override {
    String command = String(characteristic->getValue().c_str());
    String response;
    applyBLEConfiguration(command, response);
    updateBLEConfigurationValue(response);
    Serial.print("BLE config: ");
    Serial.println(response);
  }
};
void initBLE() {
  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(247);
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new PlantBLEServerCallbacks());
  BLEService* bleService = bleServer->createService(BLE_SERVICE_UUID);
  bleTelemetryCharacteristic = bleService->createCharacteristic(
    BLE_TELEMETRY_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  bleTelemetryCharacteristic->addDescriptor(new BLE2902());
  bleConfigCharacteristic = bleService->createCharacteristic(
    BLE_CONFIG_UUID,
    BLECharacteristic::PROPERTY_READ |
      BLECharacteristic::PROPERTY_WRITE |
      BLECharacteristic::PROPERTY_NOTIFY
  );
  bleConfigCharacteristic->addDescriptor(new BLE2902());
  bleConfigCharacteristic->setCallbacks(new PlantBLEConfigCallbacks());
  updateBLEConfigurationValue("READY:key=value");
  bleAlertCharacteristic = bleService->createCharacteristic(
    BLE_ALERT_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  bleAlertCharacteristic->addDescriptor(new BLE2902());
  bleService->start();
  BLEDevice::startAdvertising();
  Serial.println("BLE ready: Smart Plant Monitor is advertising.");
}
void bleTask() {
  if (bleClientConnected && millis() - lastBLENotification >= BLE_NOTIFY_INTERVAL) {
    lastBLENotification = millis();
    publishBLESensorData();
  }
  if (bleAlertPending) sendBLEAlertNotification();
}
// ============================================================
//                        AHT21B SENSOR
// ============================================================
SensorState ahtState = SENSOR_NOT_FOUND;
bool i2cDevicePresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}
// ------------------------------------------------------------
// Read AHT21B
// ------------------------------------------------------------
void readAHT() {
  if (!ahtDetected || !i2cDevicePresent(0x38)) {
    ahtState = SENSOR_NOT_FOUND;
    ahtDetected = false;
    temperature = NAN;
    humidity = NAN;
    return;
  }
  sensors_event_t humidityEvent;
  sensors_event_t temperatureEvent;
  aht.getEvent(
    &humidityEvent,
    &temperatureEvent
  );
  rawTemperature =
    temperatureEvent.temperature;
  rawHumidity =
    humidityEvent.relative_humidity;
  temperature = rawTemperature * tempScale + tempOffset;
  humidity = rawHumidity * humidityScale + humidityOffset;
  if (
    isnan(temperature) ||
    isnan(humidity)
  ) {
    ahtState = SENSOR_FAULT;
    ahtDetected = false;
    temperature = NAN;
    humidity = NAN;
    return;
  }
  ahtState = SENSOR_ACTIVE;
  Serial.print(
    "Temperature: "
  );
  Serial.print(
    temperature,
    1
  );
  Serial.println(" C");
  Serial.print(
    "Humidity: "
  );
  Serial.print(
    humidity,
    1
  );
  Serial.println(" %");
}
// ============================================================
//                       BH1750 SENSOR
// ============================================================
SensorState lightState = SENSOR_NOT_FOUND;
// ------------------------------------------------------------
// Read BH1750
// ------------------------------------------------------------
void readBH1750() {
  if (!bhDetected || !i2cDevicePresent(0x23)) {
    lightState = SENSOR_NOT_FOUND;
    bhDetected = false;
    lightLux = NAN;
    return;
  }
  float lux =
    bh1750.readLightLevel();
  if (lux < 0) {
    lightState = SENSOR_FAULT;
    bhDetected = false;
    lightLux = NAN;
    return;
  }
  rawLightLux = lux;
  lightLux = rawLightLux * lightScale + lightOffset;
  lightState = SENSOR_ACTIVE;
  updateAutomaticOLEDContrast();
  Serial.print(
    "Light: "
  );
  Serial.print(
    lightLux,
    1
  );
  Serial.println(" lux");
}
// Re-initialise a disconnected I2C sensor and re-test a bad soil reading.
// This lets the monitor automatically return to normal as soon as it is fixed.
void sensorRecoveryTask() {
  if (millis() - sensorRecoveryLastAttempt < SENSOR_RECOVERY_INTERVAL) {
    return;
  }
  sensorRecoveryLastAttempt = millis();
  if (!ahtDetected || ahtState == SENSOR_NOT_FOUND || ahtState == SENSOR_FAULT) {
    if (aht.begin(&Wire)) {
      ahtDetected = true;
      readAHT();
    } else {
      ahtDetected = false;
      ahtState = SENSOR_NOT_FOUND;
      temperature = NAN;
      humidity = NAN;
    }
  }
  if (!bhDetected || lightState == SENSOR_NOT_FOUND || lightState == SENSOR_FAULT) {
    if (bh1750.begin(BH1750::CONTINUOUS_HIGH_RES_MODE)) {
      bhDetected = true;
      readBH1750();
    } else {
      bhDetected = false;
      lightState = SENSOR_NOT_FOUND;
      lightLux = NAN;
    }
  }
  if (soilState == SENSOR_NOT_FOUND || soilState == SENSOR_FAULT) {
    readSoil();
  }
}
// ============================================================
//                   SENSOR CYCLE MANAGEMENT
// ============================================================
void startSensor(SensorID sensor) {
  currentSensor = sensor;
  sensorCycleStart = millis();
  lastSensorRead = 0;
  if (sensor == SOIL_SENSOR) {
    if (soilState != SENSOR_FAULT)
      soilState = SENSOR_ACTIVE;
  }
  else if (sensor == AHT_SENSOR) {
    if (ahtDetected)
      ahtState = SENSOR_ACTIVE;
    else
      ahtState = SENSOR_NOT_FOUND;
  }
  else if (sensor == LIGHT_SENSOR) {
    if (bhDetected)
      lightState = SENSOR_ACTIVE;
    else
      lightState = SENSOR_NOT_FOUND;
  }
}
// ------------------------------------------------------------
// Move current sensor into RESTING state
// ------------------------------------------------------------
void setCurrentSensorResting() {
  if (currentSensor == SOIL_SENSOR) {
    if (soilState == SENSOR_ACTIVE)
      soilState = SENSOR_RESTING;
  }
  else if (currentSensor == AHT_SENSOR) {
    if (ahtState == SENSOR_ACTIVE)
      ahtState = SENSOR_RESTING;
  }
  else if (currentSensor == LIGHT_SENSOR) {
    if (lightState == SENSOR_ACTIVE)
      lightState = SENSOR_RESTING;
  }
}
// ------------------------------------------------------------
// Move to next sensor
// ------------------------------------------------------------
void advanceSensor() {
  setCurrentSensorResting();
  if (currentSensor == SOIL_SENSOR) {
    startSensor(AHT_SENSOR);
  }
  else if (currentSensor == AHT_SENSOR) {
    startSensor(LIGHT_SENSOR);
  }
  else {
    startSensor(SOIL_SENSOR);
  }
}
// ============================================================
//                    NORMAL SENSOR MODE
// ============================================================
void normalSensorTask() {
  unsigned long elapsed =
    millis() - sensorCycleStart;
  // ==========================================================
  // ACTIVE PERIOD
  // ==========================================================
  if (elapsed < SENSOR_ACTIVE_TIME) {
    if (
      lastSensorRead == 0 ||
      millis() - lastSensorRead >=
      SENSOR_READ_INTERVAL
    ) {
      lastSensorRead = millis();
      if (currentSensor == SOIL_SENSOR) {
        readSoil();
      }
      else if (currentSensor == AHT_SENSOR) {
        readAHT();
      }
      else if (currentSensor == LIGHT_SENSOR) {
        readBH1750();
      }
    }
    return;
  }
  // ==========================================================
  // REST PERIOD
  // ==========================================================
  if (
    elapsed <
    SENSOR_ACTIVE_TIME +
    SENSOR_REST_TIME
  ) {
    // Keep sensor in resting state
    if (currentSensor == SOIL_SENSOR) {
      if (soilState == SENSOR_ACTIVE)
        soilState = SENSOR_RESTING;
    }
    else if (currentSensor == AHT_SENSOR) {
      if (ahtState == SENSOR_ACTIVE)
        ahtState = SENSOR_RESTING;
    }
    else if (currentSensor == LIGHT_SENSOR) {
      if (lightState == SENSOR_ACTIVE)
        lightState = SENSOR_RESTING;
    }
    return;
  }
  // ==========================================================
  // NEXT SENSOR
  // ==========================================================
  advanceSensor();
}
// ============================================================
//                        ALERT LOGIC
// ============================================================
// Each alert cause is latched independently. This prevents an unrelated
// sensor that is merely close to its limit from keeping the whole plant in
// ALERT_MODE after the sensor that actually triggered the alert recovers.
const uint16_t ALERT_REASON_SOIL = 1 << 0;
const uint16_t ALERT_REASON_TEMP_LOW = 1 << 1;
const uint16_t ALERT_REASON_TEMP_HIGH = 1 << 2;
const uint16_t ALERT_REASON_HUM_LOW = 1 << 3;
const uint16_t ALERT_REASON_HUM_HIGH = 1 << 4;
const uint16_t ALERT_REASON_LIGHT_LOW = 1 << 5;
const uint16_t ALERT_REASON_LIGHT_HIGH = 1 << 6;
uint16_t activeAlertReasons = 0;

uint16_t currentAlertReasons() {
  uint16_t reasons = 0;
  if (!isnan(soilPercent) && soilPercent < SOIL_ALERT_LOW) {
    reasons |= ALERT_REASON_SOIL;
  }
  if (!isnan(temperature)) {
    if (temperature < TEMP_ALERT_LOW) reasons |= ALERT_REASON_TEMP_LOW;
    if (temperature > TEMP_ALERT_HIGH) reasons |= ALERT_REASON_TEMP_HIGH;
  }
  if (!isnan(humidity)) {
    if (humidity < HUM_ALERT_LOW) reasons |= ALERT_REASON_HUM_LOW;
    if (humidity > HUM_ALERT_HIGH) reasons |= ALERT_REASON_HUM_HIGH;
  }
  if (!isnan(lightLux)) {
    if (lightLux < LIGHT_ALERT_LOW) reasons |= ALERT_REASON_LIGHT_LOW;
    if (lightLux > LIGHT_ALERT_HIGH) reasons |= ALERT_REASON_LIGHT_HIGH;
  }
  return reasons;
}

// ------------------------------------------------------------
// Has any threshold been exceeded?
// ------------------------------------------------------------
bool alertConditionDetected() {
  return currentAlertReasons() != 0;
}
// ------------------------------------------------------------
// HYSTERESIS CLEAR CONDITION
// ------------------------------------------------------------
//
// Alert isn't cleared immediately at the same threshold.
//
// Example:
//
// Soil <30%       → ALERT
// Soil >32%       → safe again
//
// This prevents rapid ON/OFF switching.
// ------------------------------------------------------------
bool alertConditionCleared() {
  if (activeAlertReasons == 0) return currentAlertReasons() == 0;

  if (activeAlertReasons & ALERT_REASON_SOIL) {
    if (isnan(soilPercent) || soilPercent < SOIL_CLEAR_LOW) return false;
  }
  if (activeAlertReasons & ALERT_REASON_TEMP_LOW) {
    if (isnan(temperature) || temperature < TEMP_CLEAR_LOW) return false;
  }
  if (activeAlertReasons & ALERT_REASON_TEMP_HIGH) {
    if (isnan(temperature) || temperature > TEMP_CLEAR_HIGH) return false;
  }
  if (activeAlertReasons & ALERT_REASON_HUM_LOW) {
    if (isnan(humidity) || humidity < HUM_CLEAR_LOW) return false;
  }
  if (activeAlertReasons & ALERT_REASON_HUM_HIGH) {
    if (isnan(humidity) || humidity > HUM_CLEAR_HIGH) return false;
  }
  if (activeAlertReasons & ALERT_REASON_LIGHT_LOW) {
    if (isnan(lightLux) || lightLux < LIGHT_CLEAR_LOW) return false;
  }
  if (activeAlertReasons & ALERT_REASON_LIGHT_HIGH) {
    if (isnan(lightLux) || lightLux > LIGHT_CLEAR_HIGH) return false;
  }
  return currentAlertReasons() == 0;
}
// ============================================================
//                      ENTER ALERT MODE
// ============================================================
void enterAlertMode() {
  activeAlertReasons |= currentAlertReasons();
  if (
    systemMode ==
    ALERT_MODE
  ) {
    return;
  }
  systemMode =
    ALERT_MODE;
  Serial.println();
  Serial.println(
    "================================"
  );
  Serial.println(
    "       ALERT MODE ACTIVE"
  );
  Serial.println(
    "================================"
  );
  setRGB(
    true,
    false,
    false
  );
  startBeep();
  bleAlertPending = true;
}
// ============================================================
//                       EXIT ALERT MODE
// ============================================================
void exitAlertMode() {
  if (
    systemMode ==
    NORMAL_MODE
  ) {
    return;
  }
  systemMode =
    NORMAL_MODE;
  activeAlertReasons = 0;
  Serial.println();
  Serial.println(
    "Returning to NORMAL MODE"
  );
  updateRGB();
}
// ============================================================
//                   ALERT SENSOR POLLING
// ============================================================
unsigned long lastAlertRead = 0;
const unsigned long ALERT_READ_INTERVAL = 1000UL;
void alertSensorTask() {
  if (
    millis() - lastAlertRead <
    ALERT_READ_INTERVAL
  ) {
    return;
  }
  lastAlertRead = millis();
  // ==========================================================
  // In alert mode, all sensors are monitored
  // ==========================================================
  readSoil();
  readAHT();
  readBH1750();
  // ==========================================================
  // Keep available sensors marked ACTIVE
  // ==========================================================
  if (soilState == SENSOR_RESTING)
    soilState = SENSOR_ACTIVE;
  if (ahtDetected &&
      ahtState == SENSOR_RESTING)
    ahtState = SENSOR_ACTIVE;
  if (bhDetected &&
      lightState == SENSOR_RESTING)
    lightState = SENSOR_ACTIVE;
  // ==========================================================
  // Check conditions
  // ==========================================================
  if (
    alertConditionDetected()
  ) {
    enterAlertMode();
  }
  else if (
    alertConditionCleared()
  ) {
    exitAlertMode();
  }
}
// ============================================================
//                       WIFI START
// ============================================================
void startWiFiAP() {
  if (wifiActive)
    return;
  Serial.println();
  Serial.println(
    "Starting Wi-Fi Access Point..."
  );
  WiFi.mode(WIFI_AP);
  bool result =
    WiFi.softAP(
      AP_SSID,
      AP_PASSWORD
    );
  if (!result) {
    Serial.println(
      "ERROR: Failed to start Wi-Fi AP"
    );
    return;
  }
  delay(200);
  IPAddress ip =
    WiFi.softAPIP();
  Serial.println();
  Serial.println(
    "Wi-Fi AP started"
  );
  Serial.print(
    "SSID: "
  );
  Serial.println(
    AP_SSID
  );
  Serial.print(
    "Password: "
  );
  Serial.println(
    AP_PASSWORD
  );
  Serial.print(
    "IP address: "
  );
  Serial.println(
    ip
  );
  wifiActive = true;
  wifiDisconnectTime = 0;
  // Start web server
  server.begin();
  Serial.println(
    "Web server started"
  );
  updateRGB();
}
// ============================================================
//                       WIFI STOP
// ============================================================
void stopWiFiAP() {
  if (!wifiActive)
    return;
  Serial.println();
  Serial.println(
    "Stopping Wi-Fi..."
  );
  WiFi.softAPdisconnect(
    true
  );
  WiFi.mode(
    WIFI_OFF
  );
  wifiActive = false;
  wifiDisconnectTime = 0;
  Serial.println(
    "Wi-Fi OFF"
  );
  updateRGB();
}
// ============================================================
//                         BUTTON
// ============================================================
//
// GPIO 27:
//
// Not pressed → HIGH
// Pressed       → LOW
//
// Button connection:
//
// GPIO 27 ---- BUTTON ---- GND
// GPIO 32 ---- OLED LEFT BUTTON ---- GND
// GPIO 4  ---- OLED RIGHT BUTTON --- GND
// GPIO 16 ---- OLED UP BUTTON ------ GND
// GPIO 17 ---- OLED DOWN BUTTON ---- GND
// ============================================================
void checkButton() {
  static bool lastReading =
    HIGH;
  static bool stableState =
    HIGH;
  bool reading =
    digitalRead(
      BUTTON_PIN
    );
  // Detect change
  if (
    reading !=
    lastReading
  ) {
    lastButtonChange =
      millis();
    lastReading =
      reading;
  }
  // Debounce
  if (
    millis() -
    lastButtonChange >=
    DEBOUNCE_TIME
  ) {
    if (
      reading !=
      stableState
    ) {
      stableState =
        reading;
      // Button pressed
      if (
        stableState ==
        LOW
      ) {
        Serial.println(
          "BUTTON PRESSED"
        );
        if (oledPage == CALIBRATION_PAGE && calibrationIntroActive()) {
          return;
        }
        startButtonClick();
        if (startupPhase != STARTUP_READY || hasSensorProblem()) {
          return;
        }
        if (oledPage == 0) {
          slideshowRunning = !slideshowRunning;
          oledLastChange = millis();
        } else if (oledPage == 5) {
          if (wifiActive) {
            stopWiFiAP();
          } else {
            startWiFiAP();
          }
        } else if (oledPage == CALIBRATION_PAGE) {
          if (calibrationState == CAL_IDLE) calibrationBegin();
          else if (calibrationState == CAL_EDITING) calibrationSave();
        }
        oledLastAppRefresh = 0;
        showOLED();
      }
    }
  }
}
void changeOLEDPage(int direction) {
  unsigned long now = millis();
  oledLastChange = now;
  slideshowRunning = false;
  if (oledDetected) {
    memcpy(
      oledOutgoingFrame,
      display.getBuffer(),
      sizeof(oledOutgoingFrame)
    );
  }
  oledPage += direction;
  if (oledPage >= OLED_PAGE_COUNT) {
    oledPage = 0;
  }
  if (oledPage < 0) {
    oledPage = OLED_PAGE_COUNT - 1;
  }
  // The launcher has its own selection highlight, so it does not need
  // a page-transition animation.
  if (oledAppMenuOpen) {
    oledTransitionActive = false;
    return;
  }
  oledTransitionStart = now;
  oledTransitionFromLeft = direction < 0;
  oledTransitionActive = true;
}
void setOLEDContrast() {
  if (!oledDetected) {
    return;
  }
  display.ssd1306_command(SSD1306_SETCONTRAST);
  display.ssd1306_command(oledContrast);
}
void updateAutomaticOLEDContrast() {
  if (oledManualOverrideActive) {
    if (
      millis() - oledManualOverrideStart <
      OLED_MANUAL_OVERRIDE_TIME
    ) {
      return;
    }
    oledManualOverrideActive = false;
  }
  float brightnessPercent =
    constrain(
      lightLux / OLED_BRIGHTNESS_REFERENCE_LUX * 100.0,
      0.0,
      100.0
    );
  oledContrast =
    (uint8_t)map(
      (long)brightnessPercent,
      0,
      100,
      OLED_MIN_CONTRAST,
      OLED_MAX_CONTRAST
    );
  setOLEDContrast();
}
void startOLEDManualOverride() {
  oledManualOverrideActive = true;
  oledManualOverrideStart = millis();
}
void checkOLEDButton() {
  const int buttonPins[4] = {
    OLED_LEFT_PIN,
    OLED_RIGHT_PIN,
    OLED_UP_PIN,
    OLED_DOWN_PIN
  };
  static bool lastReading[4] = {HIGH, HIGH, HIGH, HIGH};
  static bool stableState[4] = {HIGH, HIGH, HIGH, HIGH};
  static bool calibrationShortcutHeld = false;
  bool bothCalibrationButtonsPressed =
    digitalRead(OLED_UP_PIN) == LOW &&
    digitalRead(OLED_DOWN_PIN) == LOW;
  if (bothCalibrationButtonsPressed) {
    if (!calibrationShortcutHeld) {
      calibrationShortcutHeld = true;
      if (oledPage == CALIBRATION_PAGE) calibrationExit();
      else calibrationEnter();
    }
    return;
  }
  calibrationShortcutHeld = false;
  for (int index = 0; index < 4; index++) {
    bool reading = digitalRead(buttonPins[index]);
    if (reading != lastReading[index]) {
      oledButtonChange[index] = millis();
      lastReading[index] = reading;
    }
    if (
      millis() - oledButtonChange[index] >=
      DEBOUNCE_TIME &&
      reading != stableState[index]
    ) {
      stableState[index] = reading;
      if (stableState[index] == LOW) {
        if (oledPage == CALIBRATION_PAGE && calibrationIntroActive()) {
          continue;
        }
        startButtonClick();
        if (index == 0) {
          if (oledPage != CALIBRATION_PAGE) changeOLEDPage(-1);
        } else if (index == 1) {
          if (oledPage != CALIBRATION_PAGE) changeOLEDPage(1);
        } else if (index == 2) {
          if (oledPage == CALIBRATION_PAGE) {
            if (calibrationState == CAL_IDLE) calibrationSelectTarget(1);
            else calibrationAdjust(1);
          } else {
            oledContrast = min((int)OLED_MAX_CONTRAST, (int)oledContrast + 40);
            startOLEDManualOverride();
            setOLEDContrast();
          }
        } else if (index == 3) {
          if (oledPage == CALIBRATION_PAGE) {
            if (calibrationState == CAL_IDLE) calibrationSelectTarget(-1);
            else calibrationAdjust(-1);
          } else {
            oledContrast = max((int)OLED_MIN_CONTRAST, (int)oledContrast - 40);
            startOLEDManualOverride();
            setOLEDContrast();
          }
        }
        Serial.println("OLED BUTTON PRESSED");
      }
    }
  }
}
// ============================================================
//                     JSON HELPER
// ============================================================
String jsonFloat(
  float value,
  int decimals
) {
  if (isnan(value))
    return "null";
  return String(
    value,
    decimals
  );
}
// ============================================================
//                         WEB API
// ============================================================
void handleAPI() {
  String json = "{";
  // ----------------------------------------------------------
  // System
  // ----------------------------------------------------------
  json += "\"system\":\"";
  if (
    systemMode ==
    ALERT_MODE
  ) {
    json += "ALERT";
  } else {
    json += "NORMAL";
  }
  json += "\",";
  // ----------------------------------------------------------
  // Wi-Fi
  // ----------------------------------------------------------
  json += "\"wifi\":";
  json +=
    wifiActive
    ? "true"
    : "false";
  json += ",";
  // ----------------------------------------------------------
  // Soil
  // ----------------------------------------------------------
  json += "\"soil\":{";
  json += "\"value\":";
  json +=
    jsonFloat(
      soilPercent,
      1
    );
  json += ",";
  json += "\"state\":\"";
  json +=
    sensorStateToString(
      soilState
    );
  json += "\",";
  json += "\"alert\":";
  json +=
    (!isnan(soilPercent) && soilPercent < SOIL_ALERT_LOW)
    ? "true"
    : "false";
  json += "},";
  // ----------------------------------------------------------
  // Temperature
  // ----------------------------------------------------------
  json += "\"temperature\":{";
  json += "\"value\":";
  json +=
    jsonFloat(
      temperature,
      1
    );
  json += ",";
  json += "\"state\":\"";
  json +=
    sensorStateToString(
      ahtState
    );
  json += "\",";
  json += "\"alert\":";
  json +=
    (!isnan(temperature) &&
     (temperature < TEMP_ALERT_LOW || temperature > TEMP_ALERT_HIGH))
    ? "true"
    : "false";
  json += "},";
  // ----------------------------------------------------------
  // Humidity
  // ----------------------------------------------------------
  json += "\"humidity\":{";
  json += "\"value\":";
  json +=
    jsonFloat(
      humidity,
      1
    );
  json += ",";
  json += "\"state\":\"";
  json +=
    sensorStateToString(
      ahtState
    );
  json += "\",";
  json += "\"alert\":";
  json +=
    (!isnan(humidity) &&
     (humidity < HUM_ALERT_LOW || humidity > HUM_ALERT_HIGH))
    ? "true"
    : "false";
  json += "},";
  // ----------------------------------------------------------
  // Light
  // ----------------------------------------------------------
  json += "\"light\":{";
  json += "\"value\":";
  json +=
    jsonFloat(
      lightLux,
      1
    );
  json += ",";
  json += "\"state\":\"";
  json +=
    sensorStateToString(
      lightState
    );
  json += "\",";
  json += "\"alert\":";
  json +=
    (!isnan(lightLux) &&
     (lightLux < LIGHT_ALERT_LOW || lightLux > LIGHT_ALERT_HIGH))
    ? "true"
    : "false";
  json += "}";
  // ----------------------------------------------------------
  // End
  // ----------------------------------------------------------
  json += "}";
  server.send(
    200,
    "application/json",
    json
  );
}
// ============================================================
//                    SERVE INDEX.HTML
// ============================================================
void handleRoot() {
  File file =
    LittleFS.open(
      "/index.html",
      "r"
    );
  if (!file) {
    server.send(
      404,
      "text/plain",
      "index.html not found"
    );
    return;
  }
  server.streamFile(
    file,
    "text/html"
  );
  file.close();
}
// ============================================================
//                    SERVE STYLE.CSS
// ============================================================
void handleCSS() {
  File file =
    LittleFS.open(
      "/style.css",
      "r"
    );
  if (!file) {
    server.send(
      404,
      "text/plain",
      "style.css not found"
    );
    return;
  }
  server.streamFile(
    file,
    "text/css"
  );
  file.close();
}
// ============================================================
//                    SERVE SCRIPT.JS
// ============================================================
void handleJS() {
  File file =
    LittleFS.open(
      "/script.js",
      "r"
    );
  if (!file) {
    server.send(
      404,
      "text/plain",
      "script.js not found"
    );
    return;
  }
  server.streamFile(
    file,
    "application/javascript"
  );
  file.close();
}
// ============================================================
//                    SERVER NOT FOUND
// ============================================================
void handleNotFound() {
  server.send(
    404,
    "text/plain",
    "404 - Not Found"
  );
}
// ============================================================
//                    SETUP SERVER ROUTES
// ============================================================
//
// Routes are registered ONLY ONCE.
// Wi-Fi can be switched OFF/ON without
// registering the routes again.
// ============================================================
void setupServerRoutes() {
  server.on(
    "/",
    HTTP_GET,
    handleRoot
  );
  server.on(
    "/style.css",
    HTTP_GET,
    handleCSS
  );
  server.on(
    "/script.js",
    HTTP_GET,
    handleJS
  );
  server.on(
    "/api",
    HTTP_GET,
    handleAPI
  );
  server.onNotFound(
    handleNotFound
  );
}
// ============================================================
//                        WIFI TASK
// ============================================================
//
// Behavior:
//
// Boot
//  ↓
// Wi-Fi ON
//  ↓
// Device connected?
//  ↓
// YES → keep Wi-Fi ON
// NO  → start 1 minute timer
//  ↓
// 1 minute without device
//  ↓
// Wi-Fi OFF
//
// Button can wake Wi-Fi again.
// ============================================================
void wifiTask() {
  if (!wifiActive)
    return;
  server.handleClient();
}
void advanceSlideshowPage() {
  unsigned long now = millis();
  if (oledDetected) {
    memcpy(
      oledOutgoingFrame,
      display.getBuffer(),
      sizeof(oledOutgoingFrame)
    );
  }
  oledPage = (oledPage + 1) % OLED_PAGE_COUNT;
  oledLastChange = now;
  oledTransitionStart = now;
  oledTransitionFromLeft = false;
  oledTransitionActive = true;
}
// ============================================================
//                        OLED HEADER
// ============================================================
void oledHeader(
  const char* title
) {
  display.clearDisplay();
  display.setTextColor(
    SSD1306_WHITE
  );
  display.setTextSize(1);
  display.setCursor(
    0,
    0
  );
  display.println(
    title
  );
  display.drawLine(
    0,
    10,
    127,
    10,
    SSD1306_WHITE
  );
}
// ============================================================
//                       OLED DISPLAY
// ============================================================
void showLegacyOLED() {
  if (!oledDetected)
    return;
  oledHeader(
    "SMART PLANT"
  );
  display.setCursor(
    0,
    17
  );
  // ----------------------------------------------------------
  // PAGE 0 - SYSTEM
  // ----------------------------------------------------------
  if (oledPage == 0) {
    display.println(
      "System:"
    );
    display.setTextSize(2);
    if (
      systemMode ==
      ALERT_MODE
    ) {
      display.println(
        "ALERT"
      );
    } else {
      display.println(
        "NORMAL"
      );
    }
    display.setTextSize(1);
  }
  // ----------------------------------------------------------
  // PAGE 1 - SOIL
  // ----------------------------------------------------------
  else if (oledPage == 1) {
    display.println(
      "Soil Moisture"
    );
    if (
      soilState ==
      SENSOR_NOT_FOUND
    ) {
      display.setTextSize(2);
      display.println(
        "N/A"
      );
      display.setTextSize(1);
      display.println(
        "NOT FOUND"
      );
    }
    else if (
      soilState ==
      SENSOR_FAULT
    ) {
      display.setTextSize(2);
      display.println(
        "ERROR"
      );
      display.setTextSize(1);
      display.println(
        "FAULT"
      );
    }
    else {
      display.setTextSize(2);
      display.print(
        soilPercent,
        1
      );
      display.println(
        "%"
      );
      display.setTextSize(1);
      display.println(
        sensorStateToString(
          soilState
        )
      );
    }
  }
  // ----------------------------------------------------------
  // PAGE 2 - TEMPERATURE
  // ----------------------------------------------------------
  else if (oledPage == 2) {
    display.println(
      "Temperature"
    );
    if (
      ahtState ==
      SENSOR_NOT_FOUND
    ) {
      display.setTextSize(2);
      display.println(
        "N/A"
      );
      display.setTextSize(1);
      display.println(
        "NOT FOUND"
      );
    }
    else if (
      ahtState ==
      SENSOR_FAULT
    ) {
      display.setTextSize(2);
      display.println(
        "ERROR"
      );
      display.setTextSize(1);
      display.println(
        "FAULT"
      );
    }
    else {
      display.setTextSize(2);
      display.print(
        temperature,
        1
      );
      display.println(
        " C"
      );
      display.setTextSize(1);
      display.println(
        sensorStateToString(
          ahtState
        )
      );
    }
  }
  // ----------------------------------------------------------
  // PAGE 3 - HUMIDITY
  // ----------------------------------------------------------
  else if (oledPage == 3) {
    display.println(
      "Humidity"
    );
    if (
      ahtState ==
      SENSOR_NOT_FOUND
    ) {
      display.setTextSize(2);
      display.println(
        "N/A"
      );
      display.setTextSize(1);
      display.println(
        "NOT FOUND"
      );
    }
    else if (
      ahtState ==
      SENSOR_FAULT
    ) {
      display.setTextSize(2);
      display.println(
        "ERROR"
      );
      display.setTextSize(1);
      display.println(
        "FAULT"
      );
    }
    else {
      display.setTextSize(2);
      display.print(
        humidity,
        1
      );
      display.println(
        "%"
      );
      display.setTextSize(1);
      display.println(
        sensorStateToString(
          ahtState
        )
      );
    }
  }
  // ----------------------------------------------------------
  // PAGE 4 - LIGHT
  // ----------------------------------------------------------
  else if (oledPage == 4) {
    display.println(
      "Light"
    );
    if (
      lightState ==
      SENSOR_NOT_FOUND
    ) {
      display.setTextSize(2);
      display.println(
        "N/A"
      );
      display.setTextSize(1);
      display.println(
        "NOT FOUND"
      );
    }
    else if (
      lightState ==
      SENSOR_FAULT
    ) {
      display.setTextSize(2);
      display.println(
        "ERROR"
      );
      display.setTextSize(1);
      display.println(
        "FAULT"
      );
    }
    else {
      display.setTextSize(2);
      display.print(
        lightLux,
        0
      );
      display.println(
        " lux"
      );
      display.setTextSize(1);
      display.println(
        sensorStateToString(
          lightState
        )
      );
    }
  }
  // ----------------------------------------------------------
  // PAGE 5 - WEBSITE
  // ----------------------------------------------------------
  else if (oledPage == 5) {
    display.println(
      "Web Dashboard"
    );
    display.setTextSize(2);
    if (wifiActive) {
      display.println(
        "ONLINE"
      );
    } else {
      display.println(
        "OFF"
      );
    }
    display.setTextSize(1);
    display.println();
    if (wifiActive) {
      display.println(
        "192.168.4.1"
      );
    } else {
      display.println(
        "Press button"
      );
    }
  }
  if (oledTransitionActive) {
    unsigned long transitionElapsed =
      millis() - oledTransitionStart;
    if (transitionElapsed >= OLED_TRANSITION_TIME) {
      oledTransitionActive = false;
    } else {
      float transitionProgress =
        (float)transitionElapsed /
        OLED_TRANSITION_TIME;
      float easedProgress =
        1.0 -
        (
          (1.0 - transitionProgress) *
          (1.0 - transitionProgress) *
          (1.0 - transitionProgress)
        );
      int revealedHeight =
        (int)(easedProgress * SCREEN_HEIGHT);
      display.fillRect(
        0,
        revealedHeight,
        SCREEN_WIDTH,
        SCREEN_HEIGHT - revealedHeight,
        SSD1306_BLACK
      );
    }
  }
  display.display();
}
void oledCenteredTextInArea(
  const String& text,
  int y,
  int textSize,
  int left,
  int right
) {
  int16_t x1;
  int16_t y1;
  uint16_t width;
  uint16_t height;
  bool usingLargeFont = textSize >= 2;
  // Keep compact status text in the original bitmap font. Larger readings
  // use a clean sans-serif font so the values are easier to scan at a glance.
  if (usingLargeFont) {
    display.setFont(&FreeSansBold9pt7b);
    display.setTextSize(1);
  } else {
    display.setFont();
    display.setTextSize(1);
  }
  display.getTextBounds(
    text,
    0,
    0,
    &x1,
    &y1,
    &width,
    &height
  );
  display.setCursor(
    (left + right - width) / 2 - x1,
    usingLargeFont ? y - y1 : y
  );
  display.print(text);
  // Do not let the custom font leak into the next small status label.
  display.setFont();
  display.setTextSize(1);
}
void oledCenteredText(
  const String& text,
  int y,
  int textSize
) {
  oledCenteredTextInArea(text, y, textSize, 0, SCREEN_WIDTH);
}
void oledCenteredFontTextInArea(
  const String& text,
  int y,
  const GFXfont* font,
  int left,
  int right
) {
  int16_t x1;
  int16_t y1;
  uint16_t width;
  uint16_t height;
  display.setFont(font);
  display.setTextSize(1);
  display.getTextBounds(text, 0, 0, &x1, &y1, &width, &height);
  display.setCursor(
    (left + right - width) / 2 - x1,
    y - y1
  );
  display.print(text);
  display.setFont();
  display.setTextSize(1);
}
void oledSensorFooter(
  SensorState state
) {
  display.setTextSize(1);
  if (state == SENSOR_NOT_FOUND) {
    oledCenteredText("SENSOR NOT FOUND", 47, 1);
  }
  else if (state == SENSOR_FAULT) {
    oledCenteredText("SENSOR FAULT", 47, 1);
  }
  else {
    oledCenteredText(
      sensorStateToString(state),
      47,
      1
    );
  }
}
void oledProgressBar(
  float value,
  float maximum
) {
  const int barX = 12;
  const int barY = 43;
  const int barWidth = 104;
  const int barHeight = 6;
  display.drawRoundRect(
    barX,
    barY,
    barWidth,
    barHeight,
    2,
    SSD1306_WHITE
  );
  if (isnan(value) || value <= 0.0) {
    return;
  }
  float limitedValue = value;
  if (limitedValue > maximum) {
    limitedValue = maximum;
  }
  int fillWidth =
    (int)(
      (limitedValue / maximum) *
      (barWidth - 4)
    );
  if (fillWidth > 0) {
    display.fillRoundRect(
      barX + 2,
      barY + 2,
      fillWidth,
      barHeight - 4,
      1,
      SSD1306_WHITE
    );
  }
}
void oledPageHeader() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(3, 1);
  display.print("PLANT MONITOR");
  display.setCursor(109, 1);
  display.print(oledPage + 1);
  display.print("/");
  display.print(OLED_PAGE_COUNT);
  display.drawLine(
    0,
    11,
    SCREEN_WIDTH - 1,
    11,
    SSD1306_WHITE
  );
}
void oledPageDots() {
  const int dotsWidth = OLED_PAGE_COUNT * 6 - 1;
  const int startX = (SCREEN_WIDTH - dotsWidth) / 2;
  const int dotY = 62;
  for (int index = 0; index < OLED_PAGE_COUNT; index++) {
    if (index == oledPage) {
      display.fillRect(
        startX + index * 6,
        dotY,
        5,
        2,
        SSD1306_WHITE
      );
    } else {
      display.drawPixel(
        startX + index * 6 + 2,
        dotY,
        SSD1306_WHITE
      );
    }
  }
}
void oledSensorCheckScreen(bool allFound) {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(3, 1);
  display.print("SMART PLANT");
  display.drawLine(0, 11, SCREEN_WIDTH - 1, 11, SSD1306_WHITE);
  oledCenteredText(allFound ? "ALL SENSORS FOUND" : "CHECK SENSOR", 16, 1);
  display.drawRoundRect(1, 27, SCREEN_WIDTH - 2, 31, 3, SSD1306_WHITE);
  display.setCursor(8, 31);
  display.print(soilState == SENSOR_ACTIVE || soilState == SENSOR_RESTING ? "+ SOIL" : "! SOIL MISSING");
  display.setCursor(8, 40);
  display.print(ahtDetected && ahtState != SENSOR_FAULT ? "+ AHT21B" : "! AHT21B MISSING");
  display.setCursor(8, 49);
  display.print(bhDetected && lightState != SENSOR_FAULT ? "+ BH1750" : "! BH1750 MISSING");
  display.display();
}
void showOLEDAppMenu() {
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  display.setCursor(3, 1);
  display.print("PLANT APPS");
  display.drawLine(0, 11, SCREEN_WIDTH - 1, 11, SSD1306_WHITE);
  for (int index = 0; index < OLED_PAGE_COUNT; index++) {
    int y = 14 + index * 8;
    bool selected = index == oledPage;
    if (selected) {
      display.fillRoundRect(2, y - 1, 124, 9, 2, SSD1306_WHITE);
      display.setTextColor(SSD1306_BLACK);
    }
    display.setCursor(6, y);
    display.print(selected ? "> " : "  ");
    display.print(index + 1);
    display.print(". ");
    display.print(OLED_APP_NAMES[index]);
    display.setTextColor(SSD1306_WHITE);
  }
  display.display();
}
void oledAppIcon(int app, int x, int y) {
  if (app == 0) {                 // Play / slideshow
    display.fillTriangle(x, y, x, y + 10, x + 9, y + 5, SSD1306_WHITE);
  } else if (app == 1) {          // Water drop
    display.drawTriangle(x + 5, y, x, y + 8, x + 10, y + 8, SSD1306_WHITE);
    display.drawCircle(x + 5, y + 8, 4, SSD1306_WHITE);
  } else if (app == 2) {          // Thermometer
    display.drawCircle(x + 4, y + 8, 3, SSD1306_WHITE);
    display.drawLine(x + 4, y + 1, x + 4, y + 8, SSD1306_WHITE);
    display.drawLine(x + 6, y + 1, x + 6, y + 8, SSD1306_WHITE);
  } else if (app == 3) {          // Humidity waves
    display.drawCircle(x + 3, y + 4, 2, SSD1306_WHITE);
    display.drawCircle(x + 7, y + 7, 2, SSD1306_WHITE);
    display.drawLine(x, y + 10, x + 10, y + 10, SSD1306_WHITE);
  } else if (app == 4) {          // Light bulb / sun
    display.drawCircle(x + 5, y + 5, 3, SSD1306_WHITE);
    display.drawPixel(x + 5, y, SSD1306_WHITE);
    display.drawPixel(x + 5, y + 10, SSD1306_WHITE);
    display.drawPixel(x, y + 5, SSD1306_WHITE);
    display.drawPixel(x + 10, y + 5, SSD1306_WHITE);
    display.drawPixel(x + 1, y + 1, SSD1306_WHITE);
    display.drawPixel(x + 9, y + 1, SSD1306_WHITE);
    display.drawPixel(x + 1, y + 9, SSD1306_WHITE);
    display.drawPixel(x + 9, y + 9, SSD1306_WHITE);
  } else if (app == 5) {          // Wi-Fi
    display.fillCircle(x + 5, y + 10, 1, SSD1306_WHITE);
    display.drawLine(x + 2, y + 7, x + 5, y + 5, SSD1306_WHITE);
    display.drawLine(x + 5, y + 5, x + 8, y + 7, SSD1306_WHITE);
    display.drawLine(x, y + 4, x + 5, y + 1, SSD1306_WHITE);
    display.drawLine(x + 5, y + 1, x + 10, y + 4, SSD1306_WHITE);
  } else {                        // Calibration sliders
    display.drawLine(x, y + 2, x + 10, y + 2, SSD1306_WHITE);
    display.drawLine(x, y + 6, x + 10, y + 6, SSD1306_WHITE);
    display.drawLine(x, y + 10, x + 10, y + 10, SSD1306_WHITE);
    display.fillRect(x + 3, y, 2, 4, SSD1306_WHITE);
    display.fillRect(x + 7, y + 4, 2, 4, SSD1306_WHITE);
    display.fillRect(x + 1, y + 8, 2, 4, SSD1306_WHITE);
  }
}
void oledAppTitle() {
  int16_t x1;
  int16_t y1;
  uint16_t titleWidth;
  uint16_t titleHeight;
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setFont(&FreeSansBold9pt7b);
  display.setTextSize(1);
  display.getTextBounds(
    OLED_APP_NAMES[oledPage],
    0,
    0,
    &x1,
    &y1,
    &titleWidth,
    &titleHeight
  );
  // Centre the icon and app name together, not just the text.
  int groupX = (SCREEN_WIDTH - (int)titleWidth - 14) / 2;
  oledAppIcon(oledPage, groupX, 3);
  display.setCursor(groupX + 14, 13);
  display.print(OLED_APP_NAMES[oledPage]);
  display.setFont();
  display.setTextSize(1);
}
float calibrationSavedValue() {
  if (calibrationTarget == 0) return calibrationTargetMax ? soilWetRaw : soilDryRaw;
  if (calibrationTarget == 1) return calibrationTargetMax ? TEMP_ALERT_HIGH : TEMP_ALERT_LOW;
  if (calibrationTarget == 2) return calibrationTargetMax ? HUM_ALERT_HIGH : HUM_ALERT_LOW;
  return calibrationTargetMax ? LIGHT_ALERT_HIGH : LIGHT_ALERT_LOW;
}
void oledCalibrationTargetLabel(const String& text, int y) {
  // Keep long labels such as HUMIDITY MIN/MAX inside the 128-pixel display.
  // The rest of the calibration screen keeps its existing font treatment.
  oledCenteredText(text, y, 1);
}
void oledCalibrationArrowUp(int centerX, int topY) {
  display.fillTriangle(
    centerX,
    topY,
    centerX - 5,
    topY + 7,
    centerX + 5,
    topY + 7,
    SSD1306_WHITE
  );
}
void oledCalibrationArrowDown(int centerX, int topY) {
  display.fillTriangle(
    centerX - 5,
    topY,
    centerX + 5,
    topY,
    centerX,
    topY + 7,
    SSD1306_WHITE
  );
}
void oledCalibrationSelectionArrows() {
  const int arrowX = 119;
  oledCalibrationArrowUp(arrowX, 2);
  display.drawLine(arrowX, 10, arrowX, 24, SSD1306_WHITE);
  display.drawLine(arrowX, 31, arrowX, 45, SSD1306_WHITE);
  oledCalibrationArrowDown(arrowX, 46);
}
const char* calibrationDisplaySensorName() {
  if (calibrationTarget == 0) return "SOIL";
  if (calibrationTarget == 1) return "TEMP";
  if (calibrationTarget == 2) return "HUMIDITY";
  return "LIGHT";
}
void showCalibrationApp() {
  display.setTextSize(1);
  if (calibrationState == CAL_IDLE) {
    display.drawRect(3, 2, 109, 44, SSD1306_WHITE);
    oledCenteredTextInArea(calibrationDisplaySensorName(), 8, 2, 4, 111);
    oledCenteredTextInArea(
      calibrationTargetMax ? "MAX" : "MIN",
      28,
      2,
      4,
      111
    );
    oledCalibrationSelectionArrows();
    oledCenteredText("CENTER: MEASURE", 54, 1);
  } else if (calibrationState == CAL_SAMPLING) {
    oledCenteredText("TAKING 3 READINGS", 16, 1);
    oledCalibrationTargetLabel(
      String(calibrationSensorName()) + (calibrationTargetMax ? " MAX" : " MIN"),
      26
    );
    oledCenteredText("HOLD SENSOR STEADY", 38, 1);
    display.drawRoundRect(15, 46, 98, 7, 2, SSD1306_WHITE);
    if (calibrationSampleCount > 0) {
      display.fillRoundRect(17, 48, calibrationSampleCount * 31, 3, 1, SSD1306_WHITE);
    }
    oledCenteredText(
      String(calibrationSampleCount) + " / 3 READINGS",
      54,
      1
    );
  } else {
    int decimals = calibrationStep() < 1.0 ? 1 : 0;
    oledCenteredText("AVERAGE READING", 4, 1);
    oledCenteredFontTextInArea(
      String(calibrationAverage, decimals),
      17,
      &FreeSansBold18pt7b,
      3,
      125
    );
    oledCenteredText(
      calibrationStep() < 1.0 ? "UP/DOWN: +/-0.1" : "UP/DOWN: +/-1",
      46,
      1
    );
    oledCenteredText("CENTER: SAVE", 54, 1);
  }
}
void showCalibrationScreen() {
  if (calibrationIntroActive()) {
    display.clearDisplay();
    display.setTextColor(SSD1306_WHITE);
    display.setTextSize(1);
    oledCenteredFontTextInArea("CALIBRATION", 13, &FreeSans9pt7b, 4, 124);
    oledCenteredFontTextInArea("MODE", 36, &FreeSans9pt7b, 4, 124);
    display.display();
    return;
  }
  display.clearDisplay();
  display.setTextColor(SSD1306_WHITE);
  display.setTextSize(1);
  if (calibrationState != CAL_IDLE) {
    if (calibrationState == CAL_EDITING) {
      display.drawRect(1, 1, SCREEN_WIDTH - 2, 62, SSD1306_WHITE);
    } else {
      display.drawRoundRect(1, 1, SCREEN_WIDTH - 2, 62, 4, SSD1306_WHITE);
    }
  }
  showCalibrationApp();
  display.display();
}
void showOLED() {
  if (!oledDetected) {
    return;
  }
  if (startupPhase == STARTUP_BOOT) {
    showStartupSplashScreen();
    return;
  }
  if (
    startupPhase != STARTUP_READY ||
    millis() < startupResultDisplayUntil
  ) {
    oledSensorCheckScreen(startupSensorCheckPassed);
    return;
  }
  // Never leave a stale environmental reading on-screen after a sensor drops.
  if (hasSensorProblem()) {
    oledSensorCheckScreen(false);
    return;
  }
  if (oledAppMenuOpen) {
    showOLEDAppMenu();
    return;
  }
  if (oledPage == CALIBRATION_PAGE) {
    setOLEDContrast();
    showCalibrationScreen();
    return;
  }
  if (oledPage == 0) {
    // Slideshow mode is intentionally dimmer than the data apps.
    display.ssd1306_command(SSD1306_SETCONTRAST);
    display.ssd1306_command(SLIDESHOW_CONTRAST);
  } else {
    setOLEDContrast();
  }
  oledAppTitle();
  display.drawRoundRect(
    1,
    20,
    SCREEN_WIDTH - 2,
    38,
    3,
    SSD1306_WHITE
  );
  if (oledPage == 0) {
    oledCenteredText("AUTO SLIDESHOW", 24, 1);
    oledCenteredText(
      slideshowRunning ? "AUTO: ON" : "PAUSED",
      34,
      2
    );
    oledCenteredText("SELECT: PLAY / PAUSE", 50, 1);
  }
  else if (oledPage == 1) {
    if (soilState == SENSOR_NOT_FOUND || soilState == SENSOR_FAULT) {
      oledCenteredText(
        soilState == SENSOR_NOT_FOUND ? "N/A" : "ERROR",
        28,
        2
      );
    } else {
      oledCenteredText(String(soilPercent, 1) + "%", 27, 2);
    }
    oledSensorFooter(soilState);
  }
  else if (oledPage == 2) {
    if (ahtState == SENSOR_NOT_FOUND || ahtState == SENSOR_FAULT) {
      oledCenteredText(
        ahtState == SENSOR_NOT_FOUND ? "N/A" : "ERROR",
        28,
        2
      );
    } else {
      oledCenteredText(String(temperature, 1) + " C", 27, 2);
    }
    oledSensorFooter(ahtState);
  }
  else if (oledPage == 3) {
    if (ahtState == SENSOR_NOT_FOUND || ahtState == SENSOR_FAULT) {
      oledCenteredText(
        ahtState == SENSOR_NOT_FOUND ? "N/A" : "ERROR",
        28,
        2
      );
    } else {
      oledCenteredText(String(humidity, 1) + "%", 27, 2);
    }
    oledSensorFooter(ahtState);
  }
  else if (oledPage == 4) {
    if (lightState == SENSOR_NOT_FOUND || lightState == SENSOR_FAULT) {
      oledCenteredText(
        lightState == SENSOR_NOT_FOUND ? "N/A" : "ERROR",
        28,
        2
      );
    } else {
      float lightRange = LIGHT_ALERT_HIGH - LIGHT_ALERT_LOW;
      float lightPercent = lightRange > 0.0
        ? constrain(
            (lightLux - LIGHT_ALERT_LOW) / lightRange * 100.0,
            0.0,
            100.0
          )
        : 0.0;
      oledCenteredText(String(lightPercent, 0) + "%", 27, 2);
    }
    oledSensorFooter(lightState);
  }
  else if (oledPage == 5) {
    if (wifiActive) {
      oledCenteredText("ONLINE", 27, 2);
      oledCenteredText("192.168.4.1", 43, 1);
      oledCenteredText("SELECT: TURN OFF", 52, 1);
    } else {
      oledCenteredText("OFFLINE", 27, 2);
      oledCenteredText("SELECT: TURN ON", 48, 1);
    }
  }
  if (oledTransitionActive) {
    unsigned long transitionElapsed =
      millis() - oledTransitionStart;
    if (transitionElapsed >= OLED_TRANSITION_TIME) {
      oledTransitionActive = false;
    } else {
      float transitionProgress =
        (float)transitionElapsed /
        OLED_TRANSITION_TIME;
      float easedProgress =
        1.0 -
        (
          (1.0 - transitionProgress) *
          (1.0 - transitionProgress) *
          (1.0 - transitionProgress)
        );
      int incomingWidth =
        (int)(easedProgress * SCREEN_WIDTH);
      int splitX = oledTransitionFromLeft
        ? incomingWidth
        : SCREEN_WIDTH - incomingWidth;
      uint8_t* currentFrame = display.getBuffer();
      for (
        int row = 0;
        row < SCREEN_HEIGHT / 8;
        row++
      ) {
        for (
          int column = 0;
          column < SCREEN_WIDTH;
          column++
        ) {
          if (
            (oledTransitionFromLeft && column >= splitX) ||
            (!oledTransitionFromLeft && column < splitX)
          ) {
            int byteIndex =
              row * SCREEN_WIDTH + column;
            currentFrame[byteIndex] =
              oledOutgoingFrame[byteIndex];
          }
        }
      }
      display.drawLine(
        splitX,
        17,
        splitX,
        SCREEN_HEIGHT - 1,
        SSD1306_WHITE
      );
    }
  }
  display.display();
}
// ============================================================
//                        OLED TASK
// ============================================================
void oledTask() {
  unsigned long now = millis();
  // Startup and sensor-fault screens are static. Refreshing them every loop
  // overwhelms the I2C bus and can delay sensor recovery, so refresh at 4 FPS.
  bool statusScreenActive =
    startupPhase != STARTUP_READY ||
    millis() < startupResultDisplayUntil ||
    hasSensorProblem();
  if (statusScreenActive) {
    if (
      !oledWasShowingStatusScreen ||
      now - oledLastStatusRefresh >= OLED_STATUS_REFRESH_TIME
    ) {
      oledLastStatusRefresh = now;
      showOLED();
    }
    oledWasShowingStatusScreen = true;
    return;
  }
  // Restore the live dashboard immediately after a sensor recovers.
  if (oledWasShowingStatusScreen) {
    oledWasShowingStatusScreen = false;
    oledLastChange = now;
    showOLED();
    return;
  }
  if (
    slideshowRunning &&
    now - oledLastChange >= OLED_SLIDE_TIME
  ) {
    advanceSlideshowPage();
  }
  if (
    now - oledLastAppRefresh >= OLED_APP_REFRESH_TIME ||
    (
      oledTransitionActive &&
      now - oledLastFrame >= OLED_FRAME_TIME
    )
  ) {
    oledLastFrame = now;
    oledLastAppRefresh = now;
    showOLED();
  }
}
// ============================================================
//                     SYSTEM LOGIC
// ============================================================
void systemLogicTask() {
  uint16_t currentReasons = currentAlertReasons();
  if (
    systemMode ==
    NORMAL_MODE
  ) {
    if (
      currentReasons != 0
    ) {
      enterAlertMode();
    }
  }
  else {
    // Keep discovering new causes while already in alert mode. This is
    // evaluated every loop, independently of the sensor display refresh.
    uint16_t newReasons =
      currentReasons & ~activeAlertReasons;
    activeAlertReasons |= currentReasons;
    if (newReasons != 0) {
      // Tell the connected app about a newly affected condition too, not
      // only the first condition that put the monitor into alert mode.
      bleAlertPending = true;
    }
    if (
      alertConditionCleared()
    ) {
      exitAlertMode();
    }
  }
}
// ============================================================
//                          SETUP
// ============================================================
void setup() {
  Serial.begin(
    115200
  );
  calibrationPreferences.begin("plant-cal", false);
  soilDryRaw = calibrationPreferences.getFloat("soilDry", soilDryRaw);
  soilWetRaw = calibrationPreferences.getFloat("soilWet", soilWetRaw);
  SOIL_ALERT_LOW = calibrationPreferences.getFloat("soilLow", SOIL_ALERT_LOW);
  TEMP_ALERT_LOW = calibrationPreferences.getFloat("tempLow", TEMP_ALERT_LOW);
  TEMP_ALERT_HIGH = calibrationPreferences.getFloat("tempHigh", TEMP_ALERT_HIGH);
  HUM_ALERT_LOW = calibrationPreferences.getFloat("humLow", HUM_ALERT_LOW);
  HUM_ALERT_HIGH = calibrationPreferences.getFloat("humHigh", HUM_ALERT_HIGH);
  LIGHT_ALERT_LOW = calibrationPreferences.getFloat("lightLow", LIGHT_ALERT_LOW);
  LIGHT_ALERT_HIGH = calibrationPreferences.getFloat("lightHigh", LIGHT_ALERT_HIGH);
  tempScale = calibrationPreferences.getFloat("tempScale", tempScale);
  humidityOffset = calibrationPreferences.getFloat("humidityOffset", humidityOffset);
  humidityScale = calibrationPreferences.getFloat("humidityScale", humidityScale);
  tempOffset = calibrationPreferences.getFloat("tempOffset", tempOffset);
  lightScale = calibrationPreferences.getFloat("lightScale", lightScale);
  lightOffset = calibrationPreferences.getFloat("lightOffset", lightOffset);
  updateAlertClearLimits();
  printCalibrationAndAlertSettings();
  Serial.println();
  Serial.println(
    "================================"
  );
  Serial.println(
    " SMART PLANT MONITORING SYSTEM"
  );
  Serial.println(
    "================================"
  );
  Serial.println();
  // ==========================================================
  // GPIO
  // ==========================================================
  pinMode(
    BUTTON_PIN,
    INPUT_PULLUP
  );
  pinMode(OLED_LEFT_PIN, INPUT_PULLUP);
  pinMode(OLED_RIGHT_PIN, INPUT_PULLUP);
  pinMode(OLED_UP_PIN, INPUT_PULLUP);
  pinMode(OLED_DOWN_PIN, INPUT_PULLUP);
  pinMode(
    RGB_R_PIN,
    OUTPUT
  );
  pinMode(
    RGB_G_PIN,
    OUTPUT
  );
  pinMode(
    RGB_B_PIN,
    OUTPUT
  );
  pinMode(
    BUZZER_PIN,
    OUTPUT
  );
  setRGB(
    false,
    false,
    false
  );
  // ==========================================================
  // LittleFS
  // ==========================================================
  if (
    !LittleFS.begin(true)
  ) {
    Serial.println(
      "ERROR: LittleFS initialization failed!"
    );
  }
  else {
    Serial.println(
      "LittleFS initialized."
    );
  }
  // ==========================================================
  // I2C
  // ==========================================================
  Wire.begin(
    I2C_SDA,
    I2C_SCL
  );
  // ==========================================================
  // OLED
  // ==========================================================
  if (
    display.begin(
      SSD1306_SWITCHCAPVCC,
      OLED_ADDRESS
    )
  ) {
    oledDetected = true;
  setOLEDContrast();
    Serial.println(
      "OLED detected."
    );
    display.clearDisplay();
    display.setTextColor(
      SSD1306_WHITE
    );
    display.setTextSize(1);
    display.setCursor(
      20,
      20
    );
    display.println(
      "SMART PLANT"
    );
    display.setCursor(
      28,
      35
    );
    display.println(
      "STARTING..."
    );
    display.display();
  }
  else {
    oledDetected = false;
    Serial.println(
      "OLED NOT FOUND."
    );
  }
  // ==========================================================
  // STARTUP BEEP
  // ==========================================================
  startupBeep();
  // ==========================================================
  // AHT21B
  // ==========================================================
  if (
    aht.begin(&Wire)
  ) {
    ahtDetected = true;
    ahtState =
      SENSOR_RESTING;
    Serial.println(
      "AHT21B detected."
    );
  }
  else {
    ahtDetected = false;
    ahtState =
      SENSOR_NOT_FOUND;
    Serial.println(
      "AHT21B NOT FOUND."
    );
  }
  // ==========================================================
  // BH1750
  // ==========================================================
  if (
    bh1750.begin(
      BH1750::CONTINUOUS_HIGH_RES_MODE
    )
  ) {
    bhDetected = true;
    lightState =
      SENSOR_RESTING;
    Serial.println(
      "BH1750 detected."
    );
  }
  else {
    bhDetected = false;
    lightState =
      SENSOR_NOT_FOUND;
    Serial.println(
      "BH1750 NOT FOUND."
    );
  }
  if (bhDetected) {
    readBH1750();
  }
  // ==========================================================
  // SOIL
  // ==========================================================
  soilState =
    SENSOR_RESTING;
  // ==========================================================
  // WEB SERVER ROUTES
  // ==========================================================
  setupServerRoutes();
  // ==========================================================
  // START FIRST SENSOR
  // ==========================================================
  startSensor(
    SOIL_SENSOR
  );
  // ==========================================================
  // WIFI
  // ==========================================================
  WiFi.mode(
    WIFI_OFF
  );
  wifiActive = false;
  // BLE stays available for the Android app while Wi-Fi is off.
  initBLE();
  // ==========================================================
  // BOOT SPLASH SEQUENCE
  // ==========================================================
  startupPhase = STARTUP_BOOT;
  startupSplashIndex = 0;
  startupSplashStart = 0;
  startupSensorCheckPassed = false;
  startupCheckLastAttempt = 0;
  startupWarningLastBeep = 0;
  // ==========================================================
  // OLED
  // ==========================================================
  showOLED();
  Serial.println();
  Serial.println(
    "System ready."
  );
  Serial.println(
    "Wi-Fi is controlled from the Wi-Fi Status app."
  );
  Serial.println(
    "Use left/right to select an app."
  );
  Serial.println(
    "GPIO 27 selects an app or toggles Wi-Fi."
  );
  Serial.println(
    "In other apps, GPIO 27 returns to the app menu."
  );
  Serial.println(
    "OLED controls: GPIO 32 left, 4 right, 16 up, 17 down."
  );
  Serial.println(
    "Up/down change contrast by 10."
  );
  Serial.println();
}
// ============================================================
//                           LOOP
// ============================================================
void loop() {
  // Button
  checkButton();
  checkOLEDButton();
  if (startupPhase == STARTUP_BOOT) {
    startupSplashTask();
    updateBuzzer();
    oledTask();
    updateRGB();
    bleTask();
    return;
  }
  if (startupPhase == STARTUP_SENSOR_CHECK) {
    startupSensorCheckTask();
    updateBuzzer();
    oledTask();
    updateRGB();
    bleTask();
    return;
  }
  if (startupPhase == STARTUP_READY) {
    if (millis() < startupResultDisplayUntil) {
      updateBuzzer();
      oledTask();
      updateRGB();
      bleTask();
      return;
    }
  }
  // Wi-Fi
  wifiTask();
  // Buzzer
  updateBuzzer();
  // Sensors
  sensorRecoveryTask();
  if (
    systemMode ==
    NORMAL_MODE
  ) {
    normalSensorTask();
  }
  else {
    alertSensorTask();
  }
  // Take calibration samples after the regular sensor update so the first
  // sample is also a fresh reading from the connected sensor.
  calibrationTask();
  // Alert logic
  systemLogicTask();
  // BLE telemetry and queued alert event for the connected app.
  bleTask();
  // OLED
  oledTask();
  // RGB
  updateRGB();
}
