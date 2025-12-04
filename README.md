# WeLockBridge BLE SDK

A professional, SOLID-compliant Bluetooth Low Energy SDK for digital locks.

## Supported Protocols

| Protocol | Description | Document |
|----------|-------------|----------|
| **G-Series** | IMZ BLE G-Series Padlock Protocol | V11 |
| **TT-Series** | TOTARGET BLE-ELOCK Protocol | A7 |

## Features

- Clean, simple public API
- Multi-protocol support (G-Series, TT-Series)
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
val config = SdkConfig.Builder()
    .addMacPrefix("DC:0D")       // G-Series
    .addNamePattern("g4-")        // G-Series
    .addNamePattern("tt-")        // TT-Series
    .addNamePattern("elock")      // TT-Series
    .build()

val sdk = WeLockBridgeSdk.getInstance(context, config)
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

#### G-Series Lock

```kotlin
// Create G-Series credentials
val encryptionKey = "YOUR_16_BYTE_KEY!".toByteArray(Charsets.US_ASCII) // Must be 16 bytes
val credentials = DeviceCredentials.withKeyAndPassword(
    key = encryptionKey,
    password = "YOUR_PASSWORD"
).getOrThrow()

// Connect
val result = sdk.connectToDevice(scannedDevice, credentials)
```

#### TT-Series Lock

```kotlin
// Create TT-Series credentials
val credentials = DeviceCredentials.forTTSeries(
    lockId = "83181001",    // 8-digit lock ID
    password = "112233"      // 6-digit password
    // Optional: encryptionKey for encrypted mode
).getOrThrow()

// Connect
val result = sdk.connectToDevice(scannedDevice, credentials)
```

#### Auto-Detect Lock ID (TT-Series)

```kotlin
// If you don't know the lock ID, use auto-detect
val credentials = DeviceCredentials.forTTSeriesAutoDetect(
    password = "112233"
).getOrThrow()

// Lock ID will be extracted from device responses
```

### 6. Lock/Unlock Operations

```kotlin
val lockDevice = sdk.getLockableDevice(deviceId)

// Unlock - works with both protocols
lockDevice?.unlock()?.onSuccess {
    println("Unlocked successfully")
}

// Lock - works with both protocols
lockDevice?.lock()?.onSuccess {
    println("Locked successfully")
}
```

### 7. Get Lock Status

```kotlin
val lockDevice = sdk.getLockableDevice(deviceId)

// Method 1: Get current state (synchronous, cached)
val currentState = lockDevice?.getCurrentLockState()
println("Current state: $currentState")

// Method 2: Query fresh status from device (async)
lockDevice?.queryLockStatus()?.onSuccess { state ->
    println("Queried state: $state")
}

// Method 3: Get full device status (includes battery)
val statusDevice = sdk.getStatusReportingDevice(deviceId)
statusDevice?.queryStatus()?.onSuccess { status ->
    println("Lock: ${status.lockState}")
    println("Battery: ${status.batteryLevel}%")
}
```

### 8. TT-Series Specific Features

```kotlin
val ttLock = sdk.getTTSeriesLock(deviceId)

// Get firmware version
ttLock?.getVersion()?.onSuccess { version ->
    println("Firmware: $version")
}

// Get detected lock ID
val lockIdString = ttLock?.getLockIdString()
println("Lock ID: $lockIdString")

// Get battery level
val battery = ttLock?.getBatteryLevel()
println("Battery: $battery%")

// Set work mode (Sleep or Real-time)
ttLock?.setWorkMode(sleepMode = true)?.onSuccess {
    println("Set to sleep mode")
}

// Calibrate time
ttLock?.calibrateTime()?.onSuccess {
    println("Time calibrated")
}
```

### 9. Observe State Changes

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

// Observe connection state
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

### 10. Check Protocol Type

```kotlin
// Get protocol being used
val protocol = sdk.getDeviceProtocol(deviceId)
when (protocol) {
    LockProtocol.G_SERIES -> println("Using G-Series protocol")
    LockProtocol.TT_SERIES -> println("Using TT-Series protocol")
    else -> println("Unknown protocol")
}
```

### 11. Disconnect

```kotlin
sdk.disconnectDevice(deviceId)
// Or disconnect all:
sdk.disconnectAll()
```

## Protocol Comparison

| Feature | G-Series | TT-Series |
|---------|----------|-----------|
| Frame Header/Tail | 0xF11F / 0xF22F | None |
| Checksum | SunCheck + CRC16 | CRC8 (MAXIM) |
| Authentication | Encryption Key + Password | Lock ID + Password |
| Key Size | 16 bytes (AES-128) | 16 bytes (optional) |
| Password | 4-16 characters | 6 digits |
| Lock ID | Not used | 8 digits |
| Encryption | Required | Optional |
| Time Sync | Via parameter | Dedicated command |
| Work Mode | N/A | Sleep / Real-time |
| Heartbeat | N/A | Supported |

## Credentials Reference

### G-Series Credentials

```kotlin
// With encryption key only
DeviceCredentials.withEncryptionKey(key): Result<DeviceCredentials>

// With key and password
DeviceCredentials.withKeyAndPassword(key, password): Result<DeviceCredentials>

// Default (testing only)
DeviceCredentials.withDefaultKey(): DeviceCredentials
```

### TT-Series Credentials

```kotlin
// Full credentials
DeviceCredentials.forTTSeries(
    lockId: String,           // 8-digit lock ID
    password: String,         // 6-digit password
    encryptionKey: ByteArray? // Optional AES key
): Result<DeviceCredentials>

// Auto-detect lock ID
DeviceCredentials.forTTSeriesAutoDetect(
    password: String,
    encryptionKey: ByteArray?
): Result<DeviceCredentials>

// Default (testing only)
DeviceCredentials.withDefaultTTSeriesKey(): DeviceCredentials
```

## Complete API Reference

### WeLockBridgeSdk

| Method | Returns | Description |
|--------|---------|-------------|
| `getInstance(context)` | `WeLockBridgeSdk` | Get SDK singleton |
| `isBluetoothAvailable()` | `Boolean` | Check BLE availability |
| `isBluetoothEnabled()` | `Boolean` | Check Bluetooth state |
| `startScan(...)` | `Unit` | Start device scan |
| `stopScan()` | `Unit` | Stop current scan |
| `connectToDevice(device, credentials)` | `Result<BluetoothDevice>` | Connect to device |
| `disconnectDevice(deviceId)` | `Result<Unit>` | Disconnect device |
| `disconnectAll()` | `Unit` | Disconnect all devices |
| `getDevice(deviceId)` | `BluetoothDevice?` | Get connected device |
| `getLockableDevice(deviceId)` | `LockableDevice?` | Get lockable device |
| `getStatusReportingDevice(deviceId)` | `StatusReportingDevice?` | Get status device |
| `getTTSeriesLock(deviceId)` | `TTSeriesLockDevice?` | Get TT-Series device |
| `getGSeriesLock(deviceId)` | `GSeriesLockDevice?` | Get G-Series device |
| `getDeviceProtocol(deviceId)` | `LockProtocol?` | Get device protocol |
| `getConnectedDevices()` | `List<BluetoothDevice>` | All connected devices |
| `getConnectedDeviceCount()` | `Int` | Connected device count |
| `scanState` | `Flow<ScanState>` | Observable scan state |

### LockableDevice (Common Interface)

| Member | Type | Description |
|--------|------|-------------|
| `deviceId` | `String` | MAC address |
| `deviceName` | `String` | Device name |
| `deviceType` | `DeviceType` | Device type |
| `lockState` | `Flow<LockState>` | Observable lock state |
| `connectionState` | `Flow<ConnectionState>` | Observable connection |
| `lock()` | `suspend Result<Boolean>` | Lock device |
| `unlock()` | `suspend Result<Boolean>` | Unlock device |
| `getCurrentLockState()` | `LockState` | Get cached state |
| `queryLockStatus()` | `suspend Result<LockState>` | Query fresh state |
| `isConnected()` | `Boolean` | Connection status |

### TTSeriesLockDevice (TT-Specific)

| Member | Type | Description |
|--------|------|-------------|
| `calibrateTime()` | `suspend Result<Unit>` | Sync device time |
| `getVersion()` | `suspend Result<String>` | Get firmware version |
| `setWorkMode(sleepMode)` | `suspend Result<Unit>` | Set work mode |
| `getLockIdString()` | `String?` | Get detected lock ID |
| `getBatteryLevel()` | `Int` | Get battery percentage |

## TT-Series Protocol Details

### Frame Structure

```
| Encryption Type (1B) | Length (1B) | Business Data (nB) | CRC8 (1B) |
```

- **Encryption Type**: 0x01 = Plain, 0x11 = AES
- **Length**: Length of business data (before encryption)
- **CRC8**: CRC-8/MAXIM polynomial

### Command Codes

| Command | Code | Description |
|---------|------|-------------|
| Calibrate Time | 0x20 | Sync device clock |
| Check Version | 0x21 | Get firmware version |
| Set Work Mode | 0x29 | Sleep/Real-time mode |
| Check Status | 0x12 | Query lock state |
| Lock | 0x31 | Lock the device |
| Unlock | 0x37 | Unlock the device |

### Response Codes

| Code | Description |
|------|-------------|
| 0x62 | Check OK |
| 0x80 | Lock success |
| 0x81 | Already locked |
| 0x90 | Unlock success |
| 0x91 | Already unlocked |
| 0x93 | Wrong password |

### Lock Status Byte

```
| High Nibble (Status) | Low Nibble (Alarm Flags) |
```

**Status Types:**
- 0x10: Open
- 0x20: Standby
- 0x40: Sealed (Locked)
- 0x60: Unsealed (Unlocked)
- 0x70: Alarm

**Alarm Flags (when status = 0x70):**
- Bit 0: Rod cut
- Bit 1: Opened
- Bit 2: Shell opened
- Bit 3: Emergency

## Architecture

```
com.welockbridge.sdk/
├── WeLockBridgeSdk.kt           # Main facade (public)
├── core/
│   └── Types.kt                 # Interfaces, enums, data classes
├── internal/
│   └── BleConnectionManager.kt  # Low-level BLE operations
├── protocol/
│   ├── GSeriesProtocol.kt       # G-Series protocol
│   └── TTSeriesProtocol.kt      # TT-Series protocol
├── device/
│   ├── GSeriesDigitalLock.kt    # G-Series implementation
│   └── TTSeriesDigitalLock.kt   # TT-Series implementation
├── scanner/
│   └── BleDeviceScanner.kt      # Device discovery
└── example/
    └── ExampleActivity.kt       # Usage examples
```

## Security Best Practices

1. **Store keys securely**: Use Android Keystore
2. **Rotate credentials**: Implement credential refresh
3. **Enable ProGuard**: Always in release builds
4. **Validate input**: All credentials validated on creation
5. **Check expiration**: Credentials expire after 24 hours
6. **Use encryption**: Enable AES for TT-Series in production

## Troubleshooting

### Scan returns no devices
- Ensure Bluetooth is enabled
- Check location permissions (Android 11-)
- Verify device is in range (<15 meters)
- Add correct MAC prefixes/name patterns

### Connection fails
- Verify credentials are correct
- Check device is not connected elsewhere
- Try power cycling the lock
- Ensure correct protocol selected

### TT-Series specific issues
- **Wrong password error**: Verify 6-digit password
- **Lock ID unknown**: Use auto-detect credentials
- **No response**: Check if device is in sleep mode

### G-Series specific issues
- **Serial number error**: Wait between commands
- **CRC error**: Verify encryption key
- **Command failed**: Check password

## License

Copyright © 2024 WeLockBridge. All rights reserved.

## Support

For technical support, contact: support@welockbridge.com
