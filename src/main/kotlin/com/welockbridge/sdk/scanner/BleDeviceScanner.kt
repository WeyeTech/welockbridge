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
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.welockbridge.sdk.core.DeviceType
import com.welockbridge.sdk.core.ScannedDevice
import com.welockbridge.sdk.core.SdkException
import kotlinx.coroutines.*
import java.util.UUID

/**
 * WeLockBridge SDK - BLE Device Scanner
 * 
 * Scans for compatible BLE devices and identifies their type.
 */
internal class BleDeviceScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "WeLockBridge.Scanner"
        private const val DEFAULT_SCAN_DURATION_MS = 30000L
        private const val MIN_RSSI = -80
        
        // Known service UUIDs for device identification
        private val NORDIC_UART_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val CUSTOM_LOCK_SERVICE = UUID.fromString("8e400001-f315-4f60-9fb8-838830daea50")
        private val GENERIC_LOCK_SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        
        // Known MAC address prefixes for G-Series locks
        private val KNOWN_MAC_PREFIXES = listOf(
            "DC:0D", "DC:71", "E8:31", "F4:B8", "CC:DB"
        )
    }
    
    private val bluetoothManager: BluetoothManager? = 
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner
    
    private var scanCallback: ScanCallback? = null
    private var isScanning = false
    private val discoveredDevices = mutableMapOf<String, ScannedDevice>()
    
    /**
     * Start scanning for BLE devices.
     */
    @Suppress("MissingPermission")
    fun startScan(
        deviceTypes: Set<DeviceType> = setOf(DeviceType.DIGITAL_LOCK),
        scanDurationMs: Long = DEFAULT_SCAN_DURATION_MS,
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
        
        val scanFilters = buildScanFilters()
        
        try {
            bleScanner.startScan(scanFilters, scanSettings, scanCallback)
            Log.d(TAG, "Scan started successfully")
            
            // Schedule scan stop
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
        
        // Filter by signal strength
        if (rssi < MIN_RSSI) return
        
        // Skip if already discovered
        if (discoveredDevices.containsKey(address)) return
        
        val deviceType = identifyDeviceType(result)
        
        // Filter by requested device types
        if (!deviceTypes.contains(deviceType) && deviceType != DeviceType.UNKNOWN) {
            if (!deviceTypes.contains(DeviceType.DIGITAL_LOCK) || deviceType != DeviceType.DIGITAL_LOCK) {
                return
            }
        }
        
        @Suppress("MissingPermission")
        val name = device.name
        
        val scannedDevice = ScannedDevice(
            address = address,
            name = name,
            rssi = rssi,
            deviceType = deviceType,
            advertisementData = result.scanRecord?.bytes
        )
        
        discoveredDevices[address] = scannedDevice
        
        Log.d(TAG, "Found device: $address, name=$name, rssi=$rssi, type=$deviceType")
        
        onDeviceFound(discoveredDevices.values.toList())
    }
    
    private fun identifyDeviceType(result: ScanResult): DeviceType {
        val device = result.device
        val scanRecord = result.scanRecord
        
        @Suppress("MissingPermission")
        val name = device?.name?.lowercase() ?: ""
        val address = device?.address?.uppercase() ?: ""
        
        // Check MAC address prefix
        for (prefix in KNOWN_MAC_PREFIXES) {
            if (address.startsWith(prefix)) {
                return DeviceType.DIGITAL_LOCK
            }
        }
        
        // Check service UUIDs
        scanRecord?.serviceUuids?.forEach { parcelUuid ->
            when (parcelUuid.uuid) {
                NORDIC_UART_SERVICE,
                CUSTOM_LOCK_SERVICE,
                GENERIC_LOCK_SERVICE -> return DeviceType.DIGITAL_LOCK
            }
        }
        
        // Check device name patterns
        val lockPatterns = listOf("g4-", "g-series", "lock", "bander", "smart lock", "welock")
        for (pattern in lockPatterns) {
            if (name.contains(pattern)) {
                return DeviceType.DIGITAL_LOCK
            }
        }
        
        // Check for GPS tracker patterns
        val gpsPatterns = listOf("gps", "tracker", "gf-", "gt-")
        for (pattern in gpsPatterns) {
            if (name.contains(pattern)) {
                return DeviceType.GPS_TRACKER
            }
        }
        
        return DeviceType.UNKNOWN
    }
    
    private fun buildScanFilters(): List<ScanFilter> {
        // Return empty list to scan all devices
        // The filtering is done in processScanResult
        return emptyList()
    }
    
    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == 
                PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            // Android 11 and below
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == 
                PackageManager.PERMISSION_GRANTED
        }
    }
    
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
    
    fun getDiscoveredDevices(): List<ScannedDevice> = discoveredDevices.values.toList()
}
