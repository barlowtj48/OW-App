package com.owapp.companion

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

/**
 * PebbleKit2 listener service.
 *
 * This MUST exist (and be declared in AndroidManifest.xml with the
 * io.rebble.pebblekit2.RECEIVE_DATA_FROM_WATCH intent filter) for the selected
 * Pebble phone app (Core Devices app) to be able to send AppMessages to our
 * watchapp at all.
 *
 * When our watchapp opens on the watch, the Pebble app calls `sendOnAppOpened`,
 * which binds to this service. Only once that bind succeeds does the Pebble app
 * register a PebbleKit2 companion session for our UUID. Without that session,
 * every `sendDataToPebble` call fails with FailedDifferentAppOpen, even though
 * our app is the foreground app on the watch.
 *
 * We are a one-way (phone -> watch) bridge, so we don't act on inbound data; we
 * only need this service to be present and bindable.
 */
class OwPebbleListenerService : BasePebbleListenerService() {
    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        Log.d(TAG, "listener received from $watchappUUID on $watch: $data")
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "listener: app $watchappUUID opened on $watch (session established)")
    }

    override fun onAppClosed(watchappUUID: UUID, watch: WatchIdentifier) {
        Log.d(TAG, "listener: app $watchappUUID closed on $watch")
    }

    companion object {
        private const val TAG = "OwBridge"
    }
}
