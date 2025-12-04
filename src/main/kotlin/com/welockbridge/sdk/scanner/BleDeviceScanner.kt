package com.welockbridge.sdk.scanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.welockbridge.sdk.core.DeviceType
import com.welockbridge.sdk.core.LockProtocol
import com.welockbridge.sdk.core.ScannedDevice
import com.welockbridge.sdk.core.SdkConfig
import com.welockbridge.sdk.core.SdkException
import kotlinx.coroutines.*
import java.util.UUID

/**
 * WeLockBridge SDK - BLE Device Scanner
 *
 * Scans for compatible BLE devices and identifies their type.
 * All identification patterns are configurable via SdkConfig.
 * NO HARDCODED VALUES - fully configurable.
 *
 * UPDATED: Added TT-Series protocol detection
 */
internal class BleDeviceScanner(
  private val context: Context,
  private var config: SdkConfig
) {
  
  companion object {
    private const val TAG = "WeLockBridge.Scanner"
    
    /**
     * TT-Series Lock ID pattern: exactly 8 digits
     * Example: "25390069", "83181001"
     */
    private val TT_SERIES_NAME_PATTERN = Regex("^\\d{8}$")
    
    /**
     * Detect protocol from device name.
     * TT-Series locks advertise their 8-digit Lock ID as the device name.
     */
    fun detectProtocolFromName(name: String?): LockProtocol {
      if (name == null) return LockProtocol.AUTO_DETECT
      
      // TT-Series: 8-digit numeric name (this IS the Lock ID)
      if (TT_SERIES_NAME_PATTERN.matches(name)) {
        return LockProtocol.TT_SERIES
      }
      
      // G-Series patterns (common prefixes)
      val lowerName = name.lowercase()
      if (lowerName.startsWith("g4-") ||
        lowerName.startsWith("g-lock") ||
        lowerName.startsWith("gseries") ||
        lowerName.contains("imz")) {
        return LockProtocol.G_SERIES
      }
      
      return LockProtocol.AUTO_DETECT
    }
    
    /**
     * Check if device name looks like a TT-Series Lock ID.
     */
    fun isTTSeriesLockId(name: String?): Boolean {
      return name != null && TT_SERIES_NAME_PATTERN.matches(name)
    }
  }
  
  private val bluetoothManager: BluetoothManager? =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
  
  private var scanCallback: ScanCallback? = null
  private var isScanning = false
  private val discoveredDevices = mutableMapOf<String, ScannedDevice>()
  private var includeAllDevices = false
  
  // Runtime added patterns (in addition to config)
  private val runtimeMacPrefixes = mutableListOf<String>()
  private val runtimeNamePatterns = mutableListOf<String>()
  private val runtimeServiceUuids = mutableListOf<UUID>()
  
  /**
   * Update configuration.
   */
  fun updateConfig(newConfig: SdkConfig) {
    config = newConfig
    Log.d(TAG, "Config updated: ${config.macPrefixes.size} MAC prefixes, ${config.namePatterns.size} name patterns, ${config.serviceUuids.size} service UUIDs")
  }
  
  /**
   * Add MAC prefix at runtime.
   */
  fun addMacPrefix(prefix: String) {
    val normalized = prefix.uppercase().trim()
    if (normalized.isNotEmpty() && !runtimeMacPrefixes.contains(normalized)) {
      runtimeMacPrefixes.add(normalized)
      Log.d(TAG, "Added runtime MAC prefix: $normalized")
    }
  }
  
  /**
   * Add name pattern at runtime.
   */
  fun addNamePattern(pattern: String) {
    val normalized = pattern.lowercase().trim()
    if (normalized.isNotEmpty() && !runtimeNamePatterns.contains(normalized)) {
      runtimeNamePatterns.add(normalized)
      Log.d(TAG, "Added runtime name pattern: $normalized")
    }
  }
  
  /**
   * Add service UUID at runtime.
   */
  fun addServiceUuid(uuid: UUID) {
    if (!runtimeServiceUuids.contains(uuid)) {
      runtimeServiceUuids.add(uuid)
      Log.d(TAG, "Added runtime service UUID: $uuid")
    }
  }
  
  /**
   * Clear runtime patterns.
   */
  fun clearRuntimePatterns() {
    runtimeMacPrefixes.clear()
    runtimeNamePatterns.clear()
    runtimeServiceUuids.clear()
    Log.d(TAG, "Cleared runtime patterns")
  }
  
  /**
   * Get all effective MAC prefixes (config + runtime).
   */
  private fun getAllMacPrefixes(): List<String> {
    return config.macPrefixes + runtimeMacPrefixes
  }
  
  /**
   * Get all effective name patterns (config + runtime).
   */
  private fun getAllNamePatterns(): List<String> {
    return config.namePatterns + runtimeNamePatterns
  }
  
  /**
   * Get all effective service UUIDs (config + runtime).
   */
  private fun getAllServiceUuids(): List<UUID> {
    return config.serviceUuids + runtimeServiceUuids
  }
  
  /**
   * Start scanning for BLE devices.
   */
  @Suppress("MissingPermission")
  fun startScan(
    deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
    scanDurationMs: Long = config.scanDurationMs,
    includeUnknown: Boolean = false,
    onDeviceFound: (List<ScannedDevice>) -> Unit,
    onScanComplete: () -> Unit,
    onError: (SdkException) -> Unit
  ) {
    Log.d(TAG, "Starting BLE scan (includeUnknown=$includeUnknown)...")
    Log.d(TAG, "Using ${getAllMacPrefixes().size} MAC prefixes, ${getAllNamePatterns().size} name patterns, ${getAllServiceUuids().size} service UUIDs")
    
    if (!hasRequiredPermissions()) {
      onError(SdkException.PermissionDeniedException("Bluetooth permissions required"))
      return
    }
    
    if (bleScanner == null) {
      onError(SdkException.PermissionDeniedException("Bluetooth LE not available"))
      return
    }
    
    if (isScanning) {
      Log.w(TAG, "Scan already in progress")
      return
    }
    
    discoveredDevices.clear()
    isScanning = true
    includeAllDevices = includeUnknown || deviceTypes.contains(DeviceType.UNKNOWN)
    
    scanCallback = object : ScanCallback() {
      override fun onScanResult(callbackType: Int, result: ScanResult?) {
        result?.let { processScanResult(it, deviceTypes, onDeviceFound) }
      }
      
      override fun onBatchScanResults(results: MutableList<ScanResult>?) {
        results?.forEach { processScanResult(it, deviceTypes, onDeviceFound) }
      }
      
      override fun onScanFailed(errorCode: Int) {
        Log.e(TAG, "Scan failed with error code: $errorCode")
        isScanning = false
        onError(SdkException.PermissionDeniedException("Scan failed: $errorCode"))
      }
    }
    
    val scanSettings = ScanSettings.Builder()
      .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
      .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
      .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
      .build()
    
    try {
      bleScanner.startScan(null, scanSettings, scanCallback)
      Log.d(TAG, "Scan started successfully")
      
      CoroutineScope(Dispatchers.Main).launch {
        delay(scanDurationMs)
        stopScan()
        onScanComplete()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to start scan: ${e.message}")
      isScanning = false
      onError(SdkException.PermissionDeniedException("Failed to start scan: ${e.message}"))
    }
  }
  
  /**
   * Backward compatible overload.
   */
  @Suppress("MissingPermission")
  fun startScan(
    deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
    scanDurationMs: Long = config.scanDurationMs,
    onDeviceFound: (List<ScannedDevice>) -> Unit,
    onScanComplete: () -> Unit,
    onError: (SdkException) -> Unit
  ) {
    startScan(deviceTypes, scanDurationMs, false, onDeviceFound, onScanComplete, onError)
  }
  
  /**
   * Stop the current scan.
   */
  @Suppress("MissingPermission")
  fun stopScan() {
    if (!isScanning) return
    
    Log.d(TAG, "Stopping BLE scan...")
    
    scanCallback?.let {
      try {
        bleScanner?.stopScan(it)
      } catch (e: Exception) {
        Log.w(TAG, "Error stopping scan: ${e.message}")
      }
    }
    
    scanCallback = null
    isScanning = false
  }
  
  private fun processScanResult(
    result: ScanResult,
    deviceTypes: Set<DeviceType>,
    onDeviceFound: (List<ScannedDevice>) -> Unit
  ) {
    val device = result.device ?: return
    val address = device.address ?: return
    val rssi = result.rssi
    
    // Filter by signal strength from config
    if (rssi < config.minRssi) return
    
    // Skip if already discovered
    if (discoveredDevices.containsKey(address)) return
    
    val deviceType = identifyDeviceType(result)
    
    @Suppress("MissingPermission")
    val name = device.name
    
    // Detect protocol from device name
    val detectedProtocol = detectProtocolFromName(name)
    
    Log.d(TAG, "Scanned: $address, name=$name, rssi=$rssi, type=$deviceType, protocol=$detectedProtocol")
    
    // Apply filter
    val shouldInclude = when {
      includeAllDevices -> true
      deviceTypes.contains(deviceType) -> true
      deviceType == DeviceType.UNKNOWN && config.autoIdentifyUnknown -> {
        mightBeLock(result)
      }
      // Also include if we detected a specific protocol (TT-Series or G-Series)
      detectedProtocol != LockProtocol.AUTO_DETECT -> true
      else -> false
    }
    
    if (!shouldInclude) return
    
    // If we detected a protocol, mark as DIGITAL_LOCK
    val finalDeviceType = if (detectedProtocol != LockProtocol.AUTO_DETECT && deviceType == DeviceType.UNKNOWN) {
      DeviceType.DIGITAL_LOCK
    } else {
      deviceType
    }
    
    val scannedDevice = ScannedDevice(
      address = address,
      name = name,
      rssi = rssi,
      deviceType = finalDeviceType,
      advertisementData = result.scanRecord?.bytes,
      detectedProtocol = detectedProtocol
    )
    
    discoveredDevices[address] = scannedDevice
    Log.d(TAG, "Found device: $address, name=$name, rssi=$rssi, type=$finalDeviceType, protocol=$detectedProtocol")
    onDeviceFound(discoveredDevices.values.toList())
  }
  
  /**
   * Identifies device type based on configured patterns.
   */
  private fun identifyDeviceType(result: ScanResult): DeviceType {
    val device = result.device
    val scanRecord = result.scanRecord
    
    @Suppress("MissingPermission")
    val name = device?.name?.lowercase() ?: ""
    val address = device?.address?.uppercase() ?: ""
    
    // CHECK 1: MAC Address Prefix
    for (prefix in getAllMacPrefixes()) {
      if (address.startsWith(prefix.uppercase())) {
        Log.d(TAG, "Identified by MAC prefix: $prefix")
        return DeviceType.DIGITAL_LOCK
      }
    }
    
    // CHECK 2: Service UUIDs
    val allServiceUuids = getAllServiceUuids()
    scanRecord?.serviceUuids?.forEach { parcelUuid ->
      if (allServiceUuids.contains(parcelUuid.uuid)) {
        Log.d(TAG, "Identified by service UUID: ${parcelUuid.uuid}")
        return DeviceType.DIGITAL_LOCK
      }
    }
    
    // CHECK 3: Device Name Patterns
    if (name.isNotEmpty()) {
      for (pattern in getAllNamePatterns()) {
        if (name.contains(pattern.lowercase())) {
          Log.d(TAG, "Identified by name pattern: $pattern")
          return DeviceType.DIGITAL_LOCK
        }
      }
    }
    
    return DeviceType.UNKNOWN
  }
  
  /**
   * Heuristic check if an unknown device might be a lock.
   */
  private fun mightBeLock(result: ScanResult): Boolean {
    val scanRecord = result.scanRecord ?: return false
    
    // Has manufacturer data (common for locks)
    if (scanRecord.manufacturerSpecificData?.size() ?: 0 > 0) return true
    
    // Has service data
    if (scanRecord.serviceData?.isNotEmpty() == true) return true
    
    // Has any service UUIDs
    if (scanRecord.serviceUuids?.isNotEmpty() == true) return true
    
    return false
  }
  
  private fun hasRequiredPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
          PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
    }
  }
  
  fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
  
  fun getDiscoveredDevices(): List<ScannedDevice> = discoveredDevices.values.toList()
  
  fun getConfig(): SdkConfig = config
}
