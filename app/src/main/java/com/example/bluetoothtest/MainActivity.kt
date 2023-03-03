package com.example.bluetoothtest

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.ContentValues.TAG
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.bluetoothtest.BluetoothLEConnectService.Companion.INTENT_BLUETOOTH_EXTRA_DATA
import java.util.*


class MainActivity : AppCompatActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bluetoothService : BluetoothLEConnectService? = null
    private var selectedDeviceAddress: String? = null

    private val handler = Handler()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //Create the BluetoothLEConnectService
        val gattServiceIntent = Intent(this, BluetoothLEConnectService::class.java)
        bindService(gattServiceIntent, bluetoothLEConnectServiceConnection, Context.BIND_AUTO_CREATE)

        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onResume() {
        super.onResume()

        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter())

        findViewById<Button>(R.id.setupCorrectPermissionsButton).setOnClickListener {
            requestPermissions()
        }

        findViewById<Button>(R.id.leScanButton).setOnClickListener {
            scanLeDevice()
        }

        findViewById<Button>(R.id.startLeServer).setOnClickListener {
            startAdvertising()
            startServer()
        }

        findViewById<Button>(R.id.connectToLeServer).setOnClickListener {
            if (bluetoothService != null) {
                val result = bluetoothService!!.connect(selectedDeviceAddress ?: "")
                println("Connect request result=$result")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }

    /*******************************************************************************************
     *
     * 1. Request correct permissions
     *
     ********************************************************************************************/

    private fun requestPermissions() {

        // ask for / Allows permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 +
            requestMultiplePermissions.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            // Android 11 -
            requestMultiplePermissions.launch(arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN))
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            //Turns bluetooth on if it's off
            if (bluetoothAdapter?.isEnabled == false) {
                bluetoothAdapter?.enable()
            }

        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    (this as Activity),
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ),
                    1
                )
            }

            //Turns bluetooth on if it's off
            if (bluetoothAdapter?.isEnabled == false) {
                bluetoothAdapter?.enable()
            }
        } else {
            println("We don't have permission")
        }
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d("test006", "${it.key} = ${it.value}")
            }
        }

    /*******************************************************************************************
     *
     * 2. Create GATT service
     *
     ********************************************************************************************/

    private fun startServer() {
        bluetoothService?.startServer()
    }

    private fun startAdvertising() {
        bluetoothService?.startAdvertising()
    }

    /*******************************************************************************************
     *
     * 3. Scan
     *
     ********************************************************************************************/

    private fun scanLeDevice() {
        if (!scanning) { // Stops scanning after a pre-defined scan period.
            handler.postDelayed({
                scanning = false
                bluetoothLeScanner?.stopScan(leScanCallback)
            }, SCAN_PERIOD)
            scanning = true

            val filterList = listOf<ScanFilter>(
                ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
            )

            bluetoothLeScanner?.startScan(filterList, ScanSettings.Builder().build(), leScanCallback)
        } else {
            scanning = false
            bluetoothLeScanner?.stopScan(leScanCallback)
        }
    }

    // Device scan callback.
    private val leScanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            selectedDeviceAddress = result.device.address

            println("device found: ${result.device.name}")
        }
    }

    /*******************************************************************************************
     *
     * 4. Request data from service
     *
     ********************************************************************************************/

    private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothLEConnectService.ACTION_GATT_CONNECTED -> {
                    // Connected to the GATT service
                }
                BluetoothLEConnectService.ACTION_GATT_DISCONNECTED -> {
                    // Disconnected from the GATT service
                }
                BluetoothLEConnectService.ACTION_GATT_SERVICES_DISCOVERED -> {
                    // Show all the supported services and characteristics on the user interface.
                    getDataByGattServiceForCharacteristic(bluetoothService?.getSupportedGattServices())
                }
                BluetoothLEConnectService.ACTION_GATT_DATA_AVAILABLE -> {
                    dataVerification(intent)
                }
            }
        }
    }

    // Check all of the services and make sure it matched our UUID
    private fun getDataByGattServiceForCharacteristic(gattServices: List<BluetoothGattService?>?) {
        if (gattServices == null) return

        gattServices.forEach { gattService ->
            if (gattService?.uuid == SERVICE_UUID) {
                bluetoothService?.readCharacteristic(gattService.getCharacteristic(CONTENT_CHARACTERISTIC_UUID))
                return
            }
        }
    }

    private fun dataVerification(intent: Intent) {
        val data = intent.getStringExtra(INTENT_BLUETOOTH_EXTRA_DATA)
        //TODO do something with the data here
        print("We were successful. The data is: $data")
    }

    // Code to manage Service lifecycle.
    private val bluetoothLEConnectServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            bluetoothService = (service as BluetoothLEConnectService.LocalBinder).getService()
            bluetoothService?.let { bluetooth ->
                if (!bluetooth.initialize()) {
                    Log.e(TAG, "Unable to initialize Bluetooth")
                    finish()
                }
                // perform device connection
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            bluetoothService = null
        }
    }

    private fun makeGattUpdateIntentFilter(): IntentFilter? {
        return IntentFilter().apply {
            addAction(BluetoothLEConnectService.ACTION_GATT_CONNECTED)
            addAction(BluetoothLEConnectService.ACTION_GATT_DISCONNECTED)
            addAction(BluetoothLEConnectService.ACTION_GATT_SERVICES_DISCOVERED)
            addAction(BluetoothLEConnectService.ACTION_GATT_DATA_AVAILABLE)
        }
    }

    /*******************************************************************************************
     *
     * END
     *
     ********************************************************************************************/

    companion object {
        val CONTENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("4AED2DB1-2537-4D7B-A2AA-46708B4F7563")
        val SERVICE_UUID: UUID = UUID.fromString("5AE3B36E-16DB-4732-B2FB-B76CCFE30F89")

        // Stops scanning after 10 seconds.
        val SCAN_PERIOD: Long = 10000
    }
}