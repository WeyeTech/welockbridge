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
 *
 * UPDATED: Added TT-Series support
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

/**
 * Lock protocol types supported by the SDK.
 */
enum class LockProtocol(val displayName: String) {
    G_SERIES("G-Series Protocol"),
    TT_SERIES("TT-Series Protocol"),
    AUTO_DETECT("Auto Detect")
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

/**
 * Interface for TT-Series specific operations.
 * Extends LockableDevice with TT-protocol specific features.
 */
interface TTSeriesLockDevice : LockableDevice {
    /** Calibrate device time to current system time */
    suspend fun calibrateTime(): Result<Unit>
    
    /** Get firmware version string */
    suspend fun getVersion(): Result<String>
    
    /** Set work mode (sleep or real-time) */
    suspend fun setWorkMode(sleepMode: Boolean): Result<Unit>
    
    /** Get detected lock ID as string (8 digits) */
    fun getLockIdString(): String?
    
    /** Get current battery level percentage */
    fun getBatteryLevel(): Int
}

/**
 * Interface for G-Series specific operations.
 * Extends LockableDevice. G-Series locks use the standard LockableDevice
 * interface for all operations. This interface serves as a type marker.
 */
interface GSeriesLockDevice : LockableDevice

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
    val advertisementData: ByteArray? = null,
    val detectedProtocol: LockProtocol = LockProtocol.AUTO_DETECT
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
 *
 * UPDATED: Added support for TT-Series specific fields (lockId, password)
 */
data class DeviceCredentials private constructor(
    internal val encryptionKey: ByteArray?,
    internal val password: String?,
    internal val lockId: String?,
    internal val protocol: LockProtocol,
    private val timestamp: Long
) {
    companion object {
        /**
         * Create credentials with encryption key (G-Series style).
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
                    lockId = null,
                    protocol = LockProtocol.G_SERIES,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        /**
         * Create credentials with encryption key and password (G-Series style).
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
                    lockId = null,
                    protocol = LockProtocol.G_SERIES,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        /**
         * Create credentials for TT-Series lock.
         *
         * @param lockId 8-digit lock ID string (e.g., "83181001")
         * @param password 6-digit password string
         * @param encryptionKey Optional 16-byte AES key for encrypted mode
         */
        fun forTTSeries(
            lockId: String,
            password: String,
            encryptionKey: ByteArray? = null
        ): Result<DeviceCredentials> {
            if (lockId.length != 8 || !lockId.all { it.isDigit() }) {
                return Result.failure(
                    IllegalArgumentException("Lock ID must be exactly 8 digits")
                )
            }
            if (password.length != 6 || !password.all { it.isDigit() }) {
                return Result.failure(
                    IllegalArgumentException("Password must be exactly 6 digits")
                )
            }
            if (encryptionKey != null && encryptionKey.size != 16) {
                return Result.failure(
                    IllegalArgumentException("Encryption key must be exactly 16 bytes")
                )
            }
            return Result.success(
                DeviceCredentials(
                    encryptionKey = encryptionKey?.copyOf(),
                    password = password,
                    lockId = lockId,
                    protocol = LockProtocol.TT_SERIES,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        /**
         * Create credentials for TT-Series lock with default (zero) lock ID.
         * Lock ID will be auto-detected from device responses.
         *
         * @param password 6-digit password string
         * @param encryptionKey Optional 16-byte AES key for encrypted mode
         */
        fun forTTSeriesAutoDetect(
            password: String,
            encryptionKey: ByteArray? = null
        ): Result<DeviceCredentials> {
            if (password.length != 6 || !password.all { it.isDigit() }) {
                return Result.failure(
                    IllegalArgumentException("Password must be exactly 6 digits")
                )
            }
            if (encryptionKey != null && encryptionKey.size != 16) {
                return Result.failure(
                    IllegalArgumentException("Encryption key must be exactly 16 bytes")
                )
            }
            return Result.success(
                DeviceCredentials(
                    encryptionKey = encryptionKey?.copyOf(),
                    password = password,
                    lockId = "00000000", // Auto-detect
                    protocol = LockProtocol.TT_SERIES,
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
                lockId = null,
                protocol = LockProtocol.G_SERIES,
                timestamp = System.currentTimeMillis()
            )
        }

        /**
         * Create default TT-Series credentials (for testing only).
         * WARNING: Should not be used in production.
         */
        fun withDefaultTTSeriesKey(): DeviceCredentials {
            return DeviceCredentials(
                encryptionKey = null,
                password = "112233", // Default TT password
                lockId = "00000000",
                protocol = LockProtocol.TT_SERIES,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    /** Check if credentials have expired (24 hours) */
    fun isExpired(): Boolean {
        val expirationMs = 24 * 60 * 60 * 1000L
        return System.currentTimeMillis() - timestamp > expirationMs
    }

    /** Check if this is TT-Series credentials */
    fun isTTSeries(): Boolean = protocol == LockProtocol.TT_SERIES

    /** Check if this is G-Series credentials */
    fun isGSeries(): Boolean = protocol == LockProtocol.G_SERIES

    /** Get lock ID as byte array (for TT-Series) */
    fun getLockIdBytes(): ByteArray? {
        return lockId?.let { id ->
            if (id.length == 8) {
                val high = id.substring(0, 4).toInt()
                val low = id.substring(4, 8).toInt()
                byteArrayOf(
                    ((high shr 8) and 0xFF).toByte(),
                    (high and 0xFF).toByte(),
                    ((low shr 8) and 0xFF).toByte(),
                    (low and 0xFF).toByte()
                )
            } else null
        }
    }

    /** Get password as 6-byte array (for TT-Series) */
    fun getPasswordBytes(): ByteArray? {
        return password?.take(6)?.padEnd(6, '0')?.toByteArray(Charsets.US_ASCII)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceCredentials) return false
        if (encryptionKey != null) {
            if (other.encryptionKey == null) return false
            if (!encryptionKey.contentEquals(other.encryptionKey)) return false
        } else if (other.encryptionKey != null) return false
        if (password != other.password) return false
        if (lockId != other.lockId) return false
        if (protocol != other.protocol) return false
        return true
    }

    override fun hashCode(): Int {
        var result = encryptionKey?.contentHashCode() ?: 0
        result = 31 * result + (password?.hashCode() ?: 0)
        result = 31 * result + (lockId?.hashCode() ?: 0)
        result = 31 * result + protocol.hashCode()
        return result
    }

    override fun toString(): String =
        "DeviceCredentials(protocol=$protocol, hasKey=${encryptionKey != null}, hasPassword=${password != null}, hasLockId=${lockId != null})"
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
    class UnsupportedProtocolException(protocol: String) : SdkException("Unsupported protocol: $protocol")
}
