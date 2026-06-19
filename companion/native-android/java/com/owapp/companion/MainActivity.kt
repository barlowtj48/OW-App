package com.owapp.companion

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Register the custom plugin BEFORE the web view loads.
        registerPlugin(OwPebbleBridgePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
