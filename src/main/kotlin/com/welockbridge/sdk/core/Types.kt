package com.welockbridge.sdk.core

import kotlinx.coroutines.flow.Flow

/**
 * WeLockBridge SDK - Core Types and Interfaces
 *
 * This file contains all public types, interfaces, and enums that clients
 * interact with. Following SOLID principles:
 * - Single Responsibility: Each interface has one clear purpose
 * - Open/Closed: Extensible for new device types without modification
 * - Liskov Substitution: All implementations are interchangeable
 * - Interface Segregation: Small, focused interfaces
 * - Dependency Inversion: Depend on abstractions, not concretions
 */

// ============================================================================
// DEVICE STATE ENUMS
// ============================================================================

/**
 * Represents the connection state of a BLE device.
 */
sealed class ConnectionState {
  object Disconnected : ConnectionState() {
    override fun toString(): String = "Disconnected"
  }
  object Connecting : ConnectionState() {
    override fun toString(): String = "Connecting"
  }
  object Connected : ConnectionState() {
    override fun toString(): String = "Connected"
  }
  data class Error(val message: String, val cause: Throwable? = null) : ConnectionState() {
    override fun toString(): String = "Error: $message"
  }
}

/**
 * Represents the lock state of a digital lock device.
 */
enum class LockState {
  LOCKED,
  UNLOCKED,
  UNKNOWN
}

/**
 * Supported device types in the SDK.
 * New device types can be added here.
 */
enum class DeviceType(val displayName: String) {
  DIGITAL_LOCK("Digital Lock"),
  GPS_TRACKER("GPS Tracker"),
  TEMPERATURE_SENSOR("Temperature Sensor"),
  MOTION_SENSOR("Motion Sensor"),
  GATEWAY("Gateway"),
  UNKNOWN("Unknown Device")
}

// ============================================================================
// DEVICE INTERFACES
// ============================================================================

/**
 * Base interface for all Bluetooth devices managed by the SDK.
 * Provides common functionality for connection management.
 */
interface BluetoothDevice {
  /** Unique identifier (MAC address) */
  val deviceId: String
  
  /** Human-readable device name */
  val deviceName: String
  
  /** Type of device */
  val deviceType: DeviceType
  
  /** Observable connection state */
  val connectionState: Flow<ConnectionState>
  
  /** Connect to the device */
  suspend fun connect(): Result<Unit>
  
  /** Disconnect from the device */
  suspend fun disconnect(): Result<Unit>
  
  /** Check if currently connected */
  fun isConnected(): Boolean
}

/**
 * Interface for devices that support lock/unlock operations.
 * Implements Interface Segregation Principle.
 */
interface LockableDevice : BluetoothDevice {
  /** Observable lock state */
  val lockState: Flow<LockState>
  
  /** Get current lock state synchronously */
  fun getCurrentLockState(): LockState
  
  /** Lock the device */
  suspend fun lock(): Result<Boolean>
  
  /** Unlock the device */
  suspend fun unlock(): Result<Boolean>
  
  /** Query the current lock status from device */
  suspend fun queryLockStatus(): Result<LockState>
}

/**
 * Interface for devices that report status information.
 */
interface StatusReportingDevice : BluetoothDevice {
  /** Query device status */
  suspend fun queryStatus(): Result<DeviceStatus>
}

// ============================================================================
// DATA CLASSES
// ============================================================================

/**
 * Represents a scanned BLE device before connection.
 */
data class ScannedDevice(
  val address: String,
  val name: String?,
  val rssi: Int,
  val deviceType: DeviceType,
  val advertisementData: ByteArray? = null
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ScannedDevice) return false
    return address == other.address
  }
  
  override fun hashCode(): Int = address.hashCode()
}

/**
 * Device status information.
 */
data class DeviceStatus(
  val lockState: LockState,
  val batteryLevel: Int,
  val isConnected: Boolean,
  val signalStrength: Int,
  val lastUpdated: Long
)

/**
 * Configuration for connecting to a device.
 * Immutable to prevent tampering after creation.
 */
data class DeviceCredentials private constructor(
  internal val encryptionKey: ByteArray?,
  internal val password: String?,
  private val timestamp: Long
) {
  companion object {
    /**
     * Create credentials with encryption key.
     * Key must be exactly 16 bytes for AES-128.
     */
    fun withEncryptionKey(key: ByteArray): Result<DeviceCredentials> {
      if (key.size != 16) {
        return Result.failure(
          IllegalArgumentException("Encryption key must be exactly 16 bytes")
        )
      }
      return Result.success(
        DeviceCredentials(
          encryptionKey = key.copyOf(),
          password = null,
          timestamp = System.currentTimeMillis()
        )
      )
    }
    
    /**
     * Create credentials with encryption key and password.
     */
    fun withKeyAndPassword(key: ByteArray, password: String): Result<DeviceCredentials> {
      if (key.size != 16) {
        return Result.failure(
          IllegalArgumentException("Encryption key must be exactly 16 bytes")
        )
      }
      if (password.length < 4 || password.length > 16) {
        return Result.failure(
          IllegalArgumentException("Password must be 4-16 characters")
        )
      }
      return Result.success(
        DeviceCredentials(
          encryptionKey = key.copyOf(),
          password = password,
          timestamp = System.currentTimeMillis()
        )
      )
    }
    
    /**
     * Create credentials using default key (for testing only).
     * WARNING: Should not be used in production.
     */
    fun withDefaultKey(): DeviceCredentials {
      return DeviceCredentials(
        encryptionKey = "874655353424242".toByteArray(Charsets.US_ASCII),
        password = "85763534242",
        timestamp = System.currentTimeMillis()
      )
    }
  }
  
  /** Check if credentials have expired (24 hours) */
  fun isExpired(): Boolean {
    val expirationMs = 24 * 60 * 60 * 1000L
    return System.currentTimeMillis() - timestamp > expirationMs
  }
  
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is DeviceCredentials) return false
    if (encryptionKey != null) {
      if (other.encryptionKey == null) return false
      if (!encryptionKey.contentEquals(other.encryptionKey)) return false
    } else if (other.encryptionKey != null) return false
    if (password != other.password) return false
    return true
  }
  
  override fun hashCode(): Int {
    var result = encryptionKey?.contentHashCode() ?: 0
    result = 31 * result + (password?.hashCode() ?: 0)
    return result
  }
  
  override fun toString(): String = "DeviceCredentials(hasKey=${encryptionKey != null}, hasPassword=${password != null})"
}

// ============================================================================
// ERROR TYPES
// ============================================================================

/**
 * SDK-specific exceptions for better error handling.
 */
sealed class SdkException(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class NotConnectedException : SdkException("Device is not connected")
  class ConnectionFailedException(cause: Throwable?) : SdkException("Failed to connect to device", cause)
  class AuthenticationFailedException : SdkException("Device authentication failed")
  class EncryptionKeyRequiredException : SdkException("Encryption key is required for this operation")
  class InvalidCredentialsException : SdkException("Invalid credentials provided")
  class CommandFailedException(val errorCode: Int?) : SdkException("Command execution failed. Error code: $errorCode")
  class TimeoutException(operation: String) : SdkException("Operation timed out: $operation")
  class DeviceNotFoundException(address: String) : SdkException("Device not found: $address")
  class PermissionDeniedException(permission: String) : SdkException("Permission denied: $permission")
}
