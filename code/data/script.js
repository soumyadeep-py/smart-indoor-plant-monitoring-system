const connectionDot =
    document.getElementById("connectionDot");

const connectionText =
    document.getElementById("connectionText");

const systemStatus =
    document.getElementById("systemStatus");

const wifiStatus =
    document.getElementById("wifiStatus");

const systemMessage =
    document.getElementById("systemMessage");


// Sensor elements

const soilCard =
    document.getElementById("soilCard");

const soilValue =
    document.getElementById("soilValue");

const soilState =
    document.getElementById("soilState");


const temperatureCard =
    document.getElementById("temperatureCard");

const temperatureValue =
    document.getElementById("temperatureValue");

const temperatureState =
    document.getElementById("temperatureState");


const humidityCard =
    document.getElementById("humidityCard");

const humidityValue =
    document.getElementById("humidityValue");

const humidityState =
    document.getElementById("humidityState");


const lightCard =
    document.getElementById("lightCard");

const lightValue =
    document.getElementById("lightValue");

const lightState =
    document.getElementById("lightState");


// ======================================================
// CONNECTION STATUS
// ======================================================

function setConnectionStatus(connected) {

    if (connected) {

        connectionDot.classList.remove(
            "offline"
        );

        connectionDot.classList.add(
            "online"
        );

        connectionText.textContent =
            "ESP32 Connected";

    } else {

        connectionDot.classList.remove(
            "online"
        );

        connectionDot.classList.add(
            "offline"
        );

        connectionText.textContent =
            "Connection Lost";
    }
}


// ======================================================
// SENSOR DISPLAY
// ======================================================

function updateSensor(
    cardElement,
    valueElement,
    stateElement,
    sensor
) {

    cardElement.classList.remove(
        "alert",
        "sensor-error"
    );

    stateElement.classList.remove(
        "normal",
        "alert",
        "error"
    );

    if (!sensor) {
        valueElement.textContent = "—";
        stateElement.textContent = "NO DATA";
        stateElement.classList.add("error");
        cardElement.classList.add("sensor-error");
        return;
    }


    // Sensor missing / invalid

    if (
        sensor.value === null ||
        sensor.value === undefined
    ) {

        valueElement.textContent =
            "—";

        stateElement.textContent =
            sensor.state || "NO DATA";

        stateElement.classList.add("error");
        cardElement.classList.add("sensor-error");

        return;
    }


    // Display reading

    valueElement.textContent =
        Number(sensor.value).toFixed(1);

    // The firmware evaluates this using the saved alert limits, so the
    // web UI stays correct even after the user calibrates the thresholds.
    const isAlert =
        sensor.alert === true;

    if (isAlert) {
        cardElement.classList.add("alert");
        stateElement.textContent = "ALERT";
        stateElement.classList.add("alert");
    } else {
        stateElement.textContent =
            sensor.state || "ACTIVE";

        stateElement.classList.add("normal");
    }
}


// ======================================================
// SYSTEM MESSAGE
// ======================================================

function updateSystemMessage(data) {

    const alertSensors = [
        ["Soil moisture", data.soil],
        ["Temperature", data.temperature],
        ["Humidity", data.humidity],
        ["Light", data.light]
    ]
        .filter(([, sensor]) => sensor && sensor.alert === true)
        .map(([name]) => name);

    if (data.system === "ALERT") {

        const verb =
            alertSensors.length === 1 ? "is" : "are";

        systemMessage.textContent = alertSensors.length
            ? `Alert: ${alertSensors.join(
                ", "
            )} ${verb} outside the configured limit.`
            : "One or more plant conditions have exceeded the configured threshold.";

        systemStatus.textContent =
            "ALERT";

        systemStatus.classList.remove(
            "normal"
        );

        systemStatus.classList.add(
            "alert"
        );

    } else {

        systemMessage.textContent =
            "Plant conditions are currently within the configured safe range.";

        systemStatus.textContent =
            "NORMAL";

        systemStatus.classList.remove(
            "alert"
        );

        systemStatus.classList.add(
            "normal"
        );
    }
}


// ======================================================
// WIFI STATUS
// ======================================================

function updateWiFiStatus(data) {

    if (data.wifi) {

        wifiStatus.textContent =
            "ON";

    } else {

        wifiStatus.textContent =
            "OFF";
    }
}


// ======================================================
// GET SENSOR DATA
// ======================================================

async function updateData() {

    try {

        const response =
            await fetch(
                "/api",
                {
                    cache: "no-store"
                }
            );


        if (!response.ok) {

            throw new Error(
                "API request failed"
            );
        }


        const data =
            await response.json();


        setConnectionStatus(true);


        // System

        updateSystemMessage(data);

        updateWiFiStatus(data);


        // Sensors

        updateSensor(
            soilCard,
            soilValue,
            soilState,
            data.soil
        );


        updateSensor(
            temperatureCard,
            temperatureValue,
            temperatureState,
            data.temperature
        );


        updateSensor(
            humidityCard,
            humidityValue,
            humidityState,
            data.humidity
        );


        updateSensor(
            lightCard,
            lightValue,
            lightState,
            data.light
        );


    } catch (error) {

        console.log(
            "ESP32 connection error:",
            error
        );


        setConnectionStatus(false);


        systemMessage.textContent =
            "Unable to communicate with the ESP32.";

    }
}


// ======================================================
// AUTO REFRESH
// ======================================================

updateData();

setInterval(
    updateData,
    1000
);
