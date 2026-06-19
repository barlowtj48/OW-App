package com.owapp.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID

/**
 * Capacitor bridge for the OneWheel Pebble companion.
 *
 * Phase 3: connects to the OneWheel board over BLE (natively), performs the
 * Onewheel+ XR "Gemini" unlock handshake, subscribes to live battery and speed,
 * and forwards them to the Pebble watch over PebbleKit Android 2 while emitting
 * `boardUpdate` / `boardStatus` events to the web UI.
 *
 * JS usage:
 *   const OwPebbleBridge = Capacitor.registerPlugin("OwPebbleBridge");
 *   OwPebbleBridge.addListener("boardStatus", e => ...);   // { status, message }
 *   OwPebbleBridge.addListener("boardUpdate", e => ...);   // { connected, battery, speed }
 *   await OwPebbleBridge.startScan();
 *   await OwPebbleBridge.disconnect();
 *   await OwPebbleBridge.send({ connected: true, battery: 80, speed: 124 }); // manual test
 */
@CapacitorPlugin(
    name = "OwPebbleBridge",
    permissions = [
        Permission(
            alias = "ble",
            strings = [
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            ],
        ),
        Permission(
            alias = "location",
            strings = [Manifest.permission.ACCESS_FINE_LOCATION],
        ),
    ],
)
class OwPebbleBridgePlugin : Plugin() {

    companion object {
        private const val TAG = "OwBridge"

        // Stop searching after this long if the board never shows up.
        private const val SCAN_TIMEOUT_MS = 15000L

        // Must match the watch app's UUID (package.json).
        private val APP_UUID = UUID.fromString("44bd15cb-2134-41d8-9af8-cbda0d9dd4d9")

        // Must match the watch's generated MESSAGE_KEY_* numbers (logged at boot).
        private const val KEY_CONNECTED = 10000u
        private const val KEY_BATTERY = 10001u
        private const val KEY_SPEED = 10002u

        // OneWheel GATT (verified from the OneWheel reverse-engineering community).
        private val SERVICE = UUID.fromString("e659f300-ea98-11e3-ac10-0800200c9a66")
        private val CHAR_BATTERY = UUID.fromString("e659f303-ea98-11e3-ac10-0800200c9a66")
        private val CHAR_SPEED_RPM = UUID.fromString("e659f30b-ea98-11e3-ac10-0800200c9a66")
        private val CHAR_FIRMWARE = UUID.fromString("e659f311-ea98-11e3-ac10-0800200c9a66")
        private val CHAR_SERIAL_READ = UUID.fromString("e659f3fe-ea98-11e3-ac10-0800200c9a66")
        private val CHAR_SERIAL_WRITE = UUID.fromString("e659f3ff-ea98-11e3-ac10-0800200c9a66")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Boards on firmware >= 4034 ("Gemini", incl. the XR) require the unlock
        // handshake before they stream data.
        private const val GEMINI_FW_MIN = 4034

        // Local XR unlock constants (no network required).
        private val UNLOCK_PREFIX = byteArrayOf(0x43, 0x52, 0x58) // "CRX"
        private val UNLOCK_SALT = hexToBytes("D9255F0F23354E19BA739CCDC4A91765")

        // OneWheel tire: convert wheel RPM to mph.
        private val MPH_PER_RPM = 60.0 * Math.PI * 11.5 / 63360.0

        private fun hexToBytes(hex: String): ByteArray {
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(hex[i * 2], 16) shl 4) +
                    Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            return out
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender by lazy { DefaultPebbleSender(context.applicationContext) }

    // BLE state.
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var geminiUnlock = false
    private var keySent = false
    private var firmwareValue: ByteArray? = null
    private var launchingApp = false
    private val loggedScans = HashSet<String>()
    private val challenge = ByteArrayOutputStream()
    private val notifyQueue = ArrayDeque<UUID>()
    private val readQueue = ArrayDeque<UUID>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable {
        if (scanning) {
            scanning = false
            runCatching { scanner?.stopScan(scanCallback) }
            Log.w(TAG, "Scan timed out — board not found")
            emitStatus(
                "error",
                "Board not found. Make sure it's on and not connected to the OneWheel app.",
            )
        }
    }

    // Latest board values.
    private var sBattery = 0
    private var sSpeedTenths = 0
    private var sConnected = false
    private var lastWatchSendMs = 0L

    // ───────────────────────────── JS-facing methods ─────────────────────────────

    @PluginMethod
    fun startScan(call: PluginCall) {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "ble" else "location"
        if (getPermissionState(alias) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias, call, "onPermsResult")
            return
        }
        beginScan(call)
    }

    @PermissionCallback
    private fun onPermsResult(call: PluginCall) {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "ble" else "location"
        if (getPermissionState(alias) == PermissionState.GRANTED) {
            beginScan(call)
        } else {
            call.reject("Bluetooth permission denied")
        }
    }

    @PluginMethod
    fun disconnect(call: PluginCall) {
        teardown("Disconnected")
        call.resolve()
    }

    @PluginMethod
    fun send(call: PluginCall) {
        // Manual test path (Phase 2): push explicit values to the watch.
        val connected = call.getBoolean("connected", false) == true
        val battery = (call.getInt("battery", 0) ?: 0).coerceIn(0, 100)
        val speed = (call.getInt("speed", 0) ?: 0).coerceIn(0, 65535)
        scope.launch {
            try {
                val result = deliverToWatch(connected, battery, speed)
                val ret = JSObject()
                ret.put("result", result)
                call.resolve(ret)
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                call.reject("send failed: ${e.message}", e)
            }
        }
    }

    // ─────────────────────────────── BLE scanning ───────────────────────────────

    @SuppressLint("MissingPermission")
    private fun beginScan(call: PluginCall) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = manager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            call.reject("Bluetooth is off")
            emitStatus("error", "Bluetooth is off")
            return
        }

        // The OneWheel often does NOT advertise its 128-bit service UUID, and
        // when it's already linked to the phone it may not be advertising at
        // all. So first look at devices the phone already knows about (active
        // GATT connections + bonded/paired devices) and connect straight to a
        // board if one is there.
        val known = buildList {
            addAll(runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }.getOrDefault(emptyList()))
            addAll(runCatching { adapter.bondedDevices?.toList() }.getOrNull().orEmpty())
        }
        known.distinctBy { it.address }.forEach {
            Log.d(TAG, "known device: name=${it.name} addr=${it.address}")
        }
        val already = known.firstOrNull { isOneWheel(it.name, null) }
        if (already != null) {
            Log.d(TAG, "Connecting to known board ${already.name} ${already.address}")
            emitStatus("connecting", "Connecting to ${already.name ?: already.address}…")
            gatt = already.connectGatt(context, false, gattCallback)
            call.resolve()
            return
        }

        val bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            call.reject("BLE scanner unavailable")
            return
        }
        scanner = bleScanner
        scanning = true
        loggedScans.clear()
        emitStatus("scanning", "Searching for board…")

        // No service filter: many OneWheel boards don't advertise the service
        // UUID, so we scan broadly and match by name / scan-record below.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner.startScan(null, settings, scanCallback)
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        call.resolve()
    }

    /** True if the advertised name / services look like a OneWheel board. */
    private fun isOneWheel(name: String?, serviceUuids: List<ParcelUuid>?): Boolean {
        val n = name?.lowercase().orEmpty()
        if (n.startsWith("ow") || n.contains("onewheel")) return true
        return serviceUuids?.any { it.uuid == SERVICE } == true
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val device: BluetoothDevice = result.device
            val name = device.name ?: result.scanRecord?.deviceName
            // Log every distinct device once so we can see what the board
            // actually advertises if matching fails.
            if (loggedScans.add(device.address)) {
                Log.d(
                    TAG,
                    "scan: name=$name addr=${device.address} services=${result.scanRecord?.serviceUuids}",
                )
            }
            if (!isOneWheel(name, result.scanRecord?.serviceUuids)) return
            scanning = false
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            scanner?.stopScan(this)
            Log.d(TAG, "Matched board $name ${device.address}")
            emitStatus("connecting", "Connecting to ${name ?: device.address}…")
            gatt = device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            mainHandler.removeCallbacks(scanTimeoutRunnable)
            Log.w(TAG, "Scan failed ($errorCode)")
            emitStatus("error", "Scan failed ($errorCode)")
        }
    }

    // ─────────────────────────────── GATT callback ──────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    emitStatus("connecting", "Discovering services…")
                    // Make sure our app is open on the watch so it can receive data.
                    scope.launch {
                        runCatching { sender.startAppOnTheWatch(APP_UUID) }
                            .onFailure { Log.w(TAG, "startAppOnTheWatch failed", it) }
                    }
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    sConnected = false
                    scope.launch { runCatching { sendToWatch(false, sBattery, sSpeedTenths) } }
                    emitStatus("disconnected", "Board disconnected")
                    emitUpdate()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(SERVICE)
            if (service == null) {
                emitStatus("error", "OneWheel service not found")
                return
            }
            // Step 1: read the firmware revision to decide whether to unlock.
            challenge.reset()
            keySent = false
            service.getCharacteristic(CHAR_FIRMWARE)?.let { g.readCharacteristic(it) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handleRead(g, c, c.value)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            handleRead(g, c, value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            handleChanged(g, c, c.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            c: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleChanged(g, c, value)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val charUuid = descriptor.characteristic.uuid
            if (geminiUnlock && charUuid == CHAR_SERIAL_READ) {
                // Step 3: trigger the challenge by writing the firmware char onto itself.
                val service = g.getService(SERVICE)
                service?.getCharacteristic(CHAR_FIRMWARE)?.let {
                    writeChar(g, it, firmwareValue ?: it.value ?: ByteArray(0))
                }
            } else {
                // Continue enabling the next data notification (battery, then rpm).
                notifyNext(g)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (c.uuid == CHAR_SERIAL_WRITE) {
                // Step 5: unlock response accepted — start streaming live data.
                emitStatus("connected", "Connected")
                startDataNotifications(g)
            }
        }
    }

    // ─────────────────────────── GATT step handlers ────────────────────────────

    @SuppressLint("MissingPermission")
    private fun handleRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        when (c.uuid) {
            CHAR_FIRMWARE -> {
                firmwareValue = value.copyOf()
                val fw = uint16(value)
                Log.d(TAG, "Firmware revision = $fw")
                if (fw >= GEMINI_FW_MIN) {
                    // Step 2: enable notifications on the UART serial-read characteristic.
                    geminiUnlock = true
                    emitStatus("connecting", "Unlocking board…")
                    val service = g.getService(SERVICE)
                    service?.getCharacteristic(CHAR_SERIAL_READ)?.let { enableNotify(g, it) }
                } else {
                    // Older firmware streams without a handshake.
                    geminiUnlock = false
                    emitStatus("connected", "Connected")
                    startDataNotifications(g)
                }
            }
            // Initial reads (board is idle and only notifies on change).
            CHAR_BATTERY -> {
                applyBattery(value)
                readNext(g)
            }
            CHAR_SPEED_RPM -> {
                applySpeed(value)
                readNext(g)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        when (c.uuid) {
            CHAR_SERIAL_READ -> {
                if (keySent) return
                challenge.write(value, 0, value.size)
                val inkey = challenge.toByteArray()
                if (inkey.size >= 20) {
                    keySent = true
                    respondToChallenge(g, inkey)
                }
            }
            CHAR_BATTERY -> applyBattery(value)
            CHAR_SPEED_RPM -> applySpeed(value)
        }
    }

    private fun applyBattery(value: ByteArray) {
        sBattery = parseBattery(value)
        sConnected = true
        Log.d(TAG, "battery = $sBattery%")
        pushUpdate(force = true)
    }

    private fun applySpeed(value: ByteArray) {
        val rpm = uint16(value)
        sSpeedTenths = (rpm * MPH_PER_RPM * 10.0).toInt().coerceIn(0, 65535)
        sConnected = true
        Log.d(TAG, "rpm = $rpm speedTenths = $sSpeedTenths")
        pushUpdate(force = false)
    }

    @SuppressLint("MissingPermission")
    private fun respondToChallenge(g: BluetoothGatt, inkey: ByteArray) {
        try {
            val part1 = inkey.copyOfRange(3, 19) // 16 bytes
            val md5 = MessageDigest.getInstance("MD5").digest(part1 + UNLOCK_SALT)
            val body = UNLOCK_PREFIX + md5 // 19 bytes
            var check = 0
            for (b in body) check = check xor b.toInt()
            val outkey = body + check.toByte() // 20 bytes

            val service = g.getService(SERVICE)
            service?.getCharacteristic(CHAR_SERIAL_WRITE)?.let { writeChar(g, it, outkey) }
            // Stop listening to the challenge channel.
            service?.getCharacteristic(CHAR_SERIAL_READ)?.let { g.setCharacteristicNotification(it, false) }
        } catch (e: Exception) {
            emitStatus("error", "Unlock failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDataNotifications(g: BluetoothGatt) {
        notifyQueue.clear()
        notifyQueue.add(CHAR_BATTERY)
        notifyQueue.add(CHAR_SPEED_RPM)
        notifyNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun notifyNext(g: BluetoothGatt) {
        val next = notifyQueue.poll()
        if (next == null) {
            // All notifications enabled — prime the UI with one read of each
            // value, since an idle board won't push a notification until
            // something changes.
            startInitialReads(g)
            return
        }
        val service = g.getService(SERVICE)
        service?.getCharacteristic(next)?.let { enableNotify(g, it) }
    }

    @SuppressLint("MissingPermission")
    private fun startInitialReads(g: BluetoothGatt) {
        readQueue.clear()
        readQueue.add(CHAR_BATTERY)
        readQueue.add(CHAR_SPEED_RPM)
        readNext(g)
    }

    @SuppressLint("MissingPermission")
    private fun readNext(g: BluetoothGatt) {
        val next = readQueue.poll() ?: return
        g.getService(SERVICE)?.getCharacteristic(next)?.let { g.readCharacteristic(it) }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val descriptor = c.getDescriptor(CCCD) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }
    }

    // ───────────────────────────────── Helpers ─────────────────────────────────

    private fun pushUpdate(force: Boolean) {
        emitUpdate()
        val now = System.currentTimeMillis()
        if (force || now - lastWatchSendMs >= 150) {
            lastWatchSendMs = now
            scope.launch { runCatching { deliverToWatch(sConnected, sBattery, sSpeedTenths) } }
        }
    }

    private suspend fun sendToWatch(connected: Boolean, battery: Int, speedTenths: Int): String {
        val data = mapOf(
            KEY_CONNECTED to PebbleDictionaryItem.UInt8(if (connected) 1 else 0),
            KEY_BATTERY to PebbleDictionaryItem.UInt8(battery.coerceIn(0, 100)),
            KEY_SPEED to PebbleDictionaryItem.UInt16(speedTenths.coerceIn(0, 65535)),
        )
        val result = sender.sendDataToPebble(APP_UUID, data).toString()
        Log.d(TAG, "sendToWatch connected=$connected battery=$battery speed=$speedTenths -> $result")
        return result
    }

    /**
     * Send to the watch, and if it reports a different app is open (or no app
     * running), launch our watch app by UUID and send again. The watch refuses
     * AppMessages unless our app is the one in the foreground.
     */
    private suspend fun deliverToWatch(connected: Boolean, battery: Int, speedTenths: Int): String {
        var result = sendToWatch(connected, battery, speedTenths)
        if (needsAppLaunch(result) && !launchingApp) {
            launchingApp = true
            try {
                Log.d(TAG, "Launching watch app $APP_UUID (watch had a different app open)")
                val launch = runCatching { sender.startAppOnTheWatch(APP_UUID).toString() }
                    .getOrElse { "error: ${it.message}" }
                Log.d(TAG, "startAppOnTheWatch -> $launch")
                // The watch takes a moment to bring the app to the foreground
                // and for the Core app's state to catch up, so retry a few times.
                for (attempt in 1..6) {
                    delay(1000)
                    result = sendToWatch(connected, battery, speedTenths)
                    if (!needsAppLaunch(result)) break
                    Log.d(TAG, "watch send retry $attempt -> $result")
                }
            } finally {
                launchingApp = false
            }
        }
        return result
    }

    private fun needsAppLaunch(result: String): Boolean =
        result.contains("DifferentApp", ignoreCase = true) ||
            result.contains("AppNotRunning", ignoreCase = true) ||
            result.contains("NotRunning", ignoreCase = true)

    private fun emitStatus(status: String, message: String) {
        val ev = JSObject()
        ev.put("status", status)
        ev.put("message", message)
        notifyListeners("boardStatus", ev)
    }

    private fun emitUpdate() {
        val ev = JSObject()
        ev.put("connected", sConnected)
        ev.put("battery", sBattery)
        ev.put("speed", sSpeedTenths)
        notifyListeners("boardUpdate", ev)
    }

    @SuppressLint("MissingPermission")
    private fun teardown(reason: String) {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        runCatching { if (scanning) scanner?.stopScan(scanCallback) }
        scanning = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        geminiUnlock = false
        keySent = false
        firmwareValue = null
        launchingApp = false
        challenge.reset()
        notifyQueue.clear()
        readQueue.clear()
        sConnected = false
        emitStatus("disconnected", reason)
        emitUpdate()
    }

    private fun parseBattery(value: ByteArray): Int {
        val raw = when {
            value.size >= 2 -> value[1].toInt() and 0xFF
            value.isNotEmpty() -> value[0].toInt() and 0xFF
            else -> 0
        }
        return raw.coerceIn(0, 100)
    }

    private fun uint16(value: ByteArray): Int {
        if (value.size < 2) return 0
        return ((value[0].toInt() and 0xFF) shl 8) or (value[1].toInt() and 0xFF)
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        teardown("Disconnected")
        sender.close()
    }
}
