// OneWheel Companion — Phase 3 (live board link)
//
// The native "OwPebbleBridge" Capacitor plugin (Kotlin) scans for the Onewheel+
// XR, performs the BLE unlock handshake, subscribes to battery/speed, forwards
// them to the watch over PebbleKit Android 2, and emits events back to this UI.
//
// A collapsible "Developer test" panel keeps the Phase 2 manual sliders so the
// watch link can be verified without a board.

// Resolve the native "OwPebbleBridge" plugin. This is a no-bundler Capacitor
// app, so the injected native bridge may not expose `registerPlugin` (it threw
// "registerPlugin is not a function" on device). Custom plugins registered
// natively are always reachable via `Capacitor.Plugins.<name>`, so prefer that
// and fall back to `registerPlugin` only when it actually exists.
const Cap = window.Capacitor;
const OwPebbleBridge = Cap
  ? (Cap.Plugins && Cap.Plugins.OwPebbleBridge) ||
    (typeof Cap.registerPlugin === "function"
      ? Cap.registerPlugin("OwPebbleBridge")
      : null)
  : null;

const dot = document.getElementById("dot");
const batteryOut = document.getElementById("battery");
const speedOut = document.getElementById("speed");
const connectBtn = document.getElementById("connectBtn");
const disconnectBtn = document.getElementById("disconnectBtn");
const statusOut = document.getElementById("status");

// Developer test panel.
const batterySlider = document.getElementById("batterySlider");
const speedSlider = document.getElementById("speedSlider");
const sendBtn = document.getElementById("sendBtn");
const streamBtn = document.getElementById("streamBtn");
const devStatus = document.getElementById("devStatus");

let streamTimer = null;

function setStatus(message) {
  statusOut.textContent = message;
}

function setConnectionUi(state) {
  // state: "idle" | "scanning" | "connecting" | "connected" | "disconnected" | "error"
  dot.className = `dot ${state}`;
  const busy = state === "scanning" || state === "connecting";
  const connected = state === "connected";
  connectBtn.disabled = busy || connected;
  disconnectBtn.disabled = !(busy || connected);
}

function renderReadout(battery, speedTenths) {
  batteryOut.textContent = Number.isFinite(battery) ? `${battery}%` : "--%";
  speedOut.textContent = Number.isFinite(speedTenths)
    ? `${(speedTenths / 10).toFixed(1)} mph`
    : "-- mph";
}

// ───────────────────────────── Board connection ─────────────────────────────

if (OwPebbleBridge) {
  OwPebbleBridge.addListener("boardStatus", (e) => {
    setStatus(e.message || e.status);
    setConnectionUi(e.status);
    if (e.status === "disconnected" || e.status === "error") {
      renderReadout(NaN, NaN);
    }
  });

  OwPebbleBridge.addListener("boardUpdate", (e) => {
    if (e.connected) setConnectionUi("connected");
    renderReadout(e.battery, e.speed);
  });
}

async function connect() {
  if (!OwPebbleBridge) {
    setStatus("Capacitor bridge unavailable (run inside the Android app).");
    return;
  }
  setConnectionUi("scanning");
  setStatus("Searching for board…");
  try {
    await OwPebbleBridge.startScan();
  } catch (err) {
    setConnectionUi("error");
    setStatus(`Connect failed: ${err.message || err}`);
  }
}

async function disconnect() {
  if (!OwPebbleBridge) return;
  try {
    await OwPebbleBridge.disconnect();
  } catch (err) {
    setStatus(`Disconnect failed: ${err.message || err}`);
  }
  setConnectionUi("idle");
  setStatus("Idle");
  renderReadout(NaN, NaN);
}

connectBtn.addEventListener("click", connect);
disconnectBtn.addEventListener("click", disconnect);

// ─────────────────────────── Developer test panel ───────────────────────────

function devValues() {
  return {
    connected: true,
    battery: Number(batterySlider.value), // 0..100
    speed: Number(speedSlider.value), // mph * 10
  };
}

async function devSend() {
  const values = devValues();
  if (!OwPebbleBridge) {
    devStatus.textContent = "Capacitor bridge unavailable.";
    return;
  }
  try {
    const res = await OwPebbleBridge.send(values);
    devStatus.textContent = `Sent: ${res && res.result ? res.result : "ok"}`;
  } catch (err) {
    devStatus.textContent = `Send failed: ${err.message || err}`;
  }
}

sendBtn.addEventListener("click", devSend);

streamBtn.addEventListener("click", () => {
  if (streamTimer) {
    clearInterval(streamTimer);
    streamTimer = null;
    streamBtn.textContent = "Start stream";
    devStatus.textContent = "Stream stopped.";
    return;
  }
  streamBtn.textContent = "Stop stream";
  devStatus.textContent = "Streaming every 1s…";
  streamTimer = setInterval(devSend, 1000);
});

setConnectionUi("idle");
renderReadout(NaN, NaN);
