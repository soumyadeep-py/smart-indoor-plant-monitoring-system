# 🌱 Smart Plant Monitoring System using ESP32

A smart indoor plant monitoring system based on **ESP32** that monitors soil moisture, temperature, humidity, and ambient light. The system displays the sensor information on an OLED display and provides a web-based dashboard hosted directly by the ESP32.

The system is designed to reduce unnecessary sensor polling by operating the sensors in a **time-multiplexed sensing cycle** during normal operation. If an abnormal condition is detected, the system switches to **Alert Mode** and monitors the sensors more frequently.


## 📌 Features

- 🌱 Soil moisture monitoring
- 🌡️ Temperature monitoring
- 💧 Humidity monitoring
- ☀️ Ambient light monitoring
- 📺 0.96" OLED status display
- 🌐 Web dashboard hosted directly by ESP32
- 📡 ESP32 operates as a Wi-Fi Access Point
- 🔄 Time-multiplexed sensor monitoring
- 🚨 Automatic Alert Mode when threshold conditions are detected
- 💡 RGB LED system-status indication
- 🔊 Buzzer notification
- 🔘 Push-button to wake the Wi-Fi access point
- 💾 Web files stored using ESP32 LittleFS
- 📱 Dashboard can be accessed from a phone or computer connected to the ESP32 Wi-Fi network
- 📲 Native Android app for live readings over Bluetooth Low Energy
- 🧪 In-app calibration for soil, temperature, humidity, and light sensors
- 🎚️ User-configurable low/high alert limits
- 🚿 Android control for automatic watering enable/disable and moisture limits
- 🎛️ Manual motor switch in the Android app with OLED and web status
- 🔁 Continuous alert-state evaluation and automatic return to normal mode
- 🧠 Per-sensor alert cause tracking so unrelated sensors do not hold the system in alert mode
- 🟢 Green RGB LED for normal operation, 🔴 red for active plant alerts, and 🔵 blue for sensor problems
- 🔔 Android notifications when the ESP32 enters alert mode
- 💾 Calibration and alert limits persist across ESP32 restarts

## 🚨 Alert and recovery behavior

The ESP32 checks the plant state continuously. In normal mode, sensors use the time-multiplexed cycle to reduce unnecessary polling. When an alert is detected, all sensors are read approximately once per second until the active alert cause recovers.

The system uses a small recovery hysteresis to prevent rapid red/green switching:

- Soil moisture clears at 2 percentage points above its low alert limit.
- Temperature clears within 0.5 °C inside its configured low/high limits.
- Humidity clears within 2 percentage points inside its configured low/high limits.
- Light uses its existing wider recovery band based on the configured light range.

Only the sensor conditions that actually caused the alert are latched. Once those conditions recover, the system returns to normal and the RGB LED changes back to green. Alert-limit values are stored in ESP32 Preferences using NVS-compatible key names, so they remain available after reboot.

---

# 🧰 Hardware Required

| Component | Quantity |
|---|---:|
| ESP32 WROOM-32 Development Board | 1 |
| Capacitive Soil Moisture Sensor | 1 |
| AHT21B Temperature & Humidity Sensor | 1 |
| BH1750 Ambient Light Sensor | 1 |
| 0.96" OLED Display | 1 |
| Common-Cathode RGB LED | 1 |
| Passive Buzzer | 1 |
| Push Button | 1 |
| Connecting Wires | As required |

---

# 🔌 Pin Configuration

| Component | ESP32 Pin |
|---|---|
| Soil Moisture Sensor | GPIO 34 |
| I2C SDA | GPIO 21 |
| I2C SCL | GPIO 22 |
| Push Button | GPIO 27 |
| RGB LED - Red | GPIO 25 |
| RGB LED - Green | GPIO 26 |
| RGB LED - Blue | GPIO 33 |
| Passive Buzzer | GPIO 14 |
| OLED I2C Address | 0x3C |

### Pump driver connection

The current `code/code.ino` defaults to an ESP32-WROOM-32 setup:

| Component | Default pin |
|---|---:|
| Soil moisture ADC | GPIO 34 |
| Pump transistor control | GPIO 5 |

GPIO 5 in the sketch is the signal to the transistor base/gate; it does not
power the pump directly. GPIO 5 is a boot-strapping pin, so if it affects boot
on your particular board, move the transistor signal to another unused digital
output and update `PUMP_PIN`.

Use a separate supply for the pump, connect the pump supply ground to ESP32 GND,
add a base/gate resistor, and place a flyback diode across a DC pump. The pump
must never be connected directly to an ESP32 GPIO.

### Watering algorithm

The firmware converts the averaged soil ADC reading to a percentage using
`SOIL_DRY_RAW` and `SOIL_WET_RAW`. It then:

1. Requires three consecutive readings below 30% moisture.
2. Turns the transistor signal on for a 2-second pump pulse.
3. Waits 2 minutes for the water to spread before sampling again.
4. Stops when moisture reaches 40%, when the soil is no longer below the start
   threshold, or after three pulses.
5. Enforces a 30-minute minimum interval between watering cycles.

The pump output is forced OFF at boot and whenever soil calibration is invalid.
Adjust the thresholds and timings in `code/code.ino` for the plant and pump.

The AHT21B, BH1750 and OLED share the same I2C bus:

```text
ESP32 GPIO 21 ───── SDA ───── AHT21B
                           ├── BH1750
                           └── OLED

ESP32 GPIO 22 ───── SCL ───── AHT21B
                           ├── BH1750
                           └── OLEDz
```

--- 

# 📂 File Structure
```text
Smart Plant Monitoring System/
├── android-app/
│   └── app/
│       └── src/
│           └── main/
│               ├── java/
│               │   └── com/example/smartplantmonitor/
│               │       ├── MainActivity.kt
│               │       ├── PlantBleManager.kt
│               │       └── ui/
│               ├── res/
│               └── AndroidManifest.xml
├── code/
│   ├── code.ino
│   ├── partitions.csv
│   └── data/
│       ├── index.html
│       ├── style.css
│       └── script.js
├── README.md
├── .gitignore
└── .vscode/
```

## ⚙️ Installation and setup

1. Open `code/code.ino` in the Arduino IDE or ESP32 development environment.
2. Flash the firmware to the ESP32 board.
3. Upload the files from `code/data` to the ESP32 LittleFS storage so the web dashboard works correctly. For this, press <kbd>Ctrl</kbd> + <kbd>Shift</kbd> + <kbd>P</kbd> and then search "Upload LittleFS to Pico/ESP8266/ESP32".
4. Install the Android APK on a phone by opening `app.apk` and following the installation prompts.
5. Enable Bluetooth on the phone and allow the app to access Nearby devices and notifications when prompted. You may also connect to Wifi of the ESP32 which is `SmartPlant-ESP32` and then putting the password `plant1234` and then on browser, go to `192.168.4.1`. You will see dashboard of sensor readings and system status.

## 📱 Android app

The `app` module is a native Jetpack Compose Android client for the BLE service exposed by the ESP32. It provides:

- Live soil moisture, temperature, humidity, and light readings
- Live normal/alert status and sensor health updates over BLE
- Connection status and sensor health state
- Calibration controls for soil dry/wet ADC values and scale/offset correction
- Configurable low/high alert limits with validation
- Android system notifications from the BLE alert characteristic

### Running the app

1. Power on the ESP32 after flashing the firmware.
2. Open the installed Android app on the phone.
3. Tap **Find ESP32 monitor**. The app searches for the advertised device named `Smart Plant Monitor`.
4. Connect to the device and begin monitoring the readings.

The app uses the custom BLE service already defined in the firmware. Settings are sent as short `key=value` commands and are persisted by the ESP32, so calibration and alert limits remain after a reboot. The phone creates the Android notification when it receives an alert event; the ESP32 itself cannot create a phone notification directly.

The **Alert limits** screen also contains **Automatic watering** controls. The
switch enables or disables pump control, while the start and stop percentages
define the dry and wet limits used by the watering algorithm. These values are
sent over BLE and saved in ESP32 NVS. The current watering firmware accepts:

```text
waterenabled=0|1
waterstart=0..100
waterstop=0..100 (must be greater than waterstart)
```

Build a fresh Android APK after changing these controls; the previously built
`app.apk` will not contain the new UI.

The **Monitor** screen also includes a manual **Pump motor** switch. Manual
motor control overrides automatic watering and is automatically stopped by the
firmware after 30 seconds as a safety limit. The OLED Wi-Fi/status page and the
web dashboard show the live motor state.

## 🌐 ESP32 web dashboard

The project also includes a lightweight dashboard hosted directly by the ESP32. The website source is stored in:

```text
code/data/index.html
code/data/style.css
code/data/script.js
```

These files are uploaded to the ESP32’s LittleFS filesystem and served by the built-in web server. The dashboard displays:

- Soil moisture, temperature, humidity, and light values
- Sensor states and connection status
- Normal or alert system status
- ESP32 Wi-Fi status

### Using the dashboard

1. Press the ESP32 control button on GPIO 27 to enable the Wi-Fi access point.
2. Connect a phone or computer to Wi-Fi network `SmartPlant-ESP32` using password `plant1234`.
3. Open [http://192.168.4.1](http://192.168.4.1) in a browser.

The dashboard reads live JSON data from the `/api` endpoint and refreshes approximately once per second. The ESP32 can turn Wi-Fi off after a period without a connected client; press the control button again to wake it.


# 👨‍💻 About the Creators
> ## Software Head
Hi! I'm **Soumyadeep Samanta**, a first-year **B.Tech student in Computer Science and Engineering**.

I am interested in **programming, embedded systems, IoT, and developing practical technology-based projects**. Through this project, I am exploring the integration of hardware, sensors, embedded programming, and web technologies using the ESP32.

This project is part of my learning journey, where I aim to strengthen my skills in:
- 💻 C/C++ programming
- 🔌 Embedded systems
- 🌐 Web development
- 📡 IoT and wireless communication
- 🧩 Hardware-software integration
- 🌱 Smart monitoring systems

I enjoy learning by building projects and experimenting with different technologies.

My portfolio : https://soumyadeep-py.github.io/

"Learn fundamentals. Experiment. Debug. Improve."

> ## Hardware Head
Hi, I'm Tamaghna Basu, a first-year B.Tech ECE student with a strong interest in electronics, embedded systems, microcontrollers, sensors, and hardware-based technology. I enjoy understanding how electronic circuits work and turning ideas into practical projects by combining hardware and programming.

🔧 I am particularly interested in exploring **Arduino, ESP32**, **sensors**, **digital electronics**, **analog circuits**, **communication systems**, and **embedded programming**.

🛠️ Skills I'm Building
Through academic work and personal projects, I am working on strengthening my skills in:

- 💻 C / C++
- 🔧 Arduino & ESP32
- 📡 Sensor Interfacing
- ⚡ GPIO, ADC & PWM
- 🔄 I2C & Serial Communication
- 🧩 Circuit Design & Prototyping
- 🖥️ Embedded Programming
- 🧬 PCB & Electronics Fundamentals
- 🤝 Hardware–Software Integration

💡 I believe the best way to learn electronics is by building, testing, debugging, and experimenting.

🔍 I use my projects to understand how components such as microcontrollers, sensors, LEDs, displays, buzzers, and communication modules work together to create useful electronic systems.

🚀 Currently, I am focusing on developing a strong foundation in electronics and embedded systems while gradually exploring more advanced areas of ECE.

"Learn the fundamentals. Build something. Break it. Debug it. Build it better."
