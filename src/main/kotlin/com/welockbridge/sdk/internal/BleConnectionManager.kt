package com.welockbridge.sdk.internal

import android.bluetooth.BluetoothDevice as AndroidBluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Internal BLE Connection Manager
 * 
 * Handles low-level BLE GATT operations including:
 * - Connection management
 * - Service and characteristic discovery
 * - Data transmission with proper callback handling
 * - Notification subscription
 * 
 * This class is internal to the SDK and should not be accessed directly by clients.
 */
@Suppress("MissingPermission")
internal class BleConnectionManager(
    private val context: Context,
    private val device: AndroidBluetoothDevice
) {
    companion object {
        private const val TAG = "WeLockBridge.BLE"
        private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val CONNECTION_TIMEOUT_MS = 15000L
        private const val WRITE_TIMEOUT_MS = 5000L
        private const val MAX_PACKET_SIZE = 20
        private const val CHUNK_DELAY_MS = 100L
    }
    
    private var bluetoothGatt: BluetoothGatt? = null
    private val writeMutex = Mutex()
    
    private val _dataReceived = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val dataReceived: Flow<ByteArray> = _dataReceived
    
    private var onConnectionStateChange: ((Boolean, String?) -> Unit)? = null
    private var writeCompletionDeferred: CompletableDeferred<Result<Unit>>? = null
    private var connectionDeferred: CompletableDeferred<Result<Unit>>? = null
    
    private data class CharacteristicInfo(
        val serviceUuid: UUID,
        val writeUuid: UUID?,
        val notifyUuid: UUID?,
        val properties: Int
    )
    private var discoveredCharacteristics: CharacteristicInfo? = null
    
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            Log.d(TAG, "Connection state changed: status=$status, newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server")
                    bluetoothGatt = gatt
                    gatt?.discoverServices()
                    onConnectionStateChange?.invoke(true, null)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server")
                    onConnectionStateChange?.invoke(false, "Disconnected (status: $status)")
                    connectionDeferred?.complete(Result.failure(Exception("Connection lost")))
                }
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            Log.d(TAG, "Services discovered: status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                discoverCharacteristics(gatt)
                connectionDeferred?.complete(Result.success(Unit))
            } else {
                connectionDeferred?.complete(Result.failure(Exception("Service discovery failed: $status")))
            }
        }
        
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            val result = if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Write completed successfully")
                Result.success(Unit)
            } else {
                Log.e(TAG, "Write failed with status: $status")
                Result.failure(Exception("Write failed with status: $status"))
            }
            writeCompletionDeferred?.complete(result)
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.d(TAG, "Characteristic changed (API 33+): ${value.size} bytes")
            _dataReceived.tryEmit(value)
        }
        
        @Deprecated("Deprecated in API level 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            characteristic?.value?.let { value ->
                Log.d(TAG, "Characteristic changed (Legacy): ${value.size} bytes")
                _dataReceived.tryEmit(value)
            }
        }
    }
    
    private fun discoverCharacteristics(gatt: BluetoothGatt?) {
        gatt ?: return
        
        Log.d(TAG, "Discovering characteristics...")
        
        val knownServices = listOf(
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            "8e400001-f315-4f60-9fb8-838830daea50",
            "0000fff0-0000-1000-8000-00805f9b34fb",
            "0000ffe0-0000-1000-8000-00805f9b34fb"
        )
        
        for (service in gatt.services) {
            val serviceUuidStr = service.uuid.toString().lowercase()
            Log.d(TAG, "Found service: $serviceUuidStr")
            
            var writeChar: BluetoothGattCharacteristic? = null
            var notifyChar: BluetoothGattCharacteristic? = null
            
            for (char in service.characteristics) {
                val props = char.properties
                val charUuid = char.uuid.toString()
                
                Log.d(TAG, "  Characteristic: $charUuid, props=$props")
                
                if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    writeChar = char
                }
                
                if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    notifyChar = char
                }
            }
            
            if (knownServices.any { serviceUuidStr.startsWith(it.take(8)) } && 
                (writeChar != null || notifyChar != null)) {
                discoveredCharacteristics = CharacteristicInfo(
                    serviceUuid = service.uuid,
                    writeUuid = writeChar?.uuid,
                    notifyUuid = notifyChar?.uuid,
                    properties = writeChar?.properties ?: 0
                )
                Log.d(TAG, "Selected service: ${service.uuid}")
                Log.d(TAG, "  Write: ${writeChar?.uuid}")
                Log.d(TAG, "  Notify: ${notifyChar?.uuid}")
                return
            }
        }
        
        for (service in gatt.services) {
            for (char in service.characteristics) {
                val props = char.properties
                if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    discoveredCharacteristics = CharacteristicInfo(
                        serviceUuid = service.uuid,
                        writeUuid = char.uuid,
                        notifyUuid = char.uuid,
                        properties = props
                    )
                    Log.d(TAG, "Fallback service: ${service.uuid}")
                    return
                }
            }
        }
    }
    
    suspend fun connect(): Result<Unit> {
        Log.d(TAG, "Connecting to ${device.address}...")
        
        connectionDeferred = CompletableDeferred()
        
        return try {
            bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, AndroidBluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
            
            withTimeout(CONNECTION_TIMEOUT_MS) {
                connectionDeferred?.await() ?: Result.failure(Exception("Connection cancelled"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
            connectionDeferred = null
            Result.failure(e)
        }
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        discoveredCharacteristics = null
    }
    
    fun getCharacteristicInfo(): Triple<UUID, UUID, UUID>? {
        val info = discoveredCharacteristics ?: return null
        val writeUuid = info.writeUuid ?: return null
        val notifyUuid = info.notifyUuid ?: writeUuid
        return Triple(info.serviceUuid, writeUuid, notifyUuid)
    }
    
    suspend fun writeData(
        serviceUuid: UUID,
        characteristicUuid: UUID,
        data: ByteArray,
        useWriteNoResponse: Boolean = false
    ): Result<Unit> = writeMutex.withLock {
        val gatt = bluetoothGatt ?: return@withLock Result.failure(Exception("Not connected"))
        
        val service = gatt.getService(serviceUuid)
            ?: return@withLock Result.failure(Exception("Service not found: $serviceUuid"))
        
        val characteristic = service.getCharacteristic(characteristicUuid)
            ?: return@withLock Result.failure(Exception("Characteristic not found: $characteristicUuid"))
        
        if (data.size <= MAX_PACKET_SIZE) {
            return@withLock writeSingleChunk(gatt, characteristic, data, useWriteNoResponse)
        }
        
        var offset = 0
        var chunkNum = 0
        val totalChunks = (data.size + MAX_PACKET_SIZE - 1) / MAX_PACKET_SIZE
        
        while (offset < data.size) {
            val chunkSize = minOf(MAX_PACKET_SIZE, data.size - offset)
            val chunk = data.copyOfRange(offset, offset + chunkSize)
            chunkNum++
            
            Log.d(TAG, "Sending chunk $chunkNum/$totalChunks (${chunk.size} bytes)")
            
            val result = writeSingleChunk(gatt, characteristic, chunk, useWriteNoResponse)
            if (result.isFailure) {
                return@withLock result
            }
            
            offset += chunkSize
            
            if (offset < data.size) {
                delay(CHUNK_DELAY_MS)
            }
        }
        
        Result.success(Unit)
    }
    
    private suspend fun writeSingleChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        useWriteNoResponse: Boolean
    ): Result<Unit> {
        val writeType = if (useWriteNoResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        
        if (useWriteNoResponse) {
            return writeImmediate(gatt, characteristic, data, writeType)
        }
        
        writeCompletionDeferred = CompletableDeferred()
        
        val writeInitiated = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val result = gatt.writeCharacteristic(characteristic, data, writeType)
                result == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Write initiation error: ${e.message}")
            writeCompletionDeferred = null
            return Result.failure(e)
        }
        
        if (!writeInitiated) {
            writeCompletionDeferred = null
            return Result.failure(Exception("Write initiation failed"))
        }
        
        return try {
            withTimeout(WRITE_TIMEOUT_MS) {
                writeCompletionDeferred?.await() ?: Result.failure(Exception("Write cancelled"))
            }
        } catch (e: Exception) {
            writeCompletionDeferred = null
            Result.failure(Exception("Write timeout"))
        }
    }
    
    private fun writeImmediate(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        writeType: Int
    ): Result<Unit> {
        return try {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, data, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = data
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (success) Result.success(Unit) else Result.failure(Exception("Write failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun enableNotifications(
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): Result<Unit> {
        val gatt = bluetoothGatt ?: return Result.failure(Exception("Not connected"))
        
        val service = gatt.getService(serviceUuid)
            ?: return Result.failure(Exception("Service not found"))
        
        val characteristic = service.getCharacteristic(characteristicUuid)
            ?: return Result.failure(Exception("Characteristic not found"))
        
        Log.d(TAG, "Enabling notifications for $characteristicUuid")
        
        val success = gatt.setCharacteristicNotification(characteristic, true)
        
        val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
        }
        
        return if (success) {
            delay(200)
            Result.success(Unit)
        } else {
            Result.failure(Exception("Failed to enable notifications"))
        }
    }
    
    fun isConnected(): Boolean = bluetoothGatt != null
    
    fun setOnConnectionStateChange(listener: (Boolean, String?) -> Unit) {
        onConnectionStateChange = listener
    }
}
