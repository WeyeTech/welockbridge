package com.welockbridge.sdk.scanner

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.welockbridge.sdk.core.DeviceType
import com.welockbridge.sdk.core.ScannedDevice
import com.welockbridge.sdk.core.SdkConfig
import com.welockbridge.sdk.core.SdkException
import kotlinx.coroutines.*
import java.util.UUID

/**
 * BLE Device Scanner - Fully Configurable
 * NO HARDCODED VALUES - All patterns from SdkConfig
 */
internal class BleDeviceScanner(
  private val context: Context,
  private var config: SdkConfig
) {
  
  companion object {
    private const val TAG = "WeLockBridge.Scanner"
  }
  
  private val bluetoothManager: BluetoothManager? =
    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
  
  private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
  private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
  
  private var scanCallback: ScanCallback? = null
  private var isScanning = false
  private val discoveredDevices = mutableMapOf<String, ScannedDevice>()
  private var includeAllDevices = false
  
  private val runtimeMacPrefixes = mutableListOf<String>()
  private val runtimeNamePatterns = mutableListOf<String>()
  private val runtimeServiceUuids = mutableListOf<UUID>()
  
  fun updateConfig(newConfig: SdkConfig) {
    config = newConfig
    Log.d(TAG, "Config updated")
  }
  
  fun addMacPrefix(prefix: String) {
    val normalized = prefix.uppercase().trim()
    if (normalized.isNotEmpty() && !runtimeMacPrefixes.contains(normalized)) {
      runtimeMacPrefixes.add(normalized)
      Log.d(TAG, "Added runtime MAC prefix: $normalized")
    }
  }
  
  fun addNamePattern(pattern: String) {
    val normalized = pattern.lowercase().trim()
    if (normalized.isNotEmpty() && !runtimeNamePatterns.contains(normalized)) {
      runtimeNamePatterns.add(normalized)
      Log.d(TAG, "Added runtime name pattern: $normalized")
    }
  }
  
  fun addServiceUuid(uuid: UUID) {
    if (!runtimeServiceUuids.contains(uuid)) {
      runtimeServiceUuids.add(uuid)
      Log.d(TAG, "Added runtime service UUID: $uuid")
    }
  }
  
  fun clearRuntimePatterns() {
    runtimeMacPrefixes.clear()
    runtimeNamePatterns.clear()
    runtimeServiceUuids.clear()
    Log.d(TAG, "Cleared runtime patterns")
  }
  
  private fun getAllMacPrefixes(): List<String> = config.macPrefixes + runtimeMacPrefixes
  private fun getAllNamePatterns(): List<String> = config.namePatterns + runtimeNamePatterns
  private fun getAllServiceUuids(): List<UUID> = config.serviceUuids + runtimeServiceUuids
  
  @Suppress("MissingPermission")
  fun startScan(
    deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
    scanDurationMs: Long = config.scanDurationMs,
    includeUnknown: Boolean = false,
    onDeviceFound: (List<ScannedDevice>) -> Unit,
    onScanComplete: () -> Unit,
    onError: (SdkException) -> Unit
  ) {
    Log.d(TAG, "Starting BLE scan...")
    
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
        Log.e(TAG, "Scan failed: $errorCode")
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
      Log.d(TAG, "Scan started")
      
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
  
  @Suppress("MissingPermission")
  fun stopScan() {
    if (!isScanning) return
    
    Log.d(TAG, "Stopping scan...")
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
    
    if (rssi < config.minRssi) return
    if (discoveredDevices.containsKey(address)) return
    
    val deviceType = identifyDeviceType(result)
    
    @Suppress("MissingPermission")
    val name = device.name
    Log.d(TAG, "Scanned: $address, name=$name, rssi=$rssi, type=$deviceType")
    
    val shouldInclude = when {
      includeAllDevices -> true
      deviceTypes.contains(deviceType) -> true
      deviceType == DeviceType.UNKNOWN && config.autoIdentifyUnknown -> mightBeLock(result)
      else -> false
    }
    
    if (!shouldInclude) return
    
    val scannedDevice = ScannedDevice(
      address = address,
      name = name,
      rssi = rssi,
      deviceType = deviceType,
      advertisementData = result.scanRecord?.bytes
    )
    
    discoveredDevices[address] = scannedDevice
    Log.d(TAG, "Found device: $address")
    onDeviceFound(discoveredDevices.values.toList())
  }
  
  private fun identifyDeviceType(result: ScanResult): DeviceType {
    val device = result.device
    val scanRecord = result.scanRecord
    
    @Suppress("MissingPermission")
    val name = device?.name?.lowercase() ?: ""
    val address = device?.address?.uppercase() ?: ""
    
    // Check MAC prefix
    for (prefix in getAllMacPrefixes()) {
      if (address.startsWith(prefix.uppercase())) {
        Log.d(TAG, "Identified by MAC: $prefix")
        return DeviceType.DIGITAL_LOCK
      }
    }
    
    // Check service UUIDs
    scanRecord?.serviceUuids?.forEach { parcelUuid ->
      if (getAllServiceUuids().contains(parcelUuid.uuid)) {
        Log.d(TAG, "Identified by UUID: ${parcelUuid.uuid}")
        return DeviceType.DIGITAL_LOCK
      }
    }
    
    // Check name patterns
    if (name.isNotEmpty()) {
      for (pattern in getAllNamePatterns()) {
        if (name.contains(pattern.lowercase())) {
          Log.d(TAG, "Identified by name: $pattern")
          return DeviceType.DIGITAL_LOCK
        }
      }
    }
    
    return DeviceType.UNKNOWN
  }
  
  private fun mightBeLock(result: ScanResult): Boolean {
    val scanRecord = result.scanRecord ?: return false
    if (scanRecord.manufacturerSpecificData?.size() ?: 0 > 0) return true
    if (scanRecord.serviceData?.isNotEmpty() == true) return true
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