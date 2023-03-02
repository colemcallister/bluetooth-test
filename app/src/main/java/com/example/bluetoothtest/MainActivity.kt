package com.example.bluetoothtest

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
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
import androidx.fragment.app.FragmentActivity
import com.example.bluetoothtest.BTCommService.Companion.STATE_CONNECTED
import com.example.bluetoothtest.BTCommService.Companion.STATE_NONE
import com.google.android.material.internal.ContextUtils.getActivity


class MainActivity : AppCompatActivity() {
    private var mConnectedDeviceName: String = ""

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

    private var broadcastBluetooth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        //TODO: look at result code
        val testStop = "test"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null

    private var btService: BTCommService? = null

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

        findViewById<Button>(R.id.lookForBluetoothButton).setOnClickListener {
            lookForBluetooth()
        }

        findViewById<Button>(R.id.broadcastBluetoothButton).setOnClickListener {
            broadcastBluetooth()
        }

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            connectDevice("B4:F1:DA:2B:F4:E2")
            // Pixel 2 MAC
        }

        btService?.let {
            if (it.mState == STATE_NONE) {
                btService?.start()
            }
        }
    }

    override fun onStart() {
        super.onStart()

        btService = BTCommService(this, mHandler)
    }

    private fun sendMessage(msg: String) {
        if (btService?.mState != STATE_CONNECTED) {
            println("mState is NOT_CONNECTED")
            return
        }

        btService?.write(msg.toByteArray())
    }

    private fun connectDevice(address: String) {

        val device = bluetoothAdapter?.getRemoteDevice(address)
        device?.let {
            mConnectedDeviceName = it.name
            btService?.connect(device, false)
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

        // Don't forget to unregister the ACTION_FOUND receiver.
        unregisterReceiver(receiver)
        btService?.stop()
    }


    private val mHandler: Handler = object : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                Constants.MESSAGE_STATE_CHANGE -> when (msg.arg1) {
                    STATE_CONNECTED -> {
                        println("title_connected_to $mConnectedDeviceName")
                    }
                    BTCommService.STATE_CONNECTING -> println("title_connecting")
                    BTCommService.STATE_LISTEN, STATE_NONE -> println("title_not_connected")
                }
                Constants.MESSAGE_WRITE -> {
                    val writeBuf = msg.obj as ByteArray
                    // construct a string from the buffer
                    val writeMessage = String(writeBuf)
//                    mConversationArrayAdapter.add("Me:  $writeMessage")
                }
                Constants.MESSAGE_READ -> {
                    val readBuf = msg.obj as ByteArray
                    // construct a string from the valid bytes in the buffer
                    val readMessage = String(readBuf, 0, msg.arg1)
//                    mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage)
                }
                Constants.MESSAGE_DEVICE_NAME -> {
                    // save the connected device's name
//                    mConnectedDeviceName = msg.data.getString(Constants.DEVICE_NAME)
//                    if (null != activity) {
//                        Toast.makeText(
//                            activity, "Connected to "
//                                    + mConnectedDeviceName, Toast.LENGTH_SHORT
//                        ).show()
//                    }
                    println("Message device name: $mConnectedDeviceName")
                }
                Constants.MESSAGE_TOAST -> {
//                    Toast.makeText(
//                        this, msg.data.getString(Constants.TOAST),
//                        Toast.LENGTH_SHORT
//                    ).show()
                    println("very sad toast: $mConnectedDeviceName")
                }
            }
        }
    }

}