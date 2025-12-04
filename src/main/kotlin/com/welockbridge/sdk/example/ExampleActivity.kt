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
 * Demonstrates how to use the WeLockBridge BLE SDK to:
 * - Request Bluetooth permissions
 * - Scan for BLE devices
 * - Connect to a digital lock
 * - Lock/unlock the device
 * - Observe device state changes
 */
class ExampleActivity : AppCompatActivity() {
  
  companion object {
    private const val TAG = "WeLockBridge.Example"
  }
  
  private lateinit var sdk: WeLockBridgeSdk
  private var connectedDevice: LockableDevice? = null
  
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
    
    // Initialize SDK
    sdk = WeLockBridgeSdk.getInstance(this)
    
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
  
  private fun connectToDevice(scannedDevice: ScannedDevice) {
    Log.d(TAG, "Connecting to ${scannedDevice.address}...")
    
    lifecycleScope.launch {
      // Create credentials
      // In production, get these from secure storage
      val credentials = DeviceCredentials.withDefaultKey()
      
      // Or use custom credentials:
      // val key = "YOUR_16_BYTE_KEY!".toByteArray()
      // val credentials = DeviceCredentials.withKeyAndPassword(key, "YOUR_8_BYTE_KEY").getOrThrow()
      
      val result = sdk.connectToDevice(scannedDevice, credentials)
      
      result.fold(
        onSuccess = { device ->
          Log.d(TAG, "Connected successfully!")
          showToast("Connected to ${device.deviceName}")
          
          connectedDevice = sdk.getLockableDevice(device.deviceId)
          
          // Observe lock state
          connectedDevice?.let { lockDevice ->
            observeLockState(lockDevice)
            
            // Example: Unlock after connection
            // unlockDevice(lockDevice)
          }
        },
        onFailure = { error ->
          Log.e(TAG, "Connection failed: ${error.message}")
          showToast("Connection failed: ${error.message}")
        }
      )
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
        }
      }
    }
  }
  
  // Call these methods from your UI buttons
  
  fun unlockDevice() {
    val device = connectedDevice ?: run {
      showToast("No device connected")
      return
    }
    
    lifecycleScope.launch {
      Log.d(TAG, "Unlocking...")
      
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
  
  fun lockDevice() {
    val device = connectedDevice ?: run {
      showToast("No device connected")
      return
    }
    
    lifecycleScope.launch {
      Log.d(TAG, "Locking...")
      
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
  
  fun disconnectDevice() {
    val device = connectedDevice ?: return
    
    lifecycleScope.launch {
      sdk.disconnectDevice(device.deviceId)
      connectedDevice = null
      showToast("Disconnected")
    }
  }
  
  private fun showToast(message: String) {
    runOnUiThread {
      Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
  }
}
