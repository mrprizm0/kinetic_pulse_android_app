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

    // location for ride tracking + spots; notifications for the "ride in progress" note
    private val askLocation =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refreshPermissions() }
    private val locationPermissions: Array<String> = buildList {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= 33) add(android.Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

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
                    onRequestLocation = { askLocation.launch(locationPermissions) },
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
        vm.refreshPermissions()  // may have changed in system settings
        if (vm.ble.hasPermissions()) vm.ble.start()
        else askPermissions.launch(PulseBle.requiredPermissions)
    }
}
