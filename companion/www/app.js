// OneWheel Companion — Phase 2 (watch link test)
//
// Pushes Connected / BatteryPercent / Speed to the watch through the custom
// "OwPebbleBridge" Capacitor plugin (native Kotlin wrapper around PebbleKit
// Android 2). Real OneWheel BLE is added in Phase 3.

const OwPebbleBridge = window.Capacitor
  ? window.Capacitor.registerPlugin("OwPebbleBridge")
  : null;

const batterySlider = document.getElementById("batterySlider");
const speedSlider = document.getElementById("speedSlider");
const batteryOut = document.getElementById("battery");
const speedOut = document.getElementById("speed");
const sendBtn = document.getElementById("sendBtn");
const streamBtn = document.getElementById("streamBtn");
const statusOut = document.getElementById("status");

let streamTimer = null;

function currentValues() {
  const battery = Number(batterySlider.value); // 0..100
  const speedTenths = Number(speedSlider.value); // mph * 10
  return { connected: true, battery, speed: speedTenths };
}

function renderReadout({ battery, speed }) {
  batteryOut.textContent = `${battery}%`;
  speedOut.textContent = `${(speed / 10).toFixed(1)} mph`;
}

function setStatus(message) {
  statusOut.textContent = message;
}

async function sendToWatch(values) {
  renderReadout(values);
  if (!OwPebbleBridge) {
    setStatus("Capacitor bridge unavailable (run inside the Android app).");
    return;
  }
  try {
    const res = await OwPebbleBridge.send(values);
    setStatus(`Sent: ${res && res.result ? res.result : "ok"}`);
  } catch (err) {
    setStatus(`Send failed: ${err.message || err}`);
  }
}

batterySlider.addEventListener("input", () => renderReadout(currentValues()));
speedSlider.addEventListener("input", () => renderReadout(currentValues()));

sendBtn.addEventListener("click", () => sendToWatch(currentValues()));

streamBtn.addEventListener("click", () => {
  if (streamTimer) {
    clearInterval(streamTimer);
    streamTimer = null;
    streamBtn.textContent = "Start stream";
    setStatus("Stream stopped.");
    return;
  }
  streamBtn.textContent = "Stop stream";
  setStatus("Streaming every 1s...");
  streamTimer = setInterval(() => sendToWatch(currentValues()), 1000);
});

renderReadout(currentValues());
