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

    private var bluetoothGattServer: BluetoothGattServer? = null

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
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    private fun startServer() {
        bluetoothGattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        bluetoothGattServer?.addService(createGattService())
    }

    private fun createGattService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Current Time characteristic
        val contentCharacteristic = BluetoothGattCharacteristic(
            CONTENT_CHARACTERISTIC_UUID,
            //Read-only characteristic, supports notifications
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ)

        //TODO: Is descriptor needed?
        val configDescriptor = BluetoothGattDescriptor(SERVICE_UUID,
            //Read/write descriptor
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        contentCharacteristic.addDescriptor(configDescriptor)


        service.addCharacteristic(contentCharacteristic)

        return service
    }

    private fun startAdvertising() {
        val bluetoothLeAdvertiser: BluetoothLeAdvertiser? =
            bluetoothManager?.adapter?.bluetoothLeAdvertiser

        bluetoothLeAdvertiser?.let {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            it.startAdvertising(settings, data, advertiseCallback)
        } ?: Log.w(TAG, "Failed to create advertiser")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "LE Advertise Started.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "LE Advertise Failed: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                println("BluetoothDevice CONNECTED: $device")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                println("BluetoothDevice DISCONNECTED: $device")
                //Remove device from any active subscriptions
                registeredDevices.remove(device)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                 characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                CONTENT_CHARACTERISTIC_UUID -> {
                    Log.i(TAG, "Read CurrentTime")
                    bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        "send something else".toByteArray())
                }
                else -> {
                    // Invalid characteristic
                    Log.w(TAG, "Invalid Characteristic Read: " + characteristic.uuid)
                    bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null)
                }
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                             descriptor: BluetoothGattDescriptor) {
            if (CONTENT_CONFIG_DESCRIPTION_UUID == descriptor.uuid) {
                Log.d(TAG, "Config descriptor read")
                val returnValue = if (registeredDevices.contains(device)) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                bluetoothGattServer?.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    returnValue)
            } else {
                Log.w(TAG, "Unknown descriptor read request")
                bluetoothGattServer?.sendResponse(device,
                    requestId,
                    BluetoothGatt.GATT_FAILURE,
                    0, null)
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int,
                                              descriptor: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean,
                                              offset: Int, value: ByteArray) {
            if (CONTENT_CONFIG_DESCRIPTION_UUID == descriptor.uuid) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Subscribe device to notifications: $device")
                    registeredDevices.add(device)
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Unsubscribe device from notifications: $device")
                    registeredDevices.remove(device)
                }

                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0, null)
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request")
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0, null)
                }
            }
        }
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

        //TODO: What is this? Is this the same as iOS content share uuid?
        val CONTENT_CONFIG_DESCRIPTION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Stops scanning after 10 seconds.
        val SCAN_PERIOD: Long = 10000
    }
}