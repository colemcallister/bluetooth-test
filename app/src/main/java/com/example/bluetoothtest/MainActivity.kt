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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*


class MainActivity : AppCompatActivity() {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bluetoothService : BluetoothLeService? = null
    private var selectedDeviceAddress: String? = null

    // Stops scanning after 10 seconds.
    private val SCAN_PERIOD: Long = 10000

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

    private val gattUpdateReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothLeService.ACTION_GATT_CONNECTED -> {
                    println("Connected to the gatt!!! activity knows")
                }
                BluetoothLeService.ACTION_GATT_DISCONNECTED -> {
                    println("disconnected from the gatt!!! activity knows")
                }
            }
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

    // Code to manage Service lifecycle.
    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            componentName: ComponentName,
            service: IBinder
        ) {
            bluetoothService = (service as BluetoothLeService.LocalBinder).getService()
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

    private var bluetoothGattServer: BluetoothGattServer? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()
    private val SERVICE_UUID: UUID = UUID.fromString("5AE3B36E-16DB-4732-B2FB-B76CCFE30F89")
    private val CONTENT_CHARACTERISTIC_UUID: UUID = UUID.fromString("4AED2DB1-2537-4D7B-A2AA-46708B4F7563")

    //TODO: What is this? Is this the same as iOS content share uuid?
    private val CONTENT_CONFIG_DESCRIPTION_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
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
            val now = System.currentTimeMillis()
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

    fun startServer() {
        bluetoothGattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        bluetoothGattServer?.addService(createGattService())
    }

    fun createGattService(): BluetoothGattService {
        val service = BluetoothGattService(SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Current Time characteristic
        val contentCharacteristic = BluetoothGattCharacteristic(CONTENT_CHARACTERISTIC_UUID,
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



















    val MESSAGE_READ: Int = 0
    val MESSAGE_WRITE: Int = 1
    val MESSAGE_TOAST: Int = 2

    /**
     * Connect code
     */
    private val handler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_READ -> {
                    val passedMsg = String(msg.obj as ByteArray, 0, msg.arg1)
                    val received = "We got a message: $passedMsg"
                    println(received)
                    Toast.makeText(
                        this@MainActivity,
                        received,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                MESSAGE_WRITE -> {
                    println("message sent :)")
                }
                else -> {
                    println("ERROR: How did we get here?")
                }
            }
        }
    }

    inner class ConnectedThread(private val mmSocket: BluetoothSocket) : Thread() {

        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        override fun run() {
            var numBytes: Int // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                // Read from the InputStream.
                numBytes = try {
                    mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.d("MY_APP_DEBUG_TAG", "Input stream was disconnected", e)
                    break
                }

                // Send the obtained bytes to the UI activity.
                val readMsg = handler.obtainMessage(
                    MESSAGE_READ, numBytes, -1,
                    mmBuffer)
                readMsg.sendToTarget()
            }
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e("MY_APP_DEBUG_TAG", "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                val writeErrorMsg = handler.obtainMessage(MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString("toast", "Couldn't send data to the other device")
                }
                writeErrorMsg.data = bundle
                handler.sendMessage(writeErrorMsg)
                return
            }

            // Share the sent message with the UI activity.
            val writtenMsg = handler.obtainMessage(
                MESSAGE_WRITE, -1, -1, mmBuffer)
            writtenMsg.sendToTarget()
        }

        // Call this method from the main activity to shut down the connection.
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
                Log.e("MY_APP_DEBUG_TAG", "Could not close the connect socket", e)
            }
        }
    }

    private val BT_UUID = UUID.fromString("5AE3B36E-16DB-4732-B2FB-B76CCFE30F89")

    private inner class ConnectThread(device: BluetoothDevice) : Thread() {

        private var bluetoothAdapter: BluetoothAdapter? = null

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device.createRfcommSocketToServiceRecord(BT_UUID)
        }

        init {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }

        public override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter?.cancelDiscovery()

            mmSocket?.let { socket ->
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                try {
                  socket.connect()
                } catch (e: IOException) {
                    println("Socket's connect() method failed")
                    return
                }

                // The connection attempt succeeded. Perform work associated with
                // the connection in a separate thread.
                println("Client connected to server socket! Good job!")
                connectedThread?.cancel()
                connectedThread = ConnectedThread(socket)

                connectedThread?.start()
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    private inner class AcceptThread : Thread() {

        private val CONNECT_THREAD_NAME = "We have a name"
        private var bluetoothAdapter: BluetoothAdapter? = null

        init {
            bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        }

        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(CONNECT_THREAD_NAME, BT_UUID)
        }

        override fun run() {
            // Keep listening until exception occurs or a socket is returned.
            var shouldLoop = true
            while (shouldLoop) {
                val socket: BluetoothSocket? = try {
                    mmServerSocket?.accept()
                } catch (e: IOException) {
                    println("Socket's accept() method failed")
                    Log.e(TAG, "Socket's accept() method failed", e)
                    shouldLoop = false
                    null
                }
                socket?.also {
                    println("you are connected from the accept thread! Congrats!")
                    connectedThread?.cancel()
                    connectedThread = ConnectedThread(socket)
                    connectedThread?.start()

                    mmServerSocket?.close()
                    shouldLoop = false
                }
            }
        }

        // Closes the connect socket and causes the thread to finish.
        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    /**
     * End connect code
     */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val gattServiceIntent = Intent(this, BluetoothLeService::class.java)
        bindService(gattServiceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        bluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothAdapter == null) {
            println("No bluetooth")
            // Device doesn't support Bluetooth
        } else {
            println("bluetooth")
        }


        // Register for broadcasts when a device is discovered.
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        registerReceiver(receiver, filter)

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
    }


    private var requestBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            //granted
        } else {
            //deny
        }
    }

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null

    private var broadcastBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        //TODO: look at result code
        val testStop = "test"
    }

    private val requestMultiplePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d("test006", "${it.key} = ${it.value}")
            }
        }

    // Create a BroadcastReceiver for ACTION_FOUND.
    private val receiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            when(action) {
                BluetoothDevice.ACTION_FOUND -> {
                    // Discovery has found a device. Get the BluetoothDevice
                    // object and its info from the Intent.
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val deviceName = device?.name
                    val deviceHardwareAddress = device?.address // MAC address
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(gattUpdateReceiver)
    }

    private fun makeGattUpdateIntentFilter(): IntentFilter? {
        return IntentFilter().apply {
            addAction(BluetoothLeService.ACTION_GATT_CONNECTED)
            addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED)
        }
    }

    override fun onResume() {
        super.onResume()

        registerReceiver(gattUpdateReceiver, makeGattUpdateIntentFilter())

        if (acceptThread == null) {
            acceptThread = AcceptThread()
        }

        findViewById<Button>(R.id.lookForBluetoothButton).setOnClickListener {
            lookForBluetooth()
        }

        findViewById<Button>(R.id.broadcastBluetoothButton).setOnClickListener {
            broadcastBluetooth()
        }

        findViewById<Button>(R.id.openServerSocketButton).setOnClickListener {
            acceptThread?.start()
        }

        findViewById<Button>(R.id.connectAsClientSocketButton).setOnClickListener {
            connectThread?.cancel()

            //TODO: Find mac address
            val device = bluetoothAdapter?.getRemoteDevice("B4:F1:DA:2B:F4:E2")
            device?.let {
                println("creating connect thread")
                connectThread = ConnectThread(device)
                connectThread?.start()
            }
        }

        findViewById<Button>(R.id.sendMessageButton).setOnClickListener {
            connectedThread?.write("Hello world".toByteArray())
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

    private fun broadcastBluetooth() {
        val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        broadcastBluetooth.launch(discoverableIntent)
    }

    private fun lookForBluetooth() {


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
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()

            val pairedDevices: Set<BluetoothDevice>? = bluetoothAdapter?.bondedDevices
            pairedDevices?.forEach { device ->
                val deviceName = device.name
                val deviceHardwareAddress = device.address // MAC address
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
            if (bluetoothAdapter?.isDiscovering == true) {
                bluetoothAdapter?.cancelDiscovery()
            }
            bluetoothAdapter?.startDiscovery()
        } else {
            println("We don't have permission")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        acceptThread?.cancel()
        connectThread?.cancel()
        connectedThread?.cancel()

        // Don't forget to unregister the ACTION_FOUND receiver.
        unregisterReceiver(receiver)
    }
}