package com.owapp.companion

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Capacitor bridge that forwards OneWheel data to the Pebble watch via
 * PebbleKit Android 2.
 *
 * JS usage:
 *   const OwPebbleBridge = Capacitor.registerPlugin("OwPebbleBridge");
 *   await OwPebbleBridge.send({ connected: true, battery: 80, speed: 124 });
 */
@CapacitorPlugin(name = "OwPebbleBridge")
class OwPebbleBridgePlugin : Plugin() {

    companion object {
        // Must match the watch app's UUID (package.json).
        private val APP_UUID = UUID.fromString("44bd15cb-2134-41d8-9af8-cbda0d9dd4d9")

        // Must match the watch's generated MESSAGE_KEY_* numbers.
        // The watch logs them at boot ("MSG KEYS: ...") — read with `pebble logs`
        // and update these if they differ.
        private const val KEY_CONNECTED = 10000u
        private const val KEY_BATTERY = 10001u
        private const val KEY_SPEED = 10002u
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender by lazy { DefaultPebbleSender() }

    @PluginMethod
    fun send(call: PluginCall) {
        val connected = if (call.getBoolean("connected", false) == true) 1 else 0
        val battery = (call.getInt("battery", 0) ?: 0).coerceIn(0, 100)
        val speed = (call.getInt("speed", 0) ?: 0).coerceIn(0, 65535) // mph * 10

        val data = mapOf(
            KEY_CONNECTED to PebbleDictionaryItem.UInt8(connected),
            KEY_BATTERY to PebbleDictionaryItem.UInt8(battery),
            KEY_SPEED to PebbleDictionaryItem.UInt16(speed),
        )

        scope.launch {
            try {
                val result = sender.sendDataToPebble(APP_UUID, data)
                val ret = JSObject()
                ret.put("result", result.toString())
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("send failed: ${e.message}", e)
            }
        }
    }

    override fun handleOnDestroy() {
        super.handleOnDestroy()
        sender.close()
    }
}
