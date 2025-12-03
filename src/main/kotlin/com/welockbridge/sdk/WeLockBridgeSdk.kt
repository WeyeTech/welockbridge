package com.welockbridge.sdk

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
import java.util.UUID

/**
 * WeLockBridge BLE SDK - Main Entry Point
 *
 * Fully configurable - no hardcoded device identifiers.
 * Provide your device patterns via SdkConfig.
 */
class WeLockBridgeSdk private constructor(
  private val context: Context,
  private var config: SdkConfig
) {
  
  companion object {
    private const val TAG = "WeLockBridgeSdk"
    const val VERSION = "1.1.0"
    const val SDK_NAME = "WeLockBridge BLE SDK"
    
    @Volatile
    private var instance: WeLockBridgeSdk? = null
    
    /**
     * Initialize SDK with configuration.
     */
    fun getInstance(context: Context, config: SdkConfig): WeLockBridgeSdk {
      return instance ?: synchronized(this) {
        instance ?: WeLockBridgeSdk(context.applicationContext, config).also {
          instance = it
          Log.i(TAG, "$SDK_NAME v$VERSION initialized")
        }
      }
    }
    
    /**
     * Initialize SDK with empty config (add patterns at runtime).
     */
    fun getInstance(context: Context): WeLockBridgeSdk {
      return getInstance(context, SdkConfig.empty())
    }
    
    fun isInitialized(): Boolean = instance != null
    
    fun reset() {
      instance = null
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
  
  fun getConfig(): SdkConfig = config
  
  fun updateConfig(newConfig: SdkConfig) {
    config = newConfig
    scanner.updateConfig(newConfig)
  }
  
  fun addDeviceMacPrefix(prefix: String) {
    scanner.addMacPrefix(prefix)
  }
  
  fun addDeviceNamePattern(pattern: String) {
    scanner.addNamePattern(pattern)
  }
  
  fun addDeviceServiceUuid(uuid: UUID) {
    scanner.addServiceUuid(uuid)
  }
  
  fun addDeviceServiceUuid(uuidString: String) {
    try {
      addDeviceServiceUuid(UUID.fromString(uuidString))
    } catch (e: Exception) {
      Log.e(TAG, "Invalid UUID: $uuidString")
    }
  }
  
  fun clearRuntimePatterns() {
    scanner.clearRuntimePatterns()
  }
  
  // =========================================================================
  // BLUETOOTH STATUS
  // =========================================================================
  
  fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null
  fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
  
  // =========================================================================
  // SCANNING
  // =========================================================================
  
  fun startScan(
    deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
    scanDurationMs: Long = config.scanDurationMs,
    includeUnknown: Boolean = false,
    onDeviceFound: (List<ScannedDevice>) -> Unit,
    onScanComplete: (() -> Unit)? = null,
    onError: ((SdkException) -> Unit)? = null
  ) {
    _scanState.value = ScanState.SCANNING
    
    scanner.startScan(
      deviceTypes = deviceTypes,
      scanDurationMs = scanDurationMs,
      includeUnknown = includeUnknown,
      onDeviceFound = { onDeviceFound(it) },
      onScanComplete = {
        _scanState.value = ScanState.IDLE
        onScanComplete?.invoke()
      },
      onError = { error ->
        _scanState.value = ScanState.ERROR
        onError?.invoke(error)
      }
    )
  }
  
  fun startScan(
    deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
    scanDurationMs: Long = config.scanDurationMs,
    onDeviceFound: (List<ScannedDevice>) -> Unit,
    onScanComplete: (() -> Unit)? = null,
    onError: ((SdkException) -> Unit)? = null
  ) {
    startScan(deviceTypes, scanDurationMs, false, onDeviceFound, onScanComplete, onError)
  }
  
  fun scanAllDevices(
    scanDurationMs: Long = config.scanDurationMs,
    onDeviceFound: (List<ScannedDevice>) -> Unit,
    onScanComplete: (() -> Unit)? = null
  ) {
    startScan(
      deviceTypes = setOf(DeviceType.DIGITAL_LOCK, DeviceType.UNKNOWN),
      scanDurationMs = scanDurationMs,
      includeUnknown = true,
      onDeviceFound = onDeviceFound,
      onScanComplete = onScanComplete,
      onError = { Log.e(TAG, "Scan error: ${it.message}") }
    )
  }
  
  fun stopScan() {
    scanner.stopScan()
    _scanState.value = ScanState.IDLE
  }
  
  // =========================================================================
  // DEVICE CONNECTION
  // =========================================================================
  
  suspend fun connectToDevice(
    scannedDevice: ScannedDevice,
    credentials: DeviceCredentials
  ): Result<BluetoothDevice> {
    if (credentials.isExpired()) {
      return Result.failure(SdkException.InvalidCredentialsException())
    }
    
    connectedDevices[scannedDevice.address]?.let { existing ->
      if (existing.isConnected()) return Result.success(existing)
    }
    
    val androidDevice = bluetoothAdapter?.getRemoteDevice(scannedDevice.address)
      ?: return Result.failure(SdkException.DeviceNotFoundException(scannedDevice.address))
    
    val device: BluetoothDevice = when (scannedDevice.deviceType) {
      DeviceType.DIGITAL_LOCK -> GSeriesDigitalLock(context, androidDevice, credentials)
      DeviceType.UNKNOWN -> {
        Log.w(TAG, "Connecting to UNKNOWN device as G-Series lock")
        GSeriesDigitalLock(context, androidDevice, credentials)
      }
      else -> return Result.failure(
        SdkException.DeviceNotFoundException("Unsupported type: ${scannedDevice.deviceType}")
      )
    }
    
    return device.connect().fold(
      onSuccess = {
        connectedDevices[scannedDevice.address] = device
        Result.success(device)
      },
      onFailure = { Result.failure(it) }
    )
  }
  
  suspend fun disconnectDevice(deviceId: String): Result<Unit> {
    val device = connectedDevices[deviceId]
      ?: return Result.failure(SdkException.DeviceNotFoundException(deviceId))
    
    val result = device.disconnect()
    if (result.isSuccess) {
      connectedDevices.remove(deviceId)
      if (device is GSeriesDigitalLock) device.destroy()
    }
    return result
  }
  
  suspend fun disconnectAll() {
    connectedDevices.keys.toList().forEach { disconnectDevice(it) }
  }
  
  // =========================================================================
  // DEVICE ACCESS
  // =========================================================================
  
  fun getDevice(deviceId: String): BluetoothDevice? = connectedDevices[deviceId]
  
  fun getLockableDevice(deviceId: String): LockableDevice? {
    return connectedDevices[deviceId] as? LockableDevice
  }
  
  fun getStatusReportingDevice(deviceId: String): StatusReportingDevice? {
    return connectedDevices[deviceId] as? StatusReportingDevice
  }
  
  fun getConnectedDevices(): List<BluetoothDevice> = connectedDevices.values.toList()
  fun getConnectedDeviceCount(): Int = connectedDevices.size
  
  enum class ScanState { IDLE, SCANNING, ERROR }
}
