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
    private const val DESCRIPTOR_TIMEOUT_MS = 3000L
    private const val MAX_PACKET_SIZE = 20
    private const val CHUNK_DELAY_MS = 100L
  }
  
  private var bluetoothGatt: BluetoothGatt? = null
  private val writeMutex = Mutex()
  
  private val _dataReceived = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
  val dataReceived: Flow<ByteArray> = _dataReceived
  
  private var onConnectionStateChange: ((Boolean, String?) -> Unit)? = null
  private var writeCompletionDeferred: CompletableDeferred<Result<Unit>>? = null
  private var descriptorWriteDeferred: CompletableDeferred<Result<Unit>>? = null
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
          Log.d(TAG, "✅ Connected to GATT server")
          bluetoothGatt = gatt
          // Small delay before service discovery
          android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Starting service discovery...")
            gatt?.discoverServices()
          }, 500)
          onConnectionStateChange?.invoke(true, null)
        }
        BluetoothProfile.STATE_DISCONNECTED -> {
          Log.d(TAG, "❌ Disconnected from GATT server")
          onConnectionStateChange?.invoke(false, "Disconnected (status: $status)")
          connectionDeferred?.complete(Result.failure(Exception("Connection lost")))
        }
      }
    }
    
    override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
      Log.d(TAG, "Services discovered: status=$status")
      if (status == BluetoothGatt.GATT_SUCCESS) {
        logAllServices(gatt)
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
        Log.d(TAG, "✅ Write completed successfully to ${characteristic?.uuid}")
        Result.success(Unit)
      } else {
        Log.e(TAG, "❌ Write failed with status: $status")
        Result.failure(Exception("Write failed with status: $status"))
      }
      writeCompletionDeferred?.complete(result)
    }
    
    override fun onDescriptorWrite(
      gatt: BluetoothGatt?,
      descriptor: BluetoothGattDescriptor?,
      status: Int
    ) {
      val result = if (status == BluetoothGatt.GATT_SUCCESS) {
        Log.d(TAG, "✅ Descriptor write completed - notifications enabled")
        Result.success(Unit)
      } else {
        Log.e(TAG, "❌ Descriptor write failed with status: $status")
        Result.failure(Exception("Descriptor write failed: $status"))
      }
      descriptorWriteDeferred?.complete(result)
    }
    
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt,
      characteristic: BluetoothGattCharacteristic,
      value: ByteArray
    ) {
      Log.d(TAG, "📥 Received (API 33+): ${value.size} bytes - ${value.toHexString()}")
      _dataReceived.tryEmit(value)
    }
    
    @Deprecated("Deprecated in API level 33")
    override fun onCharacteristicChanged(
      gatt: BluetoothGatt?,
      characteristic: BluetoothGattCharacteristic?
    ) {
      characteristic?.value?.let { value ->
        Log.d(TAG, "📥 Received (Legacy): ${value.size} bytes - ${value.toHexString()}")
        _dataReceived.tryEmit(value)
      }
    }
  }
  
  private fun ByteArray.toHexString(): String {
    return joinToString(" ") { String.format("%02X", it) }
  }
  
  private fun logAllServices(gatt: BluetoothGatt?) {
    gatt ?: return
    Log.d(TAG, "========== ALL DISCOVERED SERVICES ==========")
    for (service in gatt.services) {
      Log.d(TAG, "📦 Service: ${service.uuid}")
      for (char in service.characteristics) {
        val props = char.properties
        val propsStr = buildString {
          if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) append("READ ")
          if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) append("WRITE ")
          if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) append("WRITE_NO_RESP ")
          if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) append("NOTIFY ")
          if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) append("INDICATE ")
        }
        Log.d(TAG, "  └─ Char: ${char.uuid} [$propsStr]")
      }
    }
    Log.d(TAG, "==============================================")
  }
  
  /**
   * AUTO-DISCOVER characteristics - NO HARDCODED UUIDs
   * Works exactly like nRF Connect - finds services dynamically
   */
  private fun discoverCharacteristics(gatt: BluetoothGatt?) {
    gatt ?: return
    
    Log.d(TAG, "")
    Log.d(TAG, "🔍 AUTO-DISCOVERING CHARACTERISTICS (LIKE NRF CONNECT)")
    Log.d(TAG, "   No hardcoded UUIDs - finding dynamically...")
    Log.d(TAG, "")
    
    // Standard BLE services to skip (not for data communication)
    val standardServicePrefixes = listOf(
      "00001800", // Generic Access
      "00001801", // Generic Attribute
      "0000180a", // Device Information
      "0000180f", // Battery Service
      "00001805", // Current Time
      "00001802"  // Immediate Alert
    )
    
    // DFU (Device Firmware Update) services to skip - NOT for data!
    val dfuServicePrefixes = listOf(
      "8e400001", // Nordic Buttonless DFU
      "0000fe59", // Nordic Secure DFU
      "00001530", // Nordic Legacy DFU
      "8ec90001"  // Another DFU variant
    )
    
    // Find ALL services with both WRITE and NOTIFY characteristics
    data class ServiceCandidate(
      val service: android.bluetooth.BluetoothGattService,
      val writeChar: BluetoothGattCharacteristic,
      val notifyChar: BluetoothGattCharacteristic,
      val score: Int
    )
    
    val candidates = mutableListOf<ServiceCandidate>()
    
    for (service in gatt.services) {
      val svcUuid = service.uuid.toString().lowercase()
      
      // Skip standard BLE services
      val isStandard = standardServicePrefixes.any { svcUuid.startsWith(it) }
      if (isStandard) {
        Log.d(TAG, "   ⏭️ Skip standard: ${service.uuid}")
        continue
      }
      
      // Skip DFU services - they're for firmware updates, not data!
      val isDfu = dfuServicePrefixes.any { svcUuid.startsWith(it) }
      if (isDfu) {
        Log.d(TAG, "   ⏭️ Skip DFU service: ${service.uuid}")
        continue
      }
      
      Log.d(TAG, "   📦 Service: ${service.uuid}")
      
      var writeChar: BluetoothGattCharacteristic? = null
      var notifyChar: BluetoothGattCharacteristic? = null
      var score = 0
      
      for (char in service.characteristics) {
        val props = char.properties
        
        // Build properties string
        val propsStr = mutableListOf<String>()
        if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) propsStr.add("R")
        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) propsStr.add("W")
        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) propsStr.add("WNR")
        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) propsStr.add("N")
        if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) propsStr.add("I")
        
        Log.d(TAG, "      └─ ${char.uuid} [${propsStr.joinToString(",")}]")
        
        // Find WRITE characteristic
        if (writeChar == null) {
          if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            writeChar = char
            score += 10
          } else if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            writeChar = char
            score += 5
          }
        }
        
        // Find NOTIFY/INDICATE characteristic
        if (notifyChar == null) {
          if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
            notifyChar = char
            score += 10
          } else if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
            notifyChar = char
            score += 5
          }
        }
      }
      
      // If service has both WRITE and NOTIFY, add to candidates
      if (writeChar != null && notifyChar != null) {
        // Bonus for custom UUID (128-bit, not 16-bit standard)
        if (!svcUuid.startsWith("0000")) {
          score += 20
        }
        
        // BIG bonus for Nordic UART Service (NUS) - most common data service
        if (svcUuid.startsWith("6e400001")) {
          score += 100
          Log.d(TAG, "      🎯 Nordic UART Service detected! +100 score")
        }
        
        candidates.add(ServiceCandidate(service, writeChar, notifyChar, score))
        Log.d(TAG, "      ✅ CANDIDATE score=$score")
      }
    }
    
    // Select best candidate
    val best = candidates.maxByOrNull { it.score }
    
    if (best != null) {
      discoveredCharacteristics = CharacteristicInfo(
        serviceUuid = best.service.uuid,
        writeUuid = best.writeChar.uuid,
        notifyUuid = best.notifyChar.uuid,
        properties = best.writeChar.properties
      )
      
      Log.d(TAG, "")
      Log.d(TAG, "╔══════════════════════════════════════════════════════╗")
      Log.d(TAG, "║ ✅ AUTO-DISCOVERED (NO HARDCODING)                   ║")
      Log.d(TAG, "╠══════════════════════════════════════════════════════╣")
      Log.d(TAG, "║ Service: ${best.service.uuid}")
      Log.d(TAG, "║ RX (Write): ${best.writeChar.uuid}")
      Log.d(TAG, "║ TX (Notify): ${best.notifyChar.uuid}")
      Log.d(TAG, "╚══════════════════════════════════════════════════════╝")
      Log.d(TAG, "")
    } else {
      Log.e(TAG, "")
      Log.e(TAG, "❌ NO SERVICE WITH WRITE+NOTIFY FOUND!")
      Log.e(TAG, "")
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
    
    Log.d(TAG, "📝 Enabling notifications for $characteristicUuid")
    
    // Enable local notification
    val success = gatt.setCharacteristicNotification(characteristic, true)
    if (!success) {
      Log.e(TAG, "❌ setCharacteristicNotification failed")
      return Result.failure(Exception("Failed to enable local notifications"))
    }
    Log.d(TAG, "✅ Local notification enabled")
    
    // Write to CCCD descriptor
    val descriptor = characteristic.getDescriptor(UUID.fromString(CCCD_UUID))
    if (descriptor == null) {
      Log.w(TAG, "⚠️ No CCCD descriptor found, notifications may not work")
      return Result.success(Unit)
    }
    
    Log.d(TAG, "📝 Writing to CCCD descriptor...")
    descriptorWriteDeferred = CompletableDeferred()
    
    val writeSuccess = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        result == BluetoothStatusCodes.SUCCESS
      } else {
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
      }
    } catch (e: Exception) {
      Log.e(TAG, "❌ Descriptor write error: ${e.message}")
      descriptorWriteDeferred = null
      return Result.failure(e)
    }
    
    if (!writeSuccess) {
      descriptorWriteDeferred = null
      Log.e(TAG, "❌ Descriptor write initiation failed")
      return Result.failure(Exception("Descriptor write failed"))
    }
    
    // Wait for descriptor write callback
    return try {
      withTimeout(DESCRIPTOR_TIMEOUT_MS) {
        descriptorWriteDeferred?.await() ?: Result.failure(Exception("Descriptor write cancelled"))
      }
    } catch (e: Exception) {
      Log.e(TAG, "❌ Descriptor write timeout")
      descriptorWriteDeferred = null
      Result.failure(Exception("Descriptor write timeout"))
    }
  }
  
  fun isConnected(): Boolean = bluetoothGatt != null
  
  fun setOnConnectionStateChange(listener: (Boolean, String?) -> Unit) {
    onConnectionStateChange = listener
  }
}
