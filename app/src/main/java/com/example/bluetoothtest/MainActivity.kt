package com.example.bluetoothtest

import android.Manifest
import android.app.Activity
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
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

        val bluetoothManager: BluetoothManager = getSystemService(BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager.getAdapter()
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

    override fun onResume() {
        super.onResume()

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