package com.welockbridge.sdk

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.welockbridge.sdk.core.*
import com.welockbridge.sdk.device.GSeriesDigitalLock
import com.welockbridge.sdk.scanner.BleDeviceScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WeLockBridge BLE SDK
 * 
 * Main entry point for the SDK. Provides a clean public API for:
 * - Scanning for BLE devices
 * - Connecting to devices
 * - Managing device lifecycle
 * - Accessing device-specific functionality
 * 
 * Usage:
 * ```kotlin
 * val sdk = WeLockBridgeSdk.getInstance(context)
 * 
 * // Scan for devices
 * sdk.startScan(
 *     deviceTypes = setOf(DeviceType.DIGITAL_LOCK),
 *     onDeviceFound = { devices -> updateUI(devices) }
 * )
 * 
 * // Connect to a device
 * val credentials = DeviceCredentials.withKeyAndPassword(key, password).getOrThrow()
 * val device = sdk.connectToDevice(scannedDevice, credentials).getOrThrow()
 * 
 * // Lock/Unlock
 * val lockDevice = sdk.getLockableDevice(device.deviceId)
 * lockDevice?.unlock()
 * ```
 * 
 * @author WeLockBridge Team
 * @version 1.0.0
 */
class WeLockBridgeSdk private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "WeLockBridgeSdk"
        const val VERSION = "1.0.0"
        const val SDK_NAME = "WeLockBridge BLE SDK"
        
        @Volatile
        private var instance: WeLockBridgeSdk? = null
        
        /**
         * Get the singleton instance of the SDK.
         * 
         * @param context Application context (will be converted to application context)
         * @return SDK instance
         */
        fun getInstance(context: Context): WeLockBridgeSdk {
            return instance ?: synchronized(this) {
                instance ?: WeLockBridgeSdk(context.applicationContext).also {
                    instance = it
                    Log.i(TAG, "$SDK_NAME v$VERSION initialized")
                }
            }
        }
    }
    
    private val bluetoothManager: BluetoothManager? = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    
    private val scanner = BleDeviceScanner(context)
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    
    private val _scanState = MutableStateFlow(ScanState.IDLE)
    val scanState: Flow<ScanState> = _scanState.asStateFlow()
    
    /**
     * Check if Bluetooth is available on this device.
     */
    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
    
    /**
     * Check if Bluetooth is currently enabled.
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
    
    /**
     * Start scanning for BLE devices.
     * 
     * @param deviceTypes Types of devices to scan for (default: DIGITAL_LOCK)
     * @param scanDurationMs Scan duration in milliseconds (default: 30000)
     * @param onDeviceFound Callback when devices are found
     * @param onScanComplete Callback when scan completes
     * @param onError Callback on error
     */
    fun startScan(
        deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
        scanDurationMs: Long = 30000L,
        onDeviceFound: (List<ScannedDevice>) -> Unit,
        onScanComplete: (() -> Unit)? = null,
        onError: ((SdkException) -> Unit)? = null
    ) {
        Log.d(TAG, "Starting scan for device types: $deviceTypes")
        _scanState.value = ScanState.SCANNING
        
        scanner.startScan(
            deviceTypes = deviceTypes,
            scanDurationMs = scanDurationMs,
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
     * Stop the current scan.
     */
    fun stopScan() {
        Log.d(TAG, "Stopping scan")
        scanner.stopScan()
        _scanState.value = ScanState.IDLE
    }
    
    /**
     * Connect to a scanned device.
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
        
        val device: BluetoothDevice = when (scannedDevice.deviceType) {
            DeviceType.DIGITAL_LOCK -> GSeriesDigitalLock(context, androidDevice, credentials)
            else -> return Result.failure(
                SdkException.DeviceNotFoundException("Unsupported device type: ${scannedDevice.deviceType}")
            )
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
     * 
     * @param deviceId Device ID (MAC address) to disconnect
     */
    suspend fun disconnectDevice(deviceId: String): Result<Unit> {
        Log.d(TAG, "Disconnecting device: $deviceId")
        
        val device = connectedDevices[deviceId]
            ?: return Result.failure(SdkException.DeviceNotFoundException(deviceId))
        
        val result = device.disconnect()
        
        if (result.isSuccess) {
            connectedDevices.remove(deviceId)
            
            // Cleanup
            if (device is GSeriesDigitalLock) {
                device.destroy()
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
    
    /**
     * Get a connected device by ID.
     * 
     * @param deviceId Device ID (MAC address)
     * @return The device or null if not connected
     */
    fun getDevice(deviceId: String): BluetoothDevice? = connectedDevices[deviceId]
    
    /**
     * Get a lockable device by ID.
     * 
     * @param deviceId Device ID (MAC address)
     * @return The lockable device or null if not found/not lockable
     */
    fun getLockableDevice(deviceId: String): LockableDevice? {
        return connectedDevices[deviceId] as? LockableDevice
    }
    
    /**
     * Get a status reporting device by ID.
     * 
     * @param deviceId Device ID (MAC address)
     * @return The status reporting device or null
     */
    fun getStatusReportingDevice(deviceId: String): StatusReportingDevice? {
        return connectedDevices[deviceId] as? StatusReportingDevice
    }
    
    /**
     * Get all connected devices.
     * 
     * @return List of connected devices
     */
    fun getConnectedDevices(): List<BluetoothDevice> = connectedDevices.values.toList()
    
    /**
     * Get count of connected devices.
     */
    fun getConnectedDeviceCount(): Int = connectedDevices.size
    
    /**
     * Scan states.
     */
    enum class ScanState {
        IDLE,
        SCANNING,
        ERROR
    }
}
