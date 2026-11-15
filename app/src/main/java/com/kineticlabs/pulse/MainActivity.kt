package com.kineticlabs.pulse

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kineticlabs.pulse.ble.PulseBle
import com.kineticlabs.pulse.ui.KineticApp
import com.kineticlabs.pulse.ui.KineticTheme

class MainActivity : ComponentActivity() {
    private val vm: PulseViewModel by viewModels()

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.ble.start() }

    private val askBluetoothOn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.ble.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val keepOn by vm.keepScreenOn.collectAsState()
            LaunchedEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            KineticTheme {
                KineticApp(
                    vm = vm,
                    onRequestPermissions = { askPermissions.launch(PulseBle.requiredPermissions) },
                    onEnableBluetooth = {
                        try {
                            askBluetoothOn.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        } catch (_: SecurityException) {
                            askPermissions.launch(PulseBle.requiredPermissions)
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (vm.ble.hasPermissions()) vm.ble.start()
        else askPermissions.launch(PulseBle.requiredPermissions)
    }
}
