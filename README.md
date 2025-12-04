# WeLockBridge BLE SDK

A professional, SOLID-compliant Bluetooth Low Energy SDK for G-Series digital locks.

## Features

- Clean, simple public API
- Automatic BLE service discovery
- AES-128 encrypted communication
- Automatic status polling
- Observable device state via Kotlin Flow
- ProGuard obfuscation for security
- Support for Android 6.0+ (API 23+)

## Quick Start

### 1. Add Dependency

```kotlin
// In your app's build.gradle.kts
dependencies {
    implementation(project(":welockbridge"))
    // OR if using AAR:
    implementation(files("libs/welockbridge.aar"))
}
```

### 2. Request Permissions

```kotlin
// Android 12+ (API 31+)
val permissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT
)

// Android 11 and below
val permissions = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION
)
```

### 3. Initialize SDK

```kotlin
val sdk = WeLockBridgeSdk.getInstance(context)
```

### 4. Scan for Devices

```kotlin
sdk.startScan(
    deviceTypes = setOf(DeviceType.DIGITAL_LOCK),
    onDeviceFound = { devices ->
        devices.forEach { device ->
            println("Found: ${device.name} (${device.address})")
        }
    },
    onScanComplete = {
        println("Scan finished")
    }
)
```

### 5. Connect to Device

```kotlin
// Create credentials
val encryptionKey = "YOUR_16_BYTE_KEY!".toByteArray(Charsets.US_ASCII)
val credentials = DeviceCredentials.withKeyAndPassword(
    key = encryptionKey,
    password = "12341234"
).getOrThrow()

// Connect
val result = sdk.connectToDevice(scannedDevice, credentials)

result.onSuccess { device ->
    println("Connected to ${device.deviceName}")
}.onFailure { error ->
    println("Connection failed: ${error.message}")
}
```

### 6. Lock/Unlock Operations

```kotlin
val lockDevice = sdk.getLockableDevice(deviceId)

// Unlock
lockDevice?.unlock()?.onSuccess {
    println("Unlocked successfully")
}

// Lock
lockDevice?.lock()?.onSuccess {
    println("Locked successfully")
}
```

### 7. Get Lock Status

```kotlin
val lockDevice = sdk.getLockableDevice(deviceId)

// Method 1: Get current state (synchronous, cached)
val currentState = lockDevice?.getCurrentLockState()
println("Current state: $currentState") // LOCKED, UNLOCKED, or UNKNOWN

// Method 2: Query fresh status from device (async)
lockDevice?.queryLockStatus()?.onSuccess { state ->
    println("Queried state: $state")
}

// Method 3: Get full device status (includes battery)
val statusDevice = sdk.getStatusReportingDevice(deviceId)
statusDevice?.queryStatus()?.onSuccess { status ->
    println("Lock: ${status.lockState}")
    println("Battery: ${status.batteryLevel}%")
    println("Connected: ${status.isConnected}")
}
```

### 8. Observe State Changes (Reactive)

```kotlin
val lockDevice = sdk.getLockableDevice(deviceId)

// Observe lock state changes
lifecycleScope.launch {
    lockDevice?.lockState?.collect { state ->
        when (state) {
            LockState.LOCKED -> showLockedUI()
            LockState.UNLOCKED -> showUnlockedUI()
            LockState.UNKNOWN -> showLoadingUI()
        }
    }
}

// Observe connection state changes
lifecycleScope.launch {
    lockDevice?.connectionState?.collect { state ->
        when (state) {
            is ConnectionState.Connected -> showConnectedUI()
            is ConnectionState.Disconnected -> showDisconnectedUI()
            is ConnectionState.Connecting -> showConnectingUI()
            is ConnectionState.Error -> showError(state.message)
        }
    }
}
```

### 9. Disconnect

```kotlin
sdk.disconnectDevice(deviceId)
// Or disconnect all:
sdk.disconnectAll()
```

## Complete API Reference

### WeLockBridgeSdk (Main Entry Point)

| Method | Returns | Description |
|--------|---------|-------------|
| `getInstance(context)` | `WeLockBridgeSdk` | Get SDK singleton instance |
| `isBluetoothAvailable()` | `Boolean` | Check if BLE is available |
| `isBluetoothEnabled()` | `Boolean` | Check if Bluetooth is on |
| `startScan(...)` | `Unit` | Start scanning for devices |
| `stopScan()` | `Unit` | Stop current scan |
| `connectToDevice(device, credentials)` | `Result<BluetoothDevice>` | Connect to a device |
| `disconnectDevice(deviceId)` | `Result<Unit>` | Disconnect specific device |
| `disconnectAll()` | `Unit` | Disconnect all devices |
| `getDevice(deviceId)` | `BluetoothDevice?` | Get any connected device |
| `getLockableDevice(deviceId)` | `LockableDevice?` | Get device with lock features |
| `getStatusReportingDevice(deviceId)` | `StatusReportingDevice?` | Get device with status features |
| `getConnectedDevices()` | `List<BluetoothDevice>` | Get all connected devices |
| `getConnectedDeviceCount()` | `Int` | Count of connected devices |
| `scanState` | `Flow<ScanState>` | Observable scan state |

### LockableDevice (Lock Control)

| Member | Type | Description |
|--------|------|-------------|
| `deviceId` | `String` | MAC address |
| `deviceName` | `String` | Device name |
| `deviceType` | `DeviceType` | Type of device |
| `lockState` | `Flow<LockState>` | **Observable lock state** |
| `connectionState` | `Flow<ConnectionState>` | **Observable connection state** |
| `lock()` | `suspend Result<Boolean>` | Lock the device |
| `unlock()` | `suspend Result<Boolean>` | Unlock the device |
| `getCurrentLockState()` | `LockState` | **Get cached lock state** |
| `queryLockStatus()` | `suspend Result<LockState>` | **Query fresh lock status** |
| `isConnected()` | `Boolean` | Check connection status |
| `connect()` | `suspend Result<Unit>` | Connect to device |
| `disconnect()` | `suspend Result<Unit>` | Disconnect from device |

### StatusReportingDevice (Full Status)

| Member | Type | Description |
|--------|------|-------------|
| `queryStatus()` | `suspend Result<DeviceStatus>` | **Get full device status** |

### DeviceStatus (Status Data)

| Field | Type | Description |
|-------|------|-------------|
| `lockState` | `LockState` | Current lock state |
| `batteryLevel` | `Int` | Battery percentage (0-100) |
| `isConnected` | `Boolean` | Connection status |
| `signalStrength` | `Int` | RSSI signal strength |
| `lastUpdated` | `Long` | Timestamp of last update |

### LockState (Enum)

| Value | Description |
|-------|-------------|
| `LOCKED` | Device is locked |
| `UNLOCKED` | Device is unlocked |
| `UNKNOWN` | State not yet determined |

### ConnectionState (Sealed Class)

| Value | Description |
|-------|-------------|
| `Connected` | Successfully connected |
| `Disconnected` | Not connected |
| `Connecting` | Connection in progress |
| `Error(message)` | Connection error with details |

### DeviceCredentials

```kotlin
// With encryption key only (16 bytes required)
DeviceCredentials.withEncryptionKey(key): Result<DeviceCredentials>

// With key and password (password: 4-16 chars)
DeviceCredentials.withKeyAndPassword(key, password): Result<DeviceCredentials>

// Default credentials (testing only - NOT for production)
DeviceCredentials.withDefaultKey(): DeviceCredentials

// Check if expired (24 hour expiration)
credentials.isExpired(): Boolean
```

## Usage Examples

### Example 1: Simple Lock/Unlock

```kotlin
class SimpleLockActivity : AppCompatActivity() {
    private lateinit var sdk: WeLockBridgeSdk
    private var lock: LockableDevice? = null
    
    fun connectAndUnlock(deviceAddress: String) {
        lifecycleScope.launch {
            val scannedDevice = ScannedDevice(deviceAddress, "Lock", -50, DeviceType.DIGITAL_LOCK)
            val credentials = DeviceCredentials.withDefaultKey()
            
            sdk.connectToDevice(scannedDevice, credentials).onSuccess {
                lock = sdk.getLockableDevice(deviceAddress)
                lock?.unlock()
            }
        }
    }
}
```

### Example 2: Status Monitoring Dashboard

```kotlin
class DashboardActivity : AppCompatActivity() {
    private lateinit var sdk: WeLockBridgeSdk
    
    fun monitorDevice(deviceId: String) {
        val lock = sdk.getLockableDevice(deviceId) ?: return
        val statusDevice = sdk.getStatusReportingDevice(deviceId)
        
        // Real-time lock state updates
        lifecycleScope.launch {
            lock.lockState.collect { state ->
                binding.tvLockState.text = when (state) {
                    LockState.LOCKED -> "🔒 Locked"
                    LockState.UNLOCKED -> "🔓 Unlocked"
                    LockState.UNKNOWN -> "❓ Unknown"
                }
            }
        }
        
        // Periodic full status refresh
        lifecycleScope.launch {
            while (isActive) {
                statusDevice?.queryStatus()?.onSuccess { status ->
                    binding.tvBattery.text = "🔋 ${status.batteryLevel}%"
                    binding.tvLastUpdate.text = "Updated: ${formatTime(status.lastUpdated)}"
                }
                delay(30_000) // Refresh every 30 seconds
            }
        }
    }
}
```

### Example 3: Connection State Handling

```kotlin
fun observeConnection(deviceId: String) {
    val device = sdk.getLockableDevice(deviceId) ?: return
    
    lifecycleScope.launch {
        device.connectionState.collect { state ->
            when (state) {
                is ConnectionState.Connected -> {
                    showToast("Connected!")
                    enableControls(true)
                }
                is ConnectionState.Disconnected -> {
                    showToast("Disconnected")
                    enableControls(false)
                }
                is ConnectionState.Connecting -> {
                    showProgress(true)
                }
                is ConnectionState.Error -> {
                    showError(state.message)
                    enableControls(false)
                }
            }
        }
    }
}
```

## Architecture

```
com.welockbridge.sdk/
├── WeLockBridgeSdk.kt          # Main facade (public)
├── core/
│   └── Types.kt                # Interfaces, enums, data classes (public)
├── internal/                   # Hidden from clients
│   └── BleConnectionManager.kt # Low-level BLE operations
├── protocol/
│   └── GSeriesProtocol.kt      # G-Series protocol implementation
├── device/
│   └── GSeriesDigitalLock.kt   # Lock device implementation
├── scanner/
│   └── BleDeviceScanner.kt     # Device discovery
└── example/
    └── ExampleActivity.kt      # Usage example
```

## Security Best Practices

1. **Store keys securely**: Use Android Keystore for encryption keys
2. **Rotate credentials**: Set up credential refresh mechanism
3. **Enable ProGuard**: Always use ProGuard in release builds
4. **Validate input**: All credentials are validated on creation
5. **Check expiration**: Credentials expire after 24 hours

## Requirements

- Android SDK 23+ (Android 6.0 Marshmallow)
- Kotlin 1.8+
- Bluetooth LE hardware

## Troubleshooting

### Scan returns no devices
- Ensure Bluetooth is enabled
- Check location permissions (Android 11 and below)
- Verify device is in range (< 15 meters)

### Connection fails
- Verify encryption key is correct (16 bytes)
- Check device is not connected to another app
- Try power cycling the lock

### Commands fail
- Verify password is correct
- Check device is still connected
- Ensure credentials haven't expired

## License

Copyright © 2024 WeLockBridge. All rights reserved.

## Support

For technical support, contact: support@welockbridge.com
