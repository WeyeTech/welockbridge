package com.welockbridge.sdk.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.welockbridge.sdk.WeLockBridgeSdk
import com.welockbridge.sdk.core.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * WeLockBridge SDK - Example Activity
 *
 * Demonstrates how to use the WeLockBridge BLE SDK with both:
 * - G-Series locks (IMZ BLE G-Series Protocol)
 * - TT-Series locks (TOTARGET BLE-ELOCK Protocol)
 *
 * Features demonstrated:
 * - Request Bluetooth permissions
 * - Scan for BLE devices
 * - Connect to locks (both protocols)
 * - Lock/unlock operations
 * - Status monitoring
 * - TT-specific operations (version check, time calibration)
 */
class ExampleActivity : AppCompatActivity() {
  
  companion object {
    private const val TAG = "WeLockBridge.Example"
  }
  
  private lateinit var sdk: WeLockBridgeSdk
  private var connectedDevice: LockableDevice? = null
  private var detectedProtocol: LockProtocol? = null
  
  // Permission launcher for Android 12+
  private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { permissions ->
    val allGranted = permissions.values.all { it }
    if (allGranted) {
      Log.d(TAG, "All permissions granted")
      startScanning()
    } else {
      Log.e(TAG, "Some permissions denied")
      showToast("Bluetooth permissions are required")
    }
  }
  
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Initialize SDK with custom config
    val config = SdkConfig.Builder()
      // Add patterns for G-Series locks
      .addMacPrefix("DC:0D")
      .addNamePattern("g4-")
      // Add patterns for TT-Series locks
      .addNamePattern("tt-")
      .addNamePattern("elock")
      .addNamePattern("totarget")
      // Common service UUID (Nordic UART)
      .addServiceUuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
      .build()
    
    sdk = WeLockBridgeSdk.getInstance(this, config)
    
    Log.i(TAG, "${WeLockBridgeSdk.SDK_NAME} v${WeLockBridgeSdk.VERSION}")
    
    // Check and request permissions
    if (hasRequiredPermissions()) {
      startScanning()
    } else {
      requestPermissions()
    }
    
    // Observe scan state
    lifecycleScope.launch {
      sdk.scanState.collectLatest { state ->
        Log.d(TAG, "Scan state: $state")
      }
    }
  }
  
  override fun onDestroy() {
    super.onDestroy()
    
    // Cleanup
    lifecycleScope.launch {
      sdk.disconnectAll()
    }
  }
  
  private fun hasRequiredPermissions(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
          PackageManager.PERMISSION_GRANTED &&
          ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
          PackageManager.PERMISSION_GRANTED
    } else {
      ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED
    }
  }
  
  private fun requestPermissions() {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
      )
    } else {
      arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    
    permissionLauncher.launch(permissions)
  }
  
  private fun startScanning() {
    Log.d(TAG, "Starting BLE scan...")
    
    sdk.startScan(
      deviceTypes = setOf(DeviceType.DIGITAL_LOCK),
      scanDurationMs = 15000L,
      onDeviceFound = { devices ->
        Log.d(TAG, "Found ${devices.size} devices:")
        devices.forEach { device ->
          Log.d(TAG, "  - ${device.name ?: "Unknown"} (${device.address}) RSSI: ${device.rssi}")
        }
        
        // Auto-connect to first lock found
        val lock = devices.firstOrNull { it.deviceType == DeviceType.DIGITAL_LOCK }
        if (lock != null && connectedDevice == null) {
          sdk.stopScan()
          connectToDevice(lock)
        }
      },
      onScanComplete = {
        Log.d(TAG, "Scan completed")
        if (connectedDevice == null) {
          showToast("No locks found")
        }
      },
      onError = { error ->
        Log.e(TAG, "Scan error: ${error.message}")
        showToast("Scan failed: ${error.message}")
      }
    )
  }
  
  /**
   * Connect to a device - demonstrates both G-Series and TT-Series connection.
   *
   * IMPORTANT: TT-Series devices advertise their 8-digit Lock ID as the device name!
   * Example: Device named "25390069" is a TT-Series lock with Lock ID "25390069"
   */
  private fun connectToDevice(scannedDevice: ScannedDevice) {
    Log.d(TAG, "Connecting to ${scannedDevice.address}...")
    Log.d(TAG, "Device name: ${scannedDevice.name}")
    Log.d(TAG, "Detected protocol from scanner: ${scannedDevice.detectedProtocol}")
    
    lifecycleScope.launch {
      // Use ProtocolDetector to determine protocol
      val protocol = ProtocolDetector.detectFromDevice(scannedDevice)
      Log.d(TAG, "Final detected protocol: $protocol")
      
      val credentials = when (protocol) {
        LockProtocol.TT_SERIES -> {
          // TT-Series: Device name IS the Lock ID!
          val lockId = ProtocolDetector.extractTTSeriesLockId(scannedDevice.name)
          if (lockId == null) {
            showToast("Cannot extract Lock ID from device name")
            return@launch
          }
          
          Log.d(TAG, "Using TT-Series protocol with Lock ID: $lockId")
          detectedProtocol = LockProtocol.TT_SERIES
          
          DeviceCredentials.forTTSeries(
            lockId = lockId,       // Use device name as Lock ID!
            password = "112233"    // Replace with actual password from your lock
            // Optional: add encryption key if device uses encryption
            // encryptionKey = yourAesKey
          ).getOrElse {
            showToast("Invalid TT credentials: ${it.message}")
            return@launch
          }
        }
        
        LockProtocol.G_SERIES -> {
          // G-Series credentials
          Log.d(TAG, "Using G-Series protocol")
          detectedProtocol = LockProtocol.G_SERIES
          DeviceCredentials.withDefaultKey()
          
          // In production, use:
          // val key = "YOUR_16_BYTE_KEY!".toByteArray()
          // DeviceCredentials.withKeyAndPassword(key, "YOUR_PASSWORD").getOrThrow()
        }
        
        LockProtocol.AUTO_DETECT -> {
          // Unknown protocol - try G-Series as fallback
          Log.w(TAG, "Unknown protocol for device: ${scannedDevice.name}")
          Log.w(TAG, "Trying G-Series as fallback")
          detectedProtocol = LockProtocol.G_SERIES
          DeviceCredentials.withDefaultKey()
        }
      }
      
      val result = sdk.connectToDevice(scannedDevice, credentials)
      
      result.fold(
        onSuccess = { device ->
          Log.d(TAG, "Connected successfully!")
          showToast("Connected to ${device.deviceName}")
          
          connectedDevice = sdk.getLockableDevice(device.deviceId)
          
          // Observe lock state
          connectedDevice?.let { lockDevice ->
            observeLockState(lockDevice)
            
            // If TT-Series, demonstrate TT-specific features
            if (detectedProtocol == LockProtocol.TT_SERIES) {
              demonstrateTTFeatures(device.deviceId)
            }
          }
        },
        onFailure = { error ->
          Log.e(TAG, "Connection failed: ${error.message}")
          showToast("Connection failed: ${error.message}")
        }
      )
    }
  }
  
  /**
   * Demonstrate TT-Series specific features.
   */
  private fun demonstrateTTFeatures(deviceId: String) {
    val ttLock = sdk.getTTSeriesLock(deviceId) ?: return
    
    lifecycleScope.launch {
      // Get firmware version
      ttLock.getVersion().fold(
        onSuccess = { version ->
          Log.d(TAG, "TT Lock Firmware: $version")
          showToast("Firmware: $version")
        },
        onFailure = { error ->
          Log.w(TAG, "Failed to get version: ${error.message}")
        }
      )
      
      // Get detected lock ID
      val lockIdString = ttLock.getLockIdString()
      if (lockIdString != null) {
        Log.d(TAG, "TT Lock ID: $lockIdString")
      }
      
      // Get battery level
      val battery = ttLock.getBatteryLevel()
      if (battery >= 0) {
        Log.d(TAG, "Battery: $battery%")
      }
    }
  }
  
  private fun observeLockState(device: LockableDevice) {
    lifecycleScope.launch {
      device.lockState.collectLatest { state ->
        Log.d(TAG, "Lock state changed: $state")
        
        when (state) {
          LockState.LOCKED -> showToast("Device is LOCKED")
          LockState.UNLOCKED -> showToast("Device is UNLOCKED")
          LockState.UNKNOWN -> Log.d(TAG, "Lock state unknown")
        }
      }
    }
    
    lifecycleScope.launch {
      device.connectionState.collectLatest { state ->
        Log.d(TAG, "Connection state: $state")
        
        if (state is ConnectionState.Disconnected || state is ConnectionState.Error) {
          connectedDevice = null
          detectedProtocol = null
        }
      }
    }
  }
  
  // =========================================================================
  // PUBLIC API - Call these methods from your UI buttons
  // =========================================================================
  
  /**
   * Unlock the connected device.
   * Works with both G-Series and TT-Series locks.
   */
  fun unlockDevice() {
    val device = connectedDevice ?: run {
      showToast("No device connected")
      return
    }
    
    lifecycleScope.launch {
      Log.d(TAG, "Unlocking (protocol: $detectedProtocol)...")
      
      val result = device.unlock()
      
      result.fold(
        onSuccess = { success ->
          Log.d(TAG, "Unlock ${if (success) "successful" else "failed"}")
        },
        onFailure = { error ->
          Log.e(TAG, "Unlock error: ${error.message}")
          showToast("Unlock failed: ${error.message}")
        }
      )
    }
  }
  
  /**
   * Lock the connected device.
   * Works with both G-Series and TT-Series locks.
   */
  fun lockDevice() {
    val device = connectedDevice ?: run {
      showToast("No device connected")
      return
    }
    
    lifecycleScope.launch {
      Log.d(TAG, "Locking (protocol: $detectedProtocol)...")
      
      val result = device.lock()
      
      result.fold(
        onSuccess = { success ->
          Log.d(TAG, "Lock ${if (success) "successful" else "failed"}")
        },
        onFailure = { error ->
          Log.e(TAG, "Lock error: ${error.message}")
          showToast("Lock failed: ${error.message}")
        }
      )
    }
  }
  
  /**
   * Query the device status.
   * Works with both G-Series and TT-Series locks.
   */
  fun queryStatus() {
    val device = connectedDevice ?: run {
      showToast("No device connected")
      return
    }
    
    lifecycleScope.launch {
      val result = device.queryLockStatus()
      
      result.fold(
        onSuccess = { state ->
          Log.d(TAG, "Current state: $state")
          showToast("Status: $state")
        },
        onFailure = { error ->
          Log.e(TAG, "Query error: ${error.message}")
        }
      )
    }
  }
  
  /**
   * Query full device status including battery.
   */
  fun queryFullStatus() {
    val device = connectedDevice?.let { sdk.getStatusReportingDevice(it.deviceId) }
    if (device == null) {
      showToast("No device connected")
      return
    }
    
    lifecycleScope.launch {
      device.queryStatus().fold(
        onSuccess = { status ->
          val msg = "Lock: ${status.lockState}, Battery: ${status.batteryLevel}%"
          Log.d(TAG, msg)
          showToast(msg)
        },
        onFailure = { error ->
          Log.e(TAG, "Status query failed: ${error.message}")
        }
      )
    }
  }
  
  /**
   * Set TT-Series work mode (TT-specific).
   *
   * @param sleepMode true for sleep mode (lower power), false for real-time mode
   */
  fun setTTWorkMode(sleepMode: Boolean) {
    val device = connectedDevice ?: run {
      showToast("No device connected")
      return
    }
    
    if (detectedProtocol != LockProtocol.TT_SERIES) {
      showToast("This feature is only for TT-Series locks")
      return
    }
    
    val ttLock = sdk.getTTSeriesLock(device.deviceId) ?: return
    
    lifecycleScope.launch {
      ttLock.setWorkMode(sleepMode).fold(
        onSuccess = {
          val mode = if (sleepMode) "Sleep" else "Real-time"
          showToast("Work mode set to: $mode")
        },
        onFailure = { error ->
          showToast("Failed: ${error.message}")
        }
      )
    }
  }
  
  /**
   * Disconnect the connected device.
   */
  fun disconnectDevice() {
    val device = connectedDevice ?: return
    
    lifecycleScope.launch {
      sdk.disconnectDevice(device.deviceId)
      connectedDevice = null
      detectedProtocol = null
      showToast("Disconnected")
    }
  }
  
  /**
   * Get current protocol being used.
   */
  fun getCurrentProtocol(): LockProtocol? = detectedProtocol
  
  private fun showToast(message: String) {
    runOnUiThread {
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
  }
}

// ============================================================================
// EXAMPLE: Direct connection with known credentials
// ============================================================================

/**
 * Example showing how to connect directly to a known device.
 */
class DirectConnectionExample {
  
  suspend fun connectToGSeriesLock(sdk: WeLockBridgeSdk, macAddress: String) {
    // G-Series credentials
    val encryptionKey = "123456789012346".toByteArray(Charsets.US_ASCII) // 16 bytes
    val credentials = DeviceCredentials.withKeyAndPassword(
      key = encryptionKey,
      password = "12341234"
    ).getOrThrow()
    
    // Create ScannedDevice manually
    val device = ScannedDevice(
      address = macAddress,
      name = "G4-Lock",
      rssi = -60,
      deviceType = DeviceType.DIGITAL_LOCK
    )
    
    sdk.connectToDevice(device, credentials).fold(
      onSuccess = { lock ->
        println("G-Series lock connected: ${lock.deviceName}")
      },
      onFailure = { error ->
        println("Connection failed: ${error.message}")
      }
    )
  }
  
  suspend fun connectToTTSeriesLock(sdk: WeLockBridgeSdk, macAddress: String) {
    // TT-Series credentials
    val credentials = DeviceCredentials.forTTSeries(
      lockId = "83181001",   // 8-digit lock ID
      password = "112233"    // 6-digit password
      // Optional: encryptionKey for encrypted mode
    ).getOrThrow()
    
    // Create ScannedDevice manually
    val device = ScannedDevice(
      address = macAddress,
      name = "TT-ELock",
      rssi = -60,
      deviceType = DeviceType.DIGITAL_LOCK
    )
    
    sdk.connectToDevice(device, credentials).fold(
      onSuccess = { lock ->
        println("TT-Series lock connected: ${lock.deviceName}")
        
        // Access TT-specific features
        val ttLock = sdk.getTTSeriesLock(lock.deviceId)
        ttLock?.let {
          it.getVersion().onSuccess { version ->
            println("Firmware: $version")
          }
          println("Lock ID: ${it.getLockIdString()}")
          println("Battery: ${it.getBatteryLevel()}%")
        }
      },
      onFailure = { error ->
        println("Connection failed: ${error.message}")
      }
    )
  }
}
