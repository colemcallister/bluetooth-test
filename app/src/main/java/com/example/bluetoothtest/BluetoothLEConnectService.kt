package com.example.bluetoothtest

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.ContentValues
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import com.example.bluetoothtest.MainActivity.Companion.CONTENT_CHARACTERISTIC_UUID

class BluetoothLEConnectService : Service() {

    private var connectionState = STATE_DISCONNECTED
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val binder = LocalBinder()

    private var bluetoothGattServer: BluetoothGattServer? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        close()
        return super.onUnbind(intent)
    }

    fun getSupportedGattServices(): List<BluetoothGattService?>? {
        return bluetoothGatt?.services
    }

    fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        bluetoothGatt?.readCharacteristic(characteristic) ?:
        run {
            // BluetoothGatt not initialized
            return
        }
    }

    fun initialize(): Boolean {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothManager = getSystemService(BluetoothManager::class.java)
        if (bluetoothAdapter == null || bluetoothManager == null) {
            return false
        }
        return true
    }

    // Connect based on the MAC address of the identified LE service
    fun connect(address: String): Boolean {
        bluetoothAdapter?.let { adapter ->
            try {
                val device = adapter.getRemoteDevice(address)
                // connect to the GATT server on the device
                bluetoothGatt = device.connectGatt(this, false, bluetoothGattCallback)
                return true
            } catch (exception: IllegalArgumentException) {
                // Device not found with provided address. Unable to connect
                return false
            }
        } ?: run {
            // BluetoothAdapter not initialized
            return false
        }
    }

    private val bluetoothGattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // successfully connected to the GATT Server
                connectionState = STATE_CONNECTED
                gatt.discoverServices()
                broadcastUpdate(ACTION_GATT_CONNECTED)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // disconnected from the GATT Server
                connectionState = STATE_DISCONNECTED
                broadcastUpdate(ACTION_GATT_DISCONNECTED)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED)
            } else {
                // unsupported status
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                broadcastUpdate(ACTION_GATT_DATA_AVAILABLE, characteristic)
            }
        }
    }

    private fun broadcastUpdate(action: String) {
        val intent = Intent(action)
        sendBroadcast(intent)
    }

    private fun broadcastUpdate(action: String, characteristic: BluetoothGattCharacteristic) {
        val intent = Intent(action)
        when (characteristic.uuid) {
            CONTENT_CHARACTERISTIC_UUID -> {
                val dataValue = characteristic.getStringValue(0)
                intent.putExtra(INTENT_BLUETOOTH_EXTRA_DATA, dataValue)
            }
            else -> {
                // For all other profiles, writes the data formatted in HEX.
                val data: ByteArray? = characteristic.value
                if (data?.isNotEmpty() == true) {
                    val hexString: String = data.joinToString(separator = " ") {
                        String.format("%02X", it)
                    }
                    intent.putExtra(INTENT_BLUETOOTH_EXTRA_DATA, "$data\n$hexString")
                }
            }
        }
        sendBroadcast(intent)
    }

    /*********
     *
     */

    fun startServer() {
        bluetoothGattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        bluetoothGattServer?.addService(createGattService())

    }

    fun startAdvertising() {
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
                .addServiceUuid(ParcelUuid(MainActivity.SERVICE_UUID))
                .build()

            it.startAdvertising(settings, data, advertiseCallback)
        }
    }

    private fun createGattService(): BluetoothGattService {
        val service = BluetoothGattService(
            MainActivity.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Current Time characteristic
        val contentCharacteristic = BluetoothGattCharacteristic(
            CONTENT_CHARACTERISTIC_UUID,
            //Read-only characteristic, supports notifications
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ)

        val configDescriptor = BluetoothGattDescriptor(
            MainActivity.SERVICE_UUID,
            //Read/write descriptor
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
        contentCharacteristic.addDescriptor(configDescriptor)


        service.addCharacteristic(contentCharacteristic)

        return service
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
                    Log.i(ContentValues.TAG, "Read CurrentTime")
                    bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_SUCCESS,
                        0,
                        "send something else".toByteArray())
                }
                else -> {
                    // Invalid characteristic
                    Log.w(ContentValues.TAG, "Invalid Characteristic Read: " + characteristic.uuid)
                    bluetoothGattServer?.sendResponse(device,
                        requestId,
                        BluetoothGatt.GATT_FAILURE,
                        0,
                        null)
                }
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(ContentValues.TAG, "LE Advertise Started.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(ContentValues.TAG, "LE Advertise Failed: $errorCode")
        }
    }

    /*********
     * end
     */

    private fun close() {
        bluetoothGatt?.let { gatt ->
            gatt.close()
            bluetoothGatt = null
        }
    }

    inner class LocalBinder : Binder() {
        fun getService() : BluetoothLEConnectService {
            return this@BluetoothLEConnectService
        }
    }

    companion object {
        const val ACTION_GATT_CONNECTED =
            "bluetooth.le.ACTION_GATT_CONNECTED"
        const val ACTION_GATT_DISCONNECTED =
            "bluetooth.le.ACTION_GATT_DISCONNECTED"
        const val ACTION_GATT_SERVICES_DISCOVERED =
            "bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED"
        const val ACTION_GATT_DATA_AVAILABLE =
            "bluetooth.le.ACTION_GATT_DATA_AVAILABLE"

        const val INTENT_BLUETOOTH_EXTRA_DATA =
            "bluetooth.le.INTENT_BLUETOOTH_EXTRA_DATA"

        private const val STATE_DISCONNECTED = 0
        private const val STATE_CONNECTED = 2

    }
}
