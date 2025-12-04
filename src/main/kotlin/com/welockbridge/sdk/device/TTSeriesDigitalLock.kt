package com.welockbridge.sdk.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice as AndroidBluetoothDevice
import android.content.Context
import android.util.Log
import com.welockbridge.sdk.core.*
import com.welockbridge.sdk.internal.BleConnectionManager
import com.welockbridge.sdk.protocol.TTSeriesProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * WeLockBridge SDK - TT Series E-Lock Digital Lock Implementation
 *
 * Implements the TOTARGET BLE-ELOCK PROTOCOL (Version A7)
 *
 * Features:
 * - Lock/Unlock operations
 * - Status checking with battery level
 * - Heartbeat handling
 * - Alarm notifications
 * - Work mode configuration (Sleep/Real-time)
 *
 * KEY DIFFERENCES FROM G-SERIES:
 * 1. Different frame structure (no frame header/tail)
 * 2. CRC8 instead of checksum + CRC16
 * 3. Different command codes and response handling
 * 4. Lock ID and Password based authentication
 *
 * @author WeLockBridge Team
 * @version 1.0.0
 */
@SuppressLint("MissingPermission")
internal class TTSeriesDigitalLock(
    private val context: Context,
    private val androidDevice: AndroidBluetoothDevice,
    private val credentials: DeviceCredentials,
    private val lockId: ByteArray = byteArrayOf(0x00, 0x00, 0x00, 0x00) // Default lock ID
) : TTSeriesLockDevice, StatusReportingDevice {

    companion object {
        private const val TAG = "WeLockBridge.TTLock"
        private const val RESPONSE_TIMEOUT_MS = 8000L
        private const val POLLING_INTERVAL_MS = 10000L // TT locks may have longer intervals
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val STATE_DEBOUNCE_MS = 2000L
        private const val MAX_CONSECUTIVE_FAILURES = 5
        private const val COMMAND_DELAY_MS = 500L
        private const val HEARTBEAT_REPLY_TIMEOUT_MS = 3000L
    }

    override val deviceId: String = androidDevice.address
    override val deviceName: String = try {
        androidDevice.name ?: "TT-Series E-Lock"
    } catch (e: SecurityException) {
        "TT-Series E-Lock"
    }
    override val deviceType: DeviceType = DeviceType.DIGITAL_LOCK

    private val connectionManager = BleConnectionManager(context, androidDevice)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _lockState = MutableStateFlow(LockState.UNKNOWN)
    override val lockState: Flow<LockState> = _lockState.asStateFlow()

    // Cache last known valid state
    private var currentLockState = LockState.UNKNOWN
    private var lastValidState = LockState.UNKNOWN
    private var lastValidStateTime = 0L
    private var lastCommandState: LockState? = null

    private var batteryLevel = -1
    private var pollingJob: Job? = null
    private var isPollingEnabled = false

    private var serviceUuid: UUID? = null
    private var writeUuid: UUID? = null
    private var notifyUuid: UUID? = null

    private val responseBuffer = mutableListOf<Byte>()
    private var pendingResponse: CompletableDeferred<ByteArray>? = null

    // Retry mechanism
    private var consecutiveFailures = 0

    // Command synchronization
    private var lastCommandTime = 0L
    private val commandMutex = Mutex()

    // TT-specific: Store lock ID extracted from responses
    private var detectedLockId: ByteArray? = null

    // Password for TT protocol (6 bytes ASCII)
    private val password: ByteArray
        get() = credentials.password?.take(6)?.padEnd(6, '0')?.toByteArray(Charsets.US_ASCII)
            ?: "000000".toByteArray(Charsets.US_ASCII)

    // Encryption key (optional for TT protocol)
    private val encryptionKey: ByteArray?
        get() = credentials.encryptionKey

    // Effective lock ID (detected or provided)
    private val effectiveLockId: ByteArray
        get() = detectedLockId ?: lockId

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
        Log.d(TAG, "Connecting to TT E-Lock: $deviceId...")
        _connectionState.value = ConnectionState.Connecting

        val connectResult = connectionManager.connect()
        if (connectResult.isFailure) {
            Log.e(TAG, "Connection failed: ${connectResult.exceptionOrNull()?.message}")
            _connectionState.value = ConnectionState.Error(
                connectResult.exceptionOrNull()?.message ?: "Connection failed"
            )
            return Result.failure(SdkException.ConnectionFailedException(connectResult.exceptionOrNull()))
        }

        Log.d(TAG, "GATT connected, getting characteristics...")
        delay(1000)

        val charInfo = connectionManager.getCharacteristicInfo()
        if (charInfo == null) {
            Log.e(TAG, "No compatible characteristics found!")
            disconnect()
            return Result.failure(SdkException.ConnectionFailedException(
                Exception("No compatible characteristics found")
            ))
        }

        serviceUuid = charInfo.first
        writeUuid = charInfo.second
        notifyUuid = charInfo.third

        Log.d(TAG, "Service UUID: $serviceUuid")
        Log.d(TAG, "Write UUID: $writeUuid")
        Log.d(TAG, "Notify UUID: $notifyUuid")

        Log.d(TAG, "Enabling notifications...")
        val notifyResult = connectionManager.enableNotifications(serviceUuid!!, notifyUuid!!)
        if (notifyResult.isFailure) {
            Log.e(TAG, "Failed to enable notifications: ${notifyResult.exceptionOrNull()?.message}")
        } else {
            Log.d(TAG, "Notifications enabled")
        }

        delay(500)

        // Calibrate time first (recommended for TT locks)
        Log.d(TAG, "Calibrating time...")
        val calibrateResult = calibrateTime()
        if (calibrateResult.isFailure) {
            Log.w(TAG, "Time calibration failed, continuing anyway")
        }

        delay(300)

        // Query initial status
        Log.d(TAG, "Querying initial status...")
        val statusResult = queryLockStatusWithRetry()
        if (statusResult.isFailure) {
            Log.w(TAG, "Initial status query failed, will retry via polling")
        } else {
            Log.d(TAG, "Initial status: ${statusResult.getOrNull()}")
        }

        startPolling()

        _connectionState.value = ConnectionState.Connected
        Log.d(TAG, "Connection complete!")
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

    override fun getCurrentLockState(): LockState {
        val timeSinceLastValid = System.currentTimeMillis() - lastValidStateTime
        return if (currentLockState == LockState.UNKNOWN && timeSinceLastValid < 30000L) {
            lastValidState
        } else {
            currentLockState
        }
    }

    override suspend fun lock(): Result<Boolean> {
        Log.d(TAG, "Locking TT E-Lock...")

        if (!isConnected()) {
            Log.e(TAG, "Not connected!")
            return Result.failure(SdkException.NotConnectedException())
        }

        lastCommandState = LockState.LOCKED

        val command = TTSeriesProtocol.buildLockCommand(effectiveLockId, password, encryptionKey)
        Log.d(TAG, "Lock command built: ${command.size} bytes")

        val response = sendCommandAndWaitForResponse(command)

        return response.fold(
            onSuccess = { data ->
                Log.d(TAG, "Parsing lock response...")
                val parsed = TTSeriesProtocol.parseResponse(data, encryptionKey)

                if (parsed == null) {
                    Log.e(TAG, "Failed to parse lock response")
                    lastCommandState = null
                    return Result.failure(SdkException.CommandFailedException(null))
                }

                // Update lock ID if detected
                parsed.lockId?.let { detectedLockId = it }

                // Update battery level if available
                parsed.batteryLevel?.let { batteryLevel = it }

                when (parsed.command) {
                    TTSeriesProtocol.RESP_LOCK_SUCCESS,
                    TTSeriesProtocol.RESP_LOCK_AGAIN -> {
                        Log.d(TAG, "Lock successful!")
                        updateLockState(LockState.LOCKED)
                        Result.success(true)
                    }
                    else -> {
                        Log.e(TAG, "Lock failed: ${parsed.errorMessage}")
                        lastCommandState = null
                        Result.failure(SdkException.CommandFailedException(parsed.command))
                    }
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Lock command failed: ${error.message}")
                lastCommandState = null
                Result.failure(error)
            }
        )
    }

    override suspend fun unlock(): Result<Boolean> {
        Log.d(TAG, "Unlocking TT E-Lock...")

        if (!isConnected()) {
            Log.e(TAG, "Not connected!")
            return Result.failure(SdkException.NotConnectedException())
        }

        lastCommandState = LockState.UNLOCKED

        val command = TTSeriesProtocol.buildUnlockCommand(effectiveLockId, password, encryptionKey)
        Log.d(TAG, "Unlock command built: ${command.size} bytes")

        val response = sendCommandAndWaitForResponse(command)

        return response.fold(
            onSuccess = { data ->
                Log.d(TAG, "Parsing unlock response...")
                val parsed = TTSeriesProtocol.parseResponse(data, encryptionKey)

                if (parsed == null) {
                    Log.e(TAG, "Failed to parse unlock response")
                    lastCommandState = null
                    return Result.failure(SdkException.CommandFailedException(null))
                }

                // Update lock ID if detected
                parsed.lockId?.let { detectedLockId = it }

                // Update battery level if available
                parsed.batteryLevel?.let { batteryLevel = it }

                when (parsed.command) {
                    TTSeriesProtocol.RESP_UNLOCK_SUCCESS,
                    TTSeriesProtocol.RESP_UNLOCK_AGAIN -> {
                        Log.d(TAG, "Unlock successful!")
                        updateLockState(LockState.UNLOCKED)
                        Result.success(true)
                    }
                    TTSeriesProtocol.RESP_UNLOCK_WRONG_PASSWORD -> {
                        Log.e(TAG, "Unlock failed: wrong password")
                        lastCommandState = null
                        Result.failure(SdkException.AuthenticationFailedException())
                    }
                    else -> {
                        Log.e(TAG, "Unlock failed: ${parsed.errorMessage}")
                        lastCommandState = null
                        Result.failure(SdkException.CommandFailedException(parsed.command))
                    }
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Unlock command failed: ${error.message}")
                lastCommandState = null
                Result.failure(error)
            }
        )
    }

    override suspend fun queryLockStatus(): Result<LockState> {
        Log.d(TAG, "Querying TT E-Lock status...")

        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }

        val command = TTSeriesProtocol.buildCheckStatusCommand(effectiveLockId, password, encryptionKey)
        val response = sendCommandAndWaitForResponse(command)

        return response.fold(
            onSuccess = { data ->
                val parsed = TTSeriesProtocol.parseResponse(data, encryptionKey)

                if (parsed == null) {
                    consecutiveFailures++
                    Log.w(TAG, "Failed to parse status response")
                    return Result.success(lastValidState)
                }

                consecutiveFailures = 0

                // Update lock ID if detected
                parsed.lockId?.let { detectedLockId = it }

                // Update battery level if available
                parsed.batteryLevel?.let {
                    batteryLevel = it
                    Log.d(TAG, "Battery level: $it%")
                }

                // Extract lock state from status
                val lockStatus = parsed.lockStatus
                val state = if (lockStatus != null) {
                    when {
                        TTSeriesProtocol.isLocked(lockStatus) -> LockState.LOCKED
                        TTSeriesProtocol.isUnlocked(lockStatus) -> LockState.UNLOCKED
                        else -> {
                            Log.w(TAG, "Unknown lock status: ${TTSeriesProtocol.getLockStatusDescription(lockStatus)}")
                            LockState.UNKNOWN
                        }
                    }
                } else {
                    LockState.UNKNOWN
                }

                if (state != LockState.UNKNOWN) {
                    updateLockState(state)
                }

                Log.d(TAG, "Lock status: $state")
                Result.success(state)
            },
            onFailure = { error ->
                consecutiveFailures++
                Log.e(TAG, "Status query failed: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Query status with retry logic.
     */
    private suspend fun queryLockStatusWithRetry(maxAttempts: Int = MAX_RETRY_ATTEMPTS): Result<LockState> {
        for (attempt in 0 until maxAttempts) {
            val result = queryLockStatus()
            if (result.isSuccess && result.getOrNull() != LockState.UNKNOWN) {
                return result
            }
            if (attempt < maxAttempts - 1) {
                Log.d(TAG, "Retry attempt ${attempt + 1}/$maxAttempts")
                delay(1000)
            }
        }

        return if (lastValidState != LockState.UNKNOWN) {
            Log.d(TAG, "All retries failed, using cached state: $lastValidState")
            Result.success(lastValidState)
        } else {
            Result.success(LockState.UNKNOWN)
        }
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

    /**
     * Calibrate the lock's time.
     */
    override suspend fun calibrateTime(): Result<Unit> {
        Log.d(TAG, "Calibrating time...")

        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }

        val command = TTSeriesProtocol.buildCalibrateTimeCommand(encryptionKey)
        val response = sendCommandAndWaitForResponse(command)

        return response.fold(
            onSuccess = { data ->
                val parsed = TTSeriesProtocol.parseResponse(data, encryptionKey)
                if (parsed?.isSuccess == true) {
                    Log.d(TAG, "Time calibration successful")
                    Result.success(Unit)
                } else {
                    Log.w(TAG, "Time calibration response unclear")
                    Result.success(Unit) // Not critical
                }
            },
            onFailure = { error ->
                Log.e(TAG, "Time calibration failed: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Get the lock's firmware version.
     */
    override suspend fun getVersion(): Result<String> {
        Log.d(TAG, "Getting version...")

        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }

        val command = TTSeriesProtocol.buildCheckVersionCommand(encryptionKey)
        val response = sendCommandAndWaitForResponse(command)

        return response.fold(
            onSuccess = { data ->
                val parsed = TTSeriesProtocol.parseResponse(data, encryptionKey)
                if (parsed?.version != null) {
                    Log.d(TAG, "Version: ${parsed.version}")
                    Result.success(parsed.version)
                } else {
                    Result.failure(SdkException.CommandFailedException(null))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Set the lock's work mode.
     *
     * @param sleepMode true for sleep mode (lower power), false for real-time mode
     */
    override suspend fun setWorkMode(sleepMode: Boolean): Result<Unit> {
        Log.d(TAG, "Setting work mode: ${if (sleepMode) "SLEEP" else "REALTIME"}")

        if (!isConnected()) {
            return Result.failure(SdkException.NotConnectedException())
        }

        val command = TTSeriesProtocol.buildSetWorkModeCommand(effectiveLockId, sleepMode, encryptionKey)
        val response = sendCommandAndWaitForResponse(command)

        return response.fold(
            onSuccess = { data ->
                val parsed = TTSeriesProtocol.parseResponse(data, encryptionKey)
                if (parsed?.isSuccess == true) {
                    Log.d(TAG, "Work mode set successfully")
                    Result.success(Unit)
                } else {
                    Result.failure(SdkException.CommandFailedException(parsed?.command))
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Get the detected lock ID (from device responses).
     */
    fun getDetectedLockId(): ByteArray? = detectedLockId

    /**
     * Get the lock ID as a string.
     */
    override fun getLockIdString(): String? {
        return detectedLockId?.let { TTSeriesProtocol.lockIdToString(it) }
    }

    /**
     * Get battery level.
     */
    override fun getBatteryLevel(): Int = batteryLevel

    /**
     * Update lock state with caching.
     */
    private fun updateLockState(newState: LockState) {
        if (newState != LockState.UNKNOWN) {
            lastValidState = newState
            lastValidStateTime = System.currentTimeMillis()
            if (lastCommandState == newState) {
                lastCommandState = null
            }
        }
        currentLockState = newState
        _lockState.value = newState
    }

    private suspend fun sendCommandAndWaitForResponse(command: ByteArray): Result<ByteArray> {
        return commandMutex.withLock {
            val timeSinceLastCommand = System.currentTimeMillis() - lastCommandTime
            if (timeSinceLastCommand < COMMAND_DELAY_MS) {
                delay(COMMAND_DELAY_MS - timeSinceLastCommand)
            }

            val svcUuid = serviceUuid ?: return@withLock Result.failure(SdkException.NotConnectedException())
            val wrtUuid = writeUuid ?: return@withLock Result.failure(SdkException.NotConnectedException())

            val hex = command.joinToString(" ") { String.format("%02X", it) }
            Log.d(TAG, "")
            Log.d(TAG, "===============================================")
            Log.d(TAG, "SENDING TT COMMAND")
            Log.d(TAG, "===============================================")
            Log.d(TAG, "Size: ${command.size} bytes")
            Log.d(TAG, "Hex: $hex")
            Log.d(TAG, "To: $wrtUuid")
            Log.d(TAG, "===============================================")
            Log.d(TAG, "")

            responseBuffer.clear()
            pendingResponse = CompletableDeferred()

            val writeResult = connectionManager.writeData(svcUuid, wrtUuid, command)
            lastCommandTime = System.currentTimeMillis()

            if (writeResult.isFailure) {
                Log.e(TAG, "Write failed: ${writeResult.exceptionOrNull()?.message}")
                pendingResponse = null
                return@withLock Result.failure(writeResult.exceptionOrNull() ?: Exception("Write failed"))
            }

            Log.d(TAG, "Write successful, waiting for response...")

            try {
                withTimeout(RESPONSE_TIMEOUT_MS) {
                    val data = pendingResponse?.await()
                    pendingResponse = null
                    if (data != null) {
                        val respHex = data.joinToString(" ") { String.format("%02X", it) }
                        Log.d(TAG, "Response received: ${data.size} bytes")
                        Log.d(TAG, "Hex: $respHex")
                        Result.success(data)
                    } else {
                        Log.e(TAG, "No response data")
                        Result.failure(SdkException.TimeoutException("response"))
                    }
                }
            } catch (e: Exception) {
                pendingResponse = null
                Log.e(TAG, "Response timeout after ${RESPONSE_TIMEOUT_MS}ms")
                Result.failure(SdkException.TimeoutException("response"))
            }
        }
    }

    private fun handleReceivedData(data: ByteArray) {
        val hex = data.joinToString(" ") { String.format("%02X", it) }
        Log.d(TAG, "")
        Log.d(TAG, "RAW DATA RECEIVED")
        Log.d(TAG, "Size: ${data.size} bytes")
        Log.d(TAG, "Hex: $hex")

        responseBuffer.addAll(data.toList())

        val bufferArray = responseBuffer.toByteArray()
        Log.d(TAG, "Buffer: ${bufferArray.size} bytes total")

        val completeFrame = tryExtractCompleteFrame()

        if (completeFrame != null) {
            val frameHex = completeFrame.joinToString(" ") { String.format("%02X", it) }
            Log.d(TAG, "Complete frame: ${completeFrame.size} bytes")
            Log.d(TAG, "Frame hex: $frameHex")

            // Check for heartbeat/alarm and handle asynchronously
            if (shouldHandleAsynchronously(completeFrame)) {
                handleAsynchronousMessage(completeFrame)
            } else {
                pendingResponse?.complete(completeFrame)
            }
        }
    }

    /**
     * Check if this message should be handled asynchronously (heartbeat, alarm).
     */
    private fun shouldHandleAsynchronously(frame: ByteArray): Boolean {
        if (frame.size < 3) return false

        val encType = frame[0].toInt() and 0xFF
        val dataLen = frame[1].toInt() and 0xFF

        if (dataLen < 1) return false

        // Get command byte (may need decryption)
        val command = if (encType == TTSeriesProtocol.ENCRYPTION_NONE && frame.size > 2) {
            frame[2].toInt() and 0xFF
        } else {
            return false // Can't determine without decryption
        }

        return command == TTSeriesProtocol.CMD_HEARTBEAT ||
                command == TTSeriesProtocol.CMD_BROKE_ALARM ||
                command == TTSeriesProtocol.CMD_ROD_CUT_ALARM ||
                command == TTSeriesProtocol.CMD_OPENED_ALARM
    }

    /**
     * Handle asynchronous messages (heartbeat, alarms).
     */
    private fun handleAsynchronousMessage(frame: ByteArray) {
        scope.launch {
            val parsed = TTSeriesProtocol.parseResponse(frame, encryptionKey)
            if (parsed == null) {
                Log.w(TAG, "Failed to parse async message")
                return@launch
            }

            // Update lock ID if available
            parsed.lockId?.let { detectedLockId = it }

            // Update battery if available
            parsed.batteryLevel?.let { batteryLevel = it }

            // Update lock state if available
            parsed.lockStatus?.let { status ->
                val state = when {
                    TTSeriesProtocol.isLocked(status) -> LockState.LOCKED
                    TTSeriesProtocol.isUnlocked(status) -> LockState.UNLOCKED
                    else -> null
                }
                state?.let { updateLockState(it) }
            }

            when {
                parsed.isHeartbeat -> {
                    Log.d(TAG, "Heartbeat received, sending reply...")
                    // Send heartbeat reply
                    val reply = TTSeriesProtocol.buildHeartbeatReply(effectiveLockId, encryptionKey)
                    try {
                        withTimeout(HEARTBEAT_REPLY_TIMEOUT_MS) {
                            connectionManager.writeData(serviceUuid!!, writeUuid!!, reply)
                        }
                        Log.d(TAG, "Heartbeat reply sent")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send heartbeat reply: ${e.message}")
                    }
                }
                parsed.isAlarm -> {
                    Log.w(TAG, "ALARM: ${parsed.alarmType}")
                    // Could emit to an alarm flow here for UI notification
                }
            }
        }
    }

    /**
     * Try to extract a complete TT protocol frame from the buffer.
     *
     * TT frame structure: EncType(1B) + Length(1B) + Data(nB) + CRC8(1B)
     */
    private fun tryExtractCompleteFrame(): ByteArray? {
        if (responseBuffer.size < 3) return null

        val buffer = responseBuffer.toByteArray()

        val encType = buffer[0].toInt() and 0xFF
        val dataLength = buffer[1].toInt() and 0xFF

        // Validate encryption type
        if (encType != TTSeriesProtocol.ENCRYPTION_NONE && encType != TTSeriesProtocol.ENCRYPTION_AES) {
            Log.w(TAG, "Invalid encryption type: 0x${encType.toString(16)}, clearing buffer")
            responseBuffer.clear()
            return null
        }

        // Calculate expected frame length
        val expectedLength = if (encType == TTSeriesProtocol.ENCRYPTION_AES) {
            // Encrypted data is padded to 16-byte blocks
            val paddedDataLength = ((dataLength + 15) / 16) * 16
            1 + 1 + paddedDataLength + 1 // encType + length + paddedData + CRC
        } else {
            1 + 1 + dataLength + 1 // encType + length + data + CRC
        }

        Log.d(TAG, "Expected frame length: $expectedLength, buffer size: ${buffer.size}")

        if (buffer.size < expectedLength) {
            Log.d(TAG, "Waiting for more data...")
            return null
        }

        // Extract frame
        val frame = buffer.copyOfRange(0, expectedLength)

        // Clear extracted bytes from buffer
        responseBuffer.clear()
        if (buffer.size > expectedLength) {
            responseBuffer.addAll(buffer.copyOfRange(expectedLength, buffer.size).toList())
        }

        return frame
    }

    private fun startPolling() {
        if (isPollingEnabled) {
            Log.d(TAG, "Polling already started")
            return
        }

        isPollingEnabled = true
        pollingJob?.cancel()
        pollingJob = scope.launch {
            Log.d(TAG, "Starting automatic status polling")
            while (isActive && isConnected() && isPollingEnabled) {
                delay(POLLING_INTERVAL_MS)

                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.w(TAG, "Stopping polling due to excessive failures ($consecutiveFailures)")
                    break
                }

                try {
                    val result = queryLockStatusWithRetry(maxAttempts = 2)
                    if (result.isSuccess) {
                        val state = result.getOrNull()
                        Log.d(TAG, "Polling result: $state")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Polling error: ${e.message}")
                }
            }
            Log.d(TAG, "Polling stopped")
            isPollingEnabled = false
        }
    }

    private fun stopPolling() {
        Log.d(TAG, "Stopping polling...")
        isPollingEnabled = false
        pollingJob?.cancel()
        pollingJob = null
    }

    fun destroy() {
        scope.cancel()
        stopPolling()
        connectionManager.disconnect()
    }
}
