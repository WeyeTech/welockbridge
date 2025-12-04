package com.welockbridge.sdk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.welockbridge.sdk.core.*
import com.welockbridge.sdk.device.GSeriesDigitalLock
import com.welockbridge.sdk.device.TTSeriesDigitalLock
import com.welockbridge.sdk.scanner.BleDeviceScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * WeLockBridge BLE SDK
 *
 * Main entry point for the SDK. Provides a clean public API for:
 * - Scanning for BLE devices
 * - Connecting to devices
 * - Managing device lifecycle
 * - Accessing device-specific functionality
 *
 * SUPPORTED PROTOCOLS:
 * - G-Series: IMZ BLE G-Series Padlock Protocol
 * - TT-Series: TOTARGET BLE-ELOCK Protocol
 *
 * The SDK automatically selects the correct protocol based on credentials.
 *
 * CONFIGURATION-BASED: No hardcoded device identifiers.
 * You must provide SdkConfig with your device patterns.
 *
 * Usage:
 * ```kotlin
 * // Create config with your device patterns
 * val config = SdkConfig.Builder()
 *     .addMacPrefix("DC:0D")
 *     .addNamePattern("g4-")
 *     .addServiceUuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
 *     .build()
 *
 * // Initialize SDK with config
 * val sdk = WeLockBridgeSdk.getInstance(context, config)
 *
 * // Connect to G-Series lock
 * val gSeriesCredentials = DeviceCredentials.withKeyAndPassword(key, password).getOrThrow()
 * sdk.connectToDevice(scannedDevice, gSeriesCredentials)
 *
 * // Connect to TT-Series lock
 * val ttCredentials = DeviceCredentials.forTTSeries("83181001", "112233").getOrThrow()
 * sdk.connectToDevice(scannedDevice, ttCredentials)
 * ```
 *
 * @author WeLockBridge Team
 * @version 1.2.0
 */
class WeLockBridgeSdk private constructor(
    private val context: Context,
    private var config: SdkConfig
) {

    companion object {
        private const val TAG = "WeLockBridgeSdk"
        const val VERSION = "1.3.0"
        const val SDK_NAME = "WeLockBridge BLE SDK"

        @Volatile
        private var instance: WeLockBridgeSdk? = null

        /**
         * Get the singleton instance of the SDK with configuration.
         *
         * @param context Application context
         * @param config SDK configuration with device identification patterns
         * @return SDK instance
         */
        fun getInstance(context: Context, config: SdkConfig): WeLockBridgeSdk {
            return instance ?: synchronized(this) {
                instance ?: WeLockBridgeSdk(context.applicationContext, config).also {
                    instance = it
                    Log.i(TAG, "$SDK_NAME v$VERSION initialized")
                    Log.i(TAG, "Config: ${config.macPrefixes.size} MAC prefixes, ${config.namePatterns.size} name patterns, ${config.serviceUuids.size} service UUIDs")
                    Log.i(TAG, "Supported protocols: G-Series, TT-Series")
                }
            }
        }

        /**
         * Get the singleton instance with empty config.
         * Use this if you want to add patterns at runtime.
         *
         * @param context Application context
         * @return SDK instance
         */
        fun getInstance(context: Context): WeLockBridgeSdk {
            return getInstance(context, SdkConfig.empty())
        }

        /**
         * Check if SDK instance exists.
         */
        fun isInitialized(): Boolean = instance != null

        /**
         * Reset SDK instance (for testing or reconfiguration).
         */
        fun reset() {
            instance = null
            Log.d(TAG, "SDK instance reset")
        }
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scanner = BleDeviceScanner(context, config)
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()

    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: Flow<ScanState> = _scanState.asStateFlow()

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    /**
     * Get current configuration.
     */
    fun getConfig(): SdkConfig = config

    /**
     * Update configuration.
     * This will affect subsequent scans.
     */
    fun updateConfig(newConfig: SdkConfig) {
        config = newConfig
        scanner.updateConfig(newConfig)
        Log.d(TAG, "Config updated")
    }

    /**
     * Add MAC address prefix at runtime.
     */
    fun addDeviceMacPrefix(prefix: String) {
        scanner.addMacPrefix(prefix)
        Log.d(TAG, "Added MAC prefix: $prefix")
    }

    /**
     * Add device name pattern at runtime.
     */
    fun addDeviceNamePattern(pattern: String) {
        scanner.addNamePattern(pattern)
        Log.d(TAG, "Added name pattern: $pattern")
    }

    /**
     * Add service UUID at runtime.
     */
    fun addDeviceServiceUuid(uuid: UUID) {
        scanner.addServiceUuid(uuid)
        Log.d(TAG, "Added service UUID: $uuid")
    }

    /**
     * Add service UUID from string at runtime.
     */
    fun addDeviceServiceUuid(uuidString: String) {
        try {
            val uuid = UUID.fromString(uuidString)
            addDeviceServiceUuid(uuid)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid UUID: $uuidString")
        }
    }

    /**
     * Clear all runtime-added patterns.
     */
    fun clearRuntimePatterns() {
        scanner.clearRuntimePatterns()
        Log.d(TAG, "Cleared runtime patterns")
    }

    // =========================================================================
    // BLUETOOTH STATUS
    // =========================================================================

    /**
     * Check if Bluetooth is available on this device.
     */
    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    /**
     * Check if Bluetooth is currently enabled.
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    // =========================================================================
    // SCANNING
    // =========================================================================

    /**
     * Start scanning for BLE devices.
     *
     * @param deviceTypes Types of devices to scan for (default: DIGITAL_LOCK)
     * @param scanDurationMs Scan duration in milliseconds (uses config default if not specified)
     * @param includeUnknown If true, includes all BLE devices even if type is unknown
     * @param onDeviceFound Callback when devices are found
     * @param onScanComplete Callback when scan completes
     * @param onError Callback on error
     */
    fun startScan(
        deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
        scanDurationMs: Long = config.scanDurationMs,
        includeUnknown: Boolean = false,
        onDeviceFound: (List<ScannedDevice>) -> Unit,
        onScanComplete: (() -> Unit)? = null,
        onError: ((SdkException) -> Unit)? = null
    ) {
        Log.d(TAG, "Starting scan for device types: $deviceTypes, includeUnknown: $includeUnknown")
        _scanState.value = ScanState.SCANNING

        scanner.startScan(
            deviceTypes = deviceTypes,
            scanDurationMs = scanDurationMs,
            includeUnknown = includeUnknown,
            onDeviceFound = { devices ->
                Log.d(TAG, "Found ${devices.size} devices")
                onDeviceFound(devices)
            },
            onScanComplete = {
                Log.d(TAG, "Scan completed")
                _scanState.value = ScanState.IDLE
                onScanComplete?.invoke()
            },
            onError = { error ->
                Log.e(TAG, "Scan error: ${error.message}")
                _scanState.value = ScanState.ERROR
                onError?.invoke(error)
            }
        )
    }

    /**
     * Start scanning with default options (backward compatible).
     */
    fun startScan(
        deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
        scanDurationMs: Long = config.scanDurationMs,
        onDeviceFound: (List<ScannedDevice>) -> Unit,
        onScanComplete: (() -> Unit)? = null,
        onError: ((SdkException) -> Unit)? = null
    ) {
        startScan(deviceTypes, scanDurationMs, false, onDeviceFound, onScanComplete, onError)
    }

    /**
     * Scan for ALL BLE devices (ignoring device type).
     * Useful for debugging when your lock shows as UNKNOWN.
     */
    fun scanAllDevices(
        scanDurationMs: Long = config.scanDurationMs,
        onDeviceFound: (List<ScannedDevice>) -> Unit,
        onScanComplete: (() -> Unit)? = null
    ) {
        Log.d(TAG, "Scanning ALL BLE devices...")
        startScan(
            deviceTypes = setOf(DeviceType.DIGITAL_LOCK, DeviceType.UNKNOWN),
            scanDurationMs = scanDurationMs,
            includeUnknown = true,
            onDeviceFound = onDeviceFound,
            onScanComplete = onScanComplete,
            onError = { Log.e(TAG, "Scan error: ${it.message}") }
        )
    }

    /**
     * Stop the current scan.
     */
    fun stopScan() {
        Log.d(TAG, "Stopping scan")
        scanner.stopScan()
        _scanState.value = ScanState.IDLE
    }

    // =========================================================================
    // DEVICE CONNECTION
    // =========================================================================

    /**
     * Connect to a scanned device.
     *
     * The SDK automatically selects the correct protocol based on the credentials:
     * - G-Series credentials -> G-Series protocol
     * - TT-Series credentials -> TT-Series protocol
     *
     * @param scannedDevice The device to connect to
     * @param credentials Device credentials for authentication
     * @return Result containing the connected device or an error
     */
    suspend fun connectToDevice(
        scannedDevice: ScannedDevice,
        credentials: DeviceCredentials
    ): Result<BluetoothDevice> {
        Log.d(TAG, "Connecting to device: ${scannedDevice.address}")
        Log.d(TAG, "Using protocol: ${credentials.protocol}")

        if (credentials.isExpired()) {
            return Result.failure(SdkException.InvalidCredentialsException())
        }

        // Check if already connected
        connectedDevices[scannedDevice.address]?.let { existingDevice ->
            if (existingDevice.isConnected()) {
                Log.d(TAG, "Device already connected")
                return Result.success(existingDevice)
            }
        }

        val androidDevice = bluetoothAdapter?.getRemoteDevice(scannedDevice.address)
            ?: return Result.failure(SdkException.DeviceNotFoundException(scannedDevice.address))

        // Select device implementation based on credentials protocol
        val device: BluetoothDevice = when {
            credentials.isTTSeries() -> {
                Log.d(TAG, "Creating TT-Series lock instance")
                val lockIdBytes = credentials.getLockIdBytes() ?: byteArrayOf(0, 0, 0, 0)
                TTSeriesDigitalLock(context, androidDevice, credentials, lockIdBytes)
            }
            credentials.isGSeries() -> {
                Log.d(TAG, "Creating G-Series lock instance")
                GSeriesDigitalLock(context, androidDevice, credentials)
            }
            scannedDevice.deviceType == DeviceType.UNKNOWN -> {
                // For UNKNOWN devices, default to G-Series (backward compatible)
                Log.w(TAG, "Connecting to UNKNOWN device type - defaulting to G-Series")
                GSeriesDigitalLock(context, androidDevice, credentials)
            }
            else -> {
                Log.d(TAG, "Creating G-Series lock instance (default)")
                GSeriesDigitalLock(context, androidDevice, credentials)
            }
        }

        val connectResult = device.connect()

        return connectResult.fold(
            onSuccess = {
                connectedDevices[scannedDevice.address] = device
                Log.d(TAG, "Successfully connected to ${scannedDevice.address}")
                Result.success(device)
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to connect: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Connect to a device with explicit protocol selection.
     *
     * @param scannedDevice The device to connect to
     * @param credentials Device credentials for authentication
     * @param protocol Lock protocol to use
     * @return Result containing the connected device or an error
     */
    suspend fun connectToDevice(
        scannedDevice: ScannedDevice,
        credentials: DeviceCredentials,
        protocol: LockProtocol
    ): Result<BluetoothDevice> {
        Log.d(TAG, "Connecting to device: ${scannedDevice.address} with explicit protocol: $protocol")

        if (credentials.isExpired()) {
            return Result.failure(SdkException.InvalidCredentialsException())
        }

        // Check if already connected
        connectedDevices[scannedDevice.address]?.let { existingDevice ->
            if (existingDevice.isConnected()) {
                Log.d(TAG, "Device already connected")
                return Result.success(existingDevice)
            }
        }

        val androidDevice = bluetoothAdapter?.getRemoteDevice(scannedDevice.address)
            ?: return Result.failure(SdkException.DeviceNotFoundException(scannedDevice.address))

        // Select device implementation based on explicit protocol
        val device: BluetoothDevice = when (protocol) {
            LockProtocol.TT_SERIES -> {
                Log.d(TAG, "Creating TT-Series lock instance (explicit)")
                val lockIdBytes = credentials.getLockIdBytes() ?: byteArrayOf(0, 0, 0, 0)
                TTSeriesDigitalLock(context, androidDevice, credentials, lockIdBytes)
            }
            LockProtocol.G_SERIES -> {
                Log.d(TAG, "Creating G-Series lock instance (explicit)")
                GSeriesDigitalLock(context, androidDevice, credentials)
            }
            LockProtocol.AUTO_DETECT -> {
                // Auto-detect based on credentials
                if (credentials.isTTSeries()) {
                    val lockIdBytes = credentials.getLockIdBytes() ?: byteArrayOf(0, 0, 0, 0)
                    TTSeriesDigitalLock(context, androidDevice, credentials, lockIdBytes)
                } else {
                    GSeriesDigitalLock(context, androidDevice, credentials)
                }
            }
        }

        val connectResult = device.connect()

        return connectResult.fold(
            onSuccess = {
                connectedDevices[scannedDevice.address] = device
                Log.d(TAG, "Successfully connected to ${scannedDevice.address}")
                Result.success(device)
            },
            onFailure = { error ->
                Log.e(TAG, "Failed to connect: ${error.message}")
                Result.failure(error)
            }
        )
    }

    /**
     * Disconnect a device.
     */
    suspend fun disconnectDevice(deviceId: String): Result<Unit> {
        Log.d(TAG, "Disconnecting device: $deviceId")

        val device = connectedDevices[deviceId]
            ?: return Result.failure(SdkException.DeviceNotFoundException(deviceId))

        val result = device.disconnect()

        if (result.isSuccess) {
            connectedDevices.remove(deviceId)
            // Cleanup device resources
            when (device) {
                is GSeriesDigitalLock -> device.destroy()
                is TTSeriesDigitalLock -> device.destroy()
            }
        }

        return result
    }

    /**
     * Disconnect all connected devices.
     */
    suspend fun disconnectAll() {
        Log.d(TAG, "Disconnecting all devices")
        connectedDevices.keys.toList().forEach { deviceId ->
            disconnectDevice(deviceId)
        }
    }

    // =========================================================================
    // DEVICE ACCESS
    // =========================================================================

    /**
     * Get a connected device by ID.
     */
    fun getDevice(deviceId: String): BluetoothDevice? = connectedDevices[deviceId]

    /**
     * Get a lockable device by ID.
     */
    fun getLockableDevice(deviceId: String): LockableDevice? {
        return connectedDevices[deviceId] as? LockableDevice
    }

    /**
     * Get a status reporting device by ID.
     */
    fun getStatusReportingDevice(deviceId: String): StatusReportingDevice? {
        return connectedDevices[deviceId] as? StatusReportingDevice
    }

    /**
     * Get a TT-Series lock device by ID (for TT-specific operations).
     */
    fun getTTSeriesLock(deviceId: String): TTSeriesDigitalLock? {
        return connectedDevices[deviceId] as? TTSeriesDigitalLock
    }

    /**
     * Get a G-Series lock device by ID (for G-specific operations).
     */
    fun getGSeriesLock(deviceId: String): GSeriesDigitalLock? {
        return connectedDevices[deviceId] as? GSeriesDigitalLock
    }

    /**
     * Get all connected devices.
     */
    fun getConnectedDevices(): List<BluetoothDevice> = connectedDevices.values.toList()

    /**
     * Get count of connected devices.
     */
    fun getConnectedDeviceCount(): Int = connectedDevices.size

    /**
     * Check what protocol a connected device is using.
     */
    fun getDeviceProtocol(deviceId: String): LockProtocol? {
        return when (connectedDevices[deviceId]) {
            is TTSeriesDigitalLock -> LockProtocol.TT_SERIES
            is GSeriesDigitalLock -> LockProtocol.G_SERIES
            else -> null
        }
    }

    /**
     * Scan states.
     */
    enum class ScanState {
        IDLE,
        SCANNING,
        ERROR
    }
}
