package com.welockbridge.sdk.device

import android.bluetooth.BluetoothDevice as AndroidBluetoothDevice
import android.content.Context
import android.util.Log
import com.welockbridge.sdk.core.*
import com.welockbridge.sdk.internal.BleConnectionManager
import com.welockbridge.sdk.protocol.GSeriesProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

/**
 * WeLockBridge SDK - G-Series Digital Lock Implementation
 *
 * Implements LockableDevice and StatusReportingDevice interfaces
 * for G-Series digital locks using the Bander Protocol V11.
 */
internal class GSeriesDigitalLock(
  private val context: Context,
  private val androidDevice: AndroidBluetoothDevice,
  private val credentials: DeviceCredentials
) : LockableDevice, StatusReportingDevice {
  
  companion object {
    private const val TAG = "WeLockBridge.Lock"
    private const val RESPONSE_TIMEOUT_MS = 8000L
    private const val POLLING_INTERVAL_MS = 5000L
  }
  
  override val deviceId: String = androidDevice.address
  override val deviceName: String = androidDevice.name ?: "G-Series Lock"
  override val deviceType: DeviceType = DeviceType.DIGITAL_LOCK
  
  private val connectionManager = BleConnectionManager(context, androidDevice)
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  
  private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
  override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()
  
  private val _lockState = MutableStateFlow(LockState.UNKNOWN)
  override val lockState: Flow<LockState> = _lockState.asStateFlow()
  
  private var currentLockState = LockState.UNKNOWN
  private var batteryLevel = -1
  private var pollingJob: Job? = null
  
  private var serviceUuid: UUID? = null
  private var writeUuid: UUID? = null
  private var notifyUuid: UUID? = null
  
  private val responseBuffer = mutableListOf<Byte>()
  private var pendingResponse: CompletableDeferred<ByteArray>? = null
  
  init {
    connectionManager.setOnConnectionStateChange { connected, error ->
      if (connected) {
        _connectionState.value = ConnectionState.Connected
      } else {
        _connectionState.value = if (error != null) {
          ConnectionState.Error(error)
        } else {
          ConnectionState.Disconnected
        }
        stopPolling()
      }
    }
    
    scope.launch {
      connectionManager.dataReceived.collect { data ->
        handleReceivedData(data)
      }
    }
  }
  
  override suspend fun connect(): Result<Unit> {
    Log.d(TAG, "🔌 Connecting to $deviceId...")
    _connectionState.value = ConnectionState.Connecting
    
    val connectResult = connectionManager.connect()
    if (connectResult.isFailure) {
      Log.e(TAG, "❌ Connection failed: ${connectResult.exceptionOrNull()?.message}")
      _connectionState.value = ConnectionState.Error(
        connectResult.exceptionOrNull()?.message ?: "Connection failed"
      )
      return Result.failure(SdkException.ConnectionFailedException(connectResult.exceptionOrNull()))
    }
    
    Log.d(TAG, "✅ GATT connected, getting characteristics...")
    
    // Wait a bit for services to stabilize
    delay(1000)
    
    // Get discovered characteristics
    val charInfo = connectionManager.getCharacteristicInfo()
    if (charInfo == null) {
      Log.e(TAG, "❌ No compatible characteristics found!")
      disconnect()
      return Result.failure(SdkException.ConnectionFailedException(
        Exception("No compatible characteristics found")
      ))
    }
    
    serviceUuid = charInfo.first
    writeUuid = charInfo.second
    notifyUuid = charInfo.third
    
    Log.d(TAG, "📦 Service UUID: $serviceUuid")
    Log.d(TAG, "📝 Write UUID: $writeUuid")
    Log.d(TAG, "📥 Notify UUID: $notifyUuid")
    
    // Enable notifications - CRITICAL for receiving responses
    Log.d(TAG, "🔔 Enabling notifications...")
    val notifyResult = connectionManager.enableNotifications(serviceUuid!!, notifyUuid!!)
    if (notifyResult.isFailure) {
      Log.e(TAG, "❌ Failed to enable notifications: ${notifyResult.exceptionOrNull()?.message}")
      // Continue anyway - some devices work without explicit notification enable
    } else {
      Log.d(TAG, "✅ Notifications enabled")
    }
    
    // Wait for notification setup to stabilize
    delay(500)
    
    // Skip authentication for now - just query status
    Log.d(TAG, "🔍 Querying initial status...")
    val statusResult = queryLockStatus()
    if (statusResult.isFailure) {
      Log.w(TAG, "⚠️ Initial status query failed: ${statusResult.exceptionOrNull()?.message}")
      // Don't fail connection for this
    } else {
      Log.d(TAG, "✅ Initial status: ${statusResult.getOrNull()}")
    }
    
    // Start polling
    startPolling()
    
    _connectionState.value = ConnectionState.Connected
    Log.d(TAG, "✅ Connection complete!")
    return Result.success(Unit)
  }
  
  override suspend fun disconnect(): Result<Unit> {
    Log.d(TAG, "Disconnecting from $deviceId...")
    stopPolling()
    connectionManager.disconnect()
    _connectionState.value = ConnectionState.Disconnected
    return Result.success(Unit)
  }
  
  override fun isConnected(): Boolean = connectionManager.isConnected()
  
  override fun getCurrentLockState(): LockState = currentLockState
  
  override suspend fun lock(): Result<Boolean> {
    Log.d(TAG, "🔒 Locking device...")
    
    if (!isConnected()) {
      Log.e(TAG, "❌ Not connected!")
      return Result.failure(SdkException.NotConnectedException())
    }
    
    val command = GSeriesProtocol.buildLockCommand(credentials.encryptionKey)
    Log.d(TAG, "📤 Lock command built: ${command.size} bytes")
    
    val response = sendCommandAndWaitForResponse(command)
    
    return response.fold(
      onSuccess = { data ->
        Log.d(TAG, "📥 Parsing lock response...")
        val parsed = GSeriesProtocol.parseResponse(data, credentials.encryptionKey)
        if (parsed?.isSuccess == true) {
          Log.d(TAG, "✅ Lock successful!")
          currentLockState = LockState.LOCKED
          _lockState.value = LockState.LOCKED
          Result.success(true)
        } else {
          Log.e(TAG, "❌ Lock failed: resultCode=${parsed?.resultCode}")
          Result.failure(SdkException.CommandFailedException(parsed?.resultCode))
        }
      },
      onFailure = {
        Log.e(TAG, "❌ Lock command failed: ${it.message}")
        Result.failure(it)
      }
    )
  }
  
  override suspend fun unlock(): Result<Boolean> {
    Log.d(TAG, "🔓 Unlocking device...")
    
    if (!isConnected()) {
      Log.e(TAG, "❌ Not connected!")
      return Result.failure(SdkException.NotConnectedException())
    }
    
    val command = GSeriesProtocol.buildUnlockCommand(credentials.encryptionKey)
    Log.d(TAG, "📤 Unlock command built: ${command.size} bytes")
    
    val response = sendCommandAndWaitForResponse(command)
    
    return response.fold(
      onSuccess = { data ->
        Log.d(TAG, "📥 Parsing unlock response...")
        val parsed = GSeriesProtocol.parseResponse(data, credentials.encryptionKey)
        if (parsed?.isSuccess == true) {
          Log.d(TAG, "✅ Unlock successful!")
          currentLockState = LockState.UNLOCKED
          _lockState.value = LockState.UNLOCKED
          Result.success(true)
        } else {
          Log.e(TAG, "❌ Unlock failed: resultCode=${parsed?.resultCode}")
          Result.failure(SdkException.CommandFailedException(parsed?.resultCode))
        }
      },
      onFailure = {
        Log.e(TAG, "❌ Unlock command failed: ${it.message}")
        Result.failure(it)
      }
    )
  }
  
  override suspend fun queryLockStatus(): Result<LockState> {
    Log.d(TAG, "🔍 Querying lock status...")
    
    if (!isConnected()) {
      Log.e(TAG, "❌ Not connected!")
      return Result.failure(SdkException.NotConnectedException())
    }
    
    val command = GSeriesProtocol.buildQueryStatusCommand(credentials.encryptionKey)
    Log.d(TAG, "📤 Query command built: ${command.size} bytes")
    
    val response = sendCommandAndWaitForResponse(command)
    
    return response.fold(
      onSuccess = { data ->
        Log.d(TAG, "📥 Parsing query response...")
        val parsed = GSeriesProtocol.parseResponse(data, credentials.encryptionKey)
        if (parsed != null) {
          val isLocked = GSeriesProtocol.extractLockState(parsed.content)
          val battery = GSeriesProtocol.extractBatteryLevel(parsed.content)
          
          Log.d(TAG, "📊 Parsed: isLocked=$isLocked, battery=$battery")
          
          if (battery != null) {
            batteryLevel = battery
          }
          
          val state = when (isLocked) {
            true -> LockState.LOCKED
            false -> LockState.UNLOCKED
            null -> LockState.UNKNOWN
          }
          
          currentLockState = state
          _lockState.value = state
          Log.d(TAG, "✅ Lock state: $state")
          Result.success(state)
        } else {
          Log.w(TAG, "⚠️ Could not parse response")
          Result.success(LockState.UNKNOWN)
        }
      },
      onFailure = {
        Log.e(TAG, "❌ Query failed: ${it.message}")
        Result.failure(it)
      }
    )
  }
  
  override suspend fun queryStatus(): Result<DeviceStatus> {
    val lockResult = queryLockStatus()
    
    return lockResult.fold(
      onSuccess = { lockState ->
        Result.success(
          DeviceStatus(
            lockState = lockState,
            batteryLevel = batteryLevel,
            isConnected = isConnected(),
            signalStrength = 0,
            lastUpdated = System.currentTimeMillis()
          )
        )
      },
      onFailure = { Result.failure(it) }
    )
  }
  
  private suspend fun authenticate(): Result<Unit> {
    val password = credentials.password ?: return Result.success(Unit)
    
    Log.d(TAG, "Authenticating...")
    
    // Try encrypted first
    var command = GSeriesProtocol.buildAuthCommand(password, credentials.encryptionKey)
    var response = sendCommandAndWaitForResponse(command)
    
    if (response.isSuccess) {
      val parsed = GSeriesProtocol.parseResponse(response.getOrNull()!!, credentials.encryptionKey)
      if (parsed?.isSuccess == true) {
        Log.d(TAG, "Authentication successful (encrypted)")
        return Result.success(Unit)
      }
    }
    
    // Try plain
    command = GSeriesProtocol.buildAuthCommand(password, null)
    response = sendCommandAndWaitForResponse(command)
    
    return response.fold(
      onSuccess = { data ->
        val parsed = GSeriesProtocol.parseResponse(data, null)
        if (parsed?.isSuccess == true) {
          Log.d(TAG, "Authentication successful (plain)")
          Result.success(Unit)
        } else {
          Log.w(TAG, "Authentication failed: ${parsed?.resultCode}")
          Result.failure(SdkException.AuthenticationFailedException())
        }
      },
      onFailure = { Result.failure(SdkException.AuthenticationFailedException()) }
    )
  }
  
  private suspend fun sendCommandAndWaitForResponse(command: ByteArray): Result<ByteArray> {
    val svcUuid = serviceUuid ?: return Result.failure(SdkException.NotConnectedException())
    val wrtUuid = writeUuid ?: return Result.failure(SdkException.NotConnectedException())
    
    val hex = command.joinToString(" ") { String.format("%02X", it) }
    Log.d(TAG, "")
    Log.d(TAG, "╔══════════════════════════════════════════════════════════════╗")
    Log.d(TAG, "║ 📤 SENDING COMMAND                                           ║")
    Log.d(TAG, "╠══════════════════════════════════════════════════════════════╣")
    Log.d(TAG, "║ Size: ${command.size} bytes")
    Log.d(TAG, "║ Hex: $hex")
    Log.d(TAG, "║ To: $wrtUuid")
    Log.d(TAG, "╚══════════════════════════════════════════════════════════════╝")
    Log.d(TAG, "")
    
    responseBuffer.clear()
    pendingResponse = CompletableDeferred()
    
    val writeResult = connectionManager.writeData(svcUuid, wrtUuid, command)
    if (writeResult.isFailure) {
      Log.e(TAG, "❌ Write failed: ${writeResult.exceptionOrNull()?.message}")
      pendingResponse = null
      return Result.failure(writeResult.exceptionOrNull() ?: Exception("Write failed"))
    }
    
    Log.d(TAG, "✅ Write successful, waiting for response...")
    
    return try {
      withTimeout(RESPONSE_TIMEOUT_MS) {
        val data = pendingResponse?.await()
        pendingResponse = null
        if (data != null) {
          val respHex = data.joinToString(" ") { String.format("%02X", it) }
          Log.d(TAG, "✅ Response received: ${data.size} bytes")
          Log.d(TAG, "   Hex: $respHex")
          Result.success(data)
        } else {
          Log.e(TAG, "❌ No response data")
          Result.failure(SdkException.TimeoutException("response"))
        }
      }
    } catch (e: Exception) {
      pendingResponse = null
      Log.e(TAG, "❌ Response timeout after ${RESPONSE_TIMEOUT_MS}ms")
      Result.failure(SdkException.TimeoutException("response"))
    }
  }
  
  /**
   * Handle received data - FROM WORKING DigitalLock.kt
   * Accumulates data and extracts complete frames
   */
  private fun handleReceivedData(data: ByteArray) {
    val hex = data.joinToString(" ") { String.format("%02X", it) }
    Log.d(TAG, "")
    Log.d(TAG, "RAW DATA RECEIVED")
    Log.d(TAG, "  Size: ${data.size} bytes")
    Log.d(TAG, "  Hex: $hex")
    
    responseBuffer.addAll(data.toList())
    
    val bufferArray = responseBuffer.toByteArray()
    Log.d(TAG, "  Buffer: ${bufferArray.size} bytes total")
    
    // Try to extract complete frame - FROM WORKING POC
    val completeFrame = tryExtractCompleteFrame()
    
    if (completeFrame != null) {
      val frameHex = completeFrame.joinToString(" ") { String.format("%02X", it) }
      Log.d(TAG, "  Complete frame: ${completeFrame.size} bytes")
      Log.d(TAG, "  Frame hex: $frameHex")
      pendingResponse?.complete(completeFrame)
    }
  }
  
  /**
   * Try to extract a complete frame - EXACT COPY from working DigitalLock.kt
   *
   * Looks for:
   * 1. ACK response: 3 bytes starting with 20 F1
   * 2. Full frame: F3 3F ... F4 4F
   */
  private fun tryExtractCompleteFrame(): ByteArray? {
    if (responseBuffer.size < 3) return null
    
    val buffer = responseBuffer.toByteArray()
    
    // Check for ACK response (3 bytes: 20 F1 XX) - FROM WORKING POC
    if (buffer.size >= 3 &&
      buffer[0] == 0x20.toByte() &&
      buffer[1] == 0xF1.toByte()
    ) {
      Log.d(TAG, "  ACK response detected!")
      val frame = buffer.copyOfRange(0, 3)
      responseBuffer.clear()
      if (buffer.size > 3) {
        responseBuffer.addAll(buffer.copyOfRange(3, buffer.size).toList())
      }
      return frame
    }
    
    // Look for full frame: F3 3F header ... F4 4F tail - FROM WORKING POC
    if (buffer.size < 11) return null
    
    // Find F3 3F header
    var startIndex = -1
    for (i in 0 until buffer.size - 1) {
      if (buffer[i] == 0xF3.toByte() && buffer[i + 1] == 0x3F.toByte()) {
        startIndex = i
        break
      }
    }
    
    if (startIndex < 0) {
      Log.d(TAG, "  No F3 3F header found")
      return null
    }
    
    // Find F4 4F tail
    var endIndex = -1
    for (i in startIndex until buffer.size - 1) {
      if (buffer[i] == 0xF4.toByte() && buffer[i + 1] == 0x4F.toByte()) {
        endIndex = i + 2
        break
      }
    }
    
    if (endIndex < 0) {
      Log.d(TAG, "  No F4 4F tail found yet, waiting for more data")
      return null
    }
    
    // Extract frame
    val frame = buffer.copyOfRange(startIndex, endIndex)
    Log.d(TAG, "  Frame extracted: ${frame.size} bytes (start=$startIndex, end=$endIndex)")
    
    // Clear extracted part from buffer
    responseBuffer.clear()
    if (endIndex < buffer.size) {
      responseBuffer.addAll(buffer.copyOfRange(endIndex, buffer.size).toList())
    }
    
    return frame
  }
  
  private fun startPolling() {
    pollingJob?.cancel()
    pollingJob = scope.launch {
      while (isActive && isConnected()) {
        delay(POLLING_INTERVAL_MS)
        try {
          queryLockStatus()
        } catch (e: Exception) {
          Log.w(TAG, "Polling error: ${e.message}")
        }
      }
    }
  }
  
  private fun stopPolling() {
    pollingJob?.cancel()
    pollingJob = null
  }
  
  fun destroy() {
    scope.cancel()
    stopPolling()
    connectionManager.disconnect()
  }
}
