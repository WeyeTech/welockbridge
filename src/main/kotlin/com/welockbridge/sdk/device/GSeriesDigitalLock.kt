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
        Log.d(TAG, "Connecting to $deviceId...")
        _connectionState.value = ConnectionState.Connecting
        
        val connectResult = connectionManager.connect()
        if (connectResult.isFailure) {
            _connectionState.value = ConnectionState.Error(
                connectResult.exceptionOrNull()?.message ?: "Connection failed"
            )
            return Result.failure(SdkException.ConnectionFailedException(connectResult.exceptionOrNull()))
        }
        
        // Get discovered characteristics
        val charInfo = connectionManager.getCharacteristicInfo()
        if (charInfo == null) {
            disconnect()
            return Result.failure(SdkException.ConnectionFailedException(
                Exception("No compatible characteristics found")
            ))
        }
        
        serviceUuid = charInfo.first
        writeUuid = charInfo.second
        notifyUuid = charInfo.third
        
        Log.d(TAG, "Using service: $serviceUuid")
        Log.d(TAG, "Write characteristic: $writeUuid")
        Log.d(TAG, "Notify characteristic: $notifyUuid")
        
        // Enable notifications
        val notifyResult = connectionManager.enableNotifications(serviceUuid!!, notifyUuid!!)
        if (notifyResult.isFailure) {
            Log.w(TAG, "Failed to enable notifications: ${notifyResult.exceptionOrNull()?.message}")
        }
        
        delay(500)
        
        // Authenticate
        val authResult = authenticate()
        if (authResult.isFailure) {
            Log.w(TAG, "Authentication failed, continuing anyway: ${authResult.exceptionOrNull()?.message}")
        }
        
        // Query initial status
        delay(300)
        queryLockStatus()
        
        // Start polling
        startPolling()
        
        _connectionState.value = ConnectionState.Connected
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
        Log.d(TAG, "Locking device...")
        
        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }
        
        val command = GSeriesProtocol.buildLockCommand(credentials.encryptionKey)
        val response = sendCommandAndWaitForResponse(command)
        
        return response.fold(
            onSuccess = { data ->
                val parsed = GSeriesProtocol.parseResponse(data, credentials.encryptionKey)
                if (parsed?.isSuccess == true) {
                    currentLockState = LockState.LOCKED
                    _lockState.value = LockState.LOCKED
                    Result.success(true)
                } else {
                    Result.failure(SdkException.CommandFailedException(parsed?.resultCode))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }
    
    override suspend fun unlock(): Result<Boolean> {
        Log.d(TAG, "Unlocking device...")
        
        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }
        
        val command = GSeriesProtocol.buildUnlockCommand(credentials.encryptionKey)
        val response = sendCommandAndWaitForResponse(command)
        
        return response.fold(
            onSuccess = { data ->
                val parsed = GSeriesProtocol.parseResponse(data, credentials.encryptionKey)
                if (parsed?.isSuccess == true) {
                    currentLockState = LockState.UNLOCKED
                    _lockState.value = LockState.UNLOCKED
                    Result.success(true)
                } else {
                    Result.failure(SdkException.CommandFailedException(parsed?.resultCode))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }
    
    override suspend fun queryLockStatus(): Result<LockState> {
        Log.d(TAG, "Querying lock status...")
        
        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }
        
        val command = GSeriesProtocol.buildQueryStatusCommand(credentials.encryptionKey)
        val response = sendCommandAndWaitForResponse(command)
        
        return response.fold(
            onSuccess = { data ->
                val parsed = GSeriesProtocol.parseResponse(data, credentials.encryptionKey)
                if (parsed != null) {
                    val isLocked = GSeriesProtocol.extractLockState(parsed.content)
                    val battery = GSeriesProtocol.extractBatteryLevel(parsed.content)
                    
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
                    Result.success(state)
                } else {
                    Result.success(LockState.UNKNOWN)
                }
            },
            onFailure = { Result.failure(it) }
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
        
        responseBuffer.clear()
        pendingResponse = CompletableDeferred()
        
        val writeResult = connectionManager.writeData(svcUuid, wrtUuid, command)
        if (writeResult.isFailure) {
            pendingResponse = null
            return Result.failure(writeResult.exceptionOrNull() ?: Exception("Write failed"))
        }
        
        return try {
            withTimeout(RESPONSE_TIMEOUT_MS) {
                val data = pendingResponse?.await()
                pendingResponse = null
                if (data != null) {
                    Result.success(data)
                } else {
                    Result.failure(SdkException.TimeoutException("response"))
                }
            }
        } catch (e: Exception) {
            pendingResponse = null
            Result.failure(SdkException.TimeoutException("response"))
        }
    }
    
    private fun handleReceivedData(data: ByteArray) {
        Log.d(TAG, "Received ${data.size} bytes")
        
        responseBuffer.addAll(data.toList())
        
        val bufferArray = responseBuffer.toByteArray()
        if (isCompleteFrame(bufferArray)) {
            Log.d(TAG, "Complete frame received")
            pendingResponse?.complete(bufferArray)
            responseBuffer.clear()
        }
    }
    
    private fun isCompleteFrame(data: ByteArray): Boolean {
        if (data.size < 11) return false
        
        // Check for response frame markers
        val hasHeader = (data[0] == 0xF3.toByte() && data[1] == 0x3F.toByte()) ||
                       (data[0] == 0xF1.toByte() && data[1] == 0x1F.toByte())
        
        if (!hasHeader) return false
        
        val hasTail = data.takeLast(2).let {
            (it[0] == 0xF4.toByte() && it[1] == 0x4F.toByte()) ||
            (it[0] == 0xF2.toByte() && it[1] == 0x2F.toByte())
        }
        
        return hasTail
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
